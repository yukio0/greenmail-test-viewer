package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.MailPart;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.ParsedAttachment;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.PartTree;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the bodies, the parts and the attachments of a mail. Mails from tests can be broken, so a
 * part that cannot be read is recorded with what is known, and parsing continues.
 */
final class MimeMessageParser {

  private static final String DEFAULT_CONTENT_TYPE = "text/plain";
  private static final Pattern FOLDING = Pattern.compile("\\s*\\r?\\n\\s*");
  private static final Pattern CHARSET =
      Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\"\\s]+)\"?");

  ParsedMail parse(MimeMessage message) {
    State state = new State(id -> true);
    PartTree partTree = collectPart(message, state, "message");
    String htmlBody = nonBlank(state.bodies.htmlBody);
    return new ParsedMail(
        textBody(state),
        htmlBody,
        htmlBody == null ? null : state.bodies.htmlPath,
        state.parts,
        partTree,
        state.attachments);
  }

  Bodies parseBodies(MimeMessage message) {
    State state = new State(id -> false);
    collectPart(message, state, "message");
    return new Bodies(textBody(state), nonBlank(state.bodies.htmlBody), state.attachmentCount());
  }

  ParsedAttachment findAttachment(MimeMessage message, String attachmentId) {
    State state = new State(attachmentId::equals);
    collectPart(message, state, "message");
    return state.attachments.isEmpty() ? null : state.attachments.get(0);
  }

  private static String textBody(State state) {
    String textBody = nonBlank(state.bodies.textBody);
    return textBody != null ? textBody : nonBlank(state.bodies.otherTextBody);
  }

  private PartTree collectPart(Part part, State state, String path) {
    return collectPart(part, state, path, false);
  }

  private PartTree collectPart(Part part, State state, String path, boolean relatedRoot) {
    // Decided from its headers, before Jakarta Mail reads its content.
    if (!(relatedRoot && canBeBody(part)) && isAttachmentCandidate(part)) {
      return collectAttachment(part, state, path);
    }

    Object content = safeContent(part);

    if (content instanceof Multipart) {
      MailPart mailPart = recordPart(part, state, path);
      return new PartTree(mailPart, collectMultipart((Multipart) content, state, path, part));
    }

    if (matchesMimeType(part, "text/html")) {
      return collectTextPart(part, state, path, true, content);
    }
    if (matchesMimeType(part, "text/plain")) {
      return collectTextPart(part, state, path, false, content);
    }
    if (matchesMimeType(part, "text/*")) {
      return collectOtherTextPart(part, state, path, content);
    }
    if (matchesMimeType(part, "multipart/*")) {
      return new PartTree(recordPart(part, state, path), List.of());
    }
    if (matchesMimeType(part, "message/rfc822")) {
      MailPart mailPart = recordPart(part, state, path);
      List<PartTree> children = new ArrayList<>();
      if (content instanceof Part) {
        children.add(collectPart((Part) content, state, path + ".message"));
      }
      return new PartTree(mailPart, children);
    }
    if (content instanceof String) {
      return collectTextPart(part, state, path, false, content);
    }
    if (content instanceof Part) {
      MailPart mailPart = recordPart(part, state, path);
      return new PartTree(mailPart, List.of(collectPart((Part) content, state, path + ".message")));
    }
    return new PartTree(recordPart(part, state, path), List.of());
  }

  private List<PartTree> collectMultipart(
      Multipart multipart, State state, String path, Part owner) {
    boolean related = matchesMimeType(owner, "multipart/related");
    boolean digest = matchesMimeType(owner, "multipart/digest");
    String rootContentId = related ? startContentId(owner) : null;
    List<PartTree> children = new ArrayList<>();
    int count;
    try {
      count = multipart.getCount();
    } catch (Exception e) {
      count = 0;
    }
    List<Part> parts = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      try {
        Part part = multipart.getBodyPart(index);
        parts.add(digest ? withDigestDefault(part) : part);
        paths.add(path + "." + (index + 1));
      } catch (Exception e) {
        // A part that cannot be read is left out, so that the rest of a broken mail is still shown.
      }
    }
    // The root of a multipart/related is its body, also when marked as an attachment (RFC 2387).
    int root = related ? rootIndex(parts, rootContentId) : -1;
    FoundBodies bodies = state.takeBodies();
    List<FoundBodies> found = new ArrayList<>();
    for (int i = 0; i < parts.size(); i++) {
      children.add(collectPart(parts.get(i), state, paths.get(i), i == root));
      found.add(state.takeBodies());
    }
    if (root >= 0) {
      bodies.fillFrom(found.get(root));
    }
    for (int i = 0; i < found.size(); i++) {
      if (i == root) {
        continue;
      }
      FoundBodies other = found.get(i);
      if (related) {
        other.otherTextBody = null;
      }
      bodies.fillFrom(other);
    }
    state.bodies = bodies;
    return children;
  }

  // A part of a multipart/digest without a type is a mail (RFC 2046), which Jakarta Mail would read
  // as text.
  private static Part withDigestDefault(Part part) {
    if (!(part instanceof MimeBodyPart)) {
      return part;
    }
    MimeBodyPart original = (MimeBodyPart) part;
    try {
      if (original.getHeader("Content-Type") != null) {
        return part;
      }
      InternetHeaders headers = new InternetHeaders();
      Enumeration<Header> all = original.getAllHeaders();
      while (all.hasMoreElements()) {
        Header header = all.nextElement();
        headers.addHeader(header.getName(), header.getValue());
      }
      headers.setHeader("Content-Type", "message/rfc822");
      byte[] content;
      try (InputStream raw = original.getRawInputStream()) {
        content = raw.readAllBytes();
      }
      return new MimeBodyPart(headers, content);
    } catch (MessagingException | IOException e) {
      return part;
    }
  }

  private static int rootIndex(List<Part> parts, String rootContentId) {
    if (parts.isEmpty()) {
      return -1;
    }
    if (rootContentId != null) {
      for (int i = 0; i < parts.size(); i++) {
        if (rootContentId.equals(normalizedContentId(parts.get(i)))) {
          return i;
        }
      }
    }
    return 0;
  }

  private static boolean canBeBody(Part part) {
    return matchesMimeType(part, "text/*") || matchesMimeType(part, "multipart/*");
  }

  private static String startContentId(Part part) {
    try {
      String start = new ContentType(effectiveContentType(part)).getParameter("start");
      return start == null ? null : normalizeContentId(start);
    } catch (ParseException | RuntimeException e) {
      return null;
    }
  }

  private PartTree collectTextPart(
      Part part, State state, String path, boolean isHtml, Object content) {
    String text = bodyText(part, content);
    MailPart mailPart = recordPart(part, state, path);
    if (text != null) {
      if (isHtml) {
        if (state.bodies.htmlBody == null) {
          state.bodies.htmlBody = text;
          state.bodies.htmlPath = path;
        }
      } else if (state.bodies.textBody == null) {
        state.bodies.textBody = text;
      }
    }
    return new PartTree(mailPart, List.of());
  }

  private PartTree collectOtherTextPart(Part part, State state, String path, Object content) {
    MailPart mailPart = recordPart(part, state, path);
    if (state.bodies.otherTextBody == null) {
      state.bodies.otherTextBody =
          textContent(content, charsetParameter(effectiveContentType(part)));
    }
    return new PartTree(mailPart, List.of());
  }

  private MailPart recordPart(Part part, State state, String path) {
    String contentType = effectiveContentType(part);
    String disposition = normalizedDisposition(part);
    if (disposition == null) {
      String raw = safeDisposition(part);
      disposition = raw == null ? null : raw.trim();
    }
    String[] transferEncoding = safeHeader(part, "Content-Transfer-Encoding");
    MailPart mailPart =
        new MailPart(
            path,
            contentType,
            mediaType(contentType),
            charsetParameter(contentType),
            transferEncoding == null || transferEncoding.length == 0
                ? null
                : transferEncoding[0].trim(),
            disposition,
            decodedFileName(part),
            normalizedContentId(part),
            safeSize(part));
    state.parts.add(mailPart);
    return mailPart;
  }

  private PartTree collectAttachment(Part part, State state, String path) {
    MailPart mailPart = recordPart(part, state, path);
    String id = state.nextAttachmentId();
    if (state.decodes.test(id)) {
      state.attachments.add(readAttachment(part, id, path));
    }
    return new PartTree(mailPart, List.of());
  }

  private ParsedAttachment readAttachment(Part part, String attachmentId, String path) {
    String contentType = effectiveContentType(part);
    String contentId = normalizedContentId(part);
    byte[] bytes = null;
    String decodeError = null;
    try (InputStream input = part.getInputStream()) {
      bytes = input.readAllBytes();
    } catch (Exception e) {
      decodeError = attachmentDecodeErrorMessage(e);
    }
    AttachmentSummary summary =
        new AttachmentSummary(
            attachmentId,
            decodedFileName(part),
            contentType,
            bytes != null ? Long.valueOf(bytes.length) : safeSize(part),
            normalizedDisposition(part),
            contentId,
            contentId != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/"),
            decodeError,
            path);
    return new ParsedAttachment(summary, bytes);
  }

  private static String attachmentDecodeErrorMessage(Exception exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      return "Failed to decode attachment";
    }
    return "Failed to decode attachment: " + message.trim();
  }

  // Also a part that is neither a text, a multipart nor a mail, such as a PDF attached without a
  // name.
  private boolean isAttachmentCandidate(Part part) {
    if (matchesMimeType(part, "multipart/*")) {
      return isAttachedMultipart(part);
    }
    String disposition = safeDisposition(part);
    if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || decodedFileName(part) != null) {
      return true;
    }
    if (matchesMimeType(part, "text/*")) {
      return false;
    }
    if (matchesMimeType(part, "message/rfc822")) {
      return Part.INLINE.equalsIgnoreCase(disposition) || normalizedContentId(part) != null;
    }
    return true;
  }

  private static boolean isAttachedMultipart(Part part) {
    return !(part instanceof Message) && Part.ATTACHMENT.equalsIgnoreCase(safeDisposition(part));
  }

  private static String textContent(Object content) {
    return textContent(content, null);
  }

  // UTF-8 for a body without a known charset, which Jakarta Mail would read as US-ASCII.
  private static String bodyText(Part part, Object content) {
    String charset = charsetParameter(effectiveContentType(part));
    if (charset != null && content instanceof String) {
      return (String) content;
    }
    try (InputStream input = part.getInputStream()) {
      return new String(input.readAllBytes(), charsetOrUtf8(charset));
    } catch (Exception e) {
      return textContent(content);
    }
  }

  private static String textContent(Object content, String charset) {
    try {
      if (content instanceof String) {
        return (String) content;
      }
      if (content instanceof InputStream) {
        try (InputStream input = (InputStream) content) {
          return new String(input.readAllBytes(), charsetOrUtf8(charset));
        }
      }
    } catch (Exception e) {
    }
    return null;
  }

  static Charset charsetOrUtf8(String charset) {
    if (charset == null) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(MimeUtility.javaCharset(charset));
    } catch (RuntimeException e) {
      return StandardCharsets.UTF_8;
    }
  }

  private static Object safeContent(Part part) {
    try {
      return part.getContent();
    } catch (Exception e) {
      return null;
    }
  }

  private static String safeDisposition(Part part) {
    try {
      return part.getDisposition();
    } catch (Exception e) {
      return null;
    }
  }

  private static String[] safeHeader(Part part, String name) {
    try {
      return part.getHeader(name);
    } catch (Exception e) {
      return null;
    }
  }

  private static Long safeSize(Part part) {
    try {
      int size = part.getSize();
      return size >= 0 ? Long.valueOf(size) : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String normalizedContentId(Part part) {
    String[] header = safeHeader(part, "Content-ID");
    if (header == null || header.length == 0) {
      return null;
    }
    return normalizeContentId(header[0]);
  }

  static String normalizeContentId(String contentId) {
    String value = contentId.trim();
    if (value.startsWith("<")) {
      value = value.substring(1);
    }
    if (value.endsWith(">")) {
      value = value.substring(0, value.length() - 1);
    }
    value = value.trim();
    return value.isEmpty() ? null : value;
  }

  private static String normalizedDisposition(Part part) {
    String disposition = safeDisposition(part);
    if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
      return Part.ATTACHMENT;
    }
    if (Part.INLINE.equalsIgnoreCase(disposition)) {
      return Part.INLINE;
    }
    return null;
  }

  private static String decodedFileName(Part part) {
    String fileName;
    try {
      fileName = part.getFileName();
    } catch (Exception e) {
      return null;
    }
    if (fileName == null || fileName.isBlank()) {
      return null;
    }
    try {
      return MimeUtility.decodeText(fileName);
    } catch (Exception e) {
      return fileName;
    }
  }

  private static String effectiveContentType(Part part) {
    return FOLDING.matcher(foldedContentType(part)).replaceAll(" ");
  }

  private static String foldedContentType(Part part) {
    String headerContentType;
    try {
      headerContentType = part.getContentType();
    } catch (Exception e) {
      headerContentType = null;
    }
    if (headerContentType == null) {
      headerContentType = DEFAULT_CONTENT_TYPE;
    }
    String dataHandlerContentType;
    try {
      dataHandlerContentType = part.getDataHandler().getContentType();
    } catch (Exception e) {
      dataHandlerContentType = null;
    }
    if (dataHandlerContentType == null || dataHandlerContentType.isBlank()) {
      return headerContentType;
    }
    if (mediaType(headerContentType).equals(DEFAULT_CONTENT_TYPE)) {
      return dataHandlerContentType;
    }
    return headerContentType;
  }

  private static boolean matchesMimeType(Part part, String pattern) {
    String contentType = mediaType(effectiveContentType(part));
    String expected = pattern.toLowerCase(Locale.ROOT);
    if (expected.endsWith("/*")) {
      return contentType.startsWith(expected.substring(0, expected.length() - 1));
    }
    return contentType.equals(expected);
  }

  static String mediaType(String contentType) {
    int separator = contentType.indexOf(';');
    String mediaType = separator < 0 ? contentType : contentType.substring(0, separator);
    return mediaType.trim().toLowerCase(Locale.ROOT);
  }

  static String charsetParameter(String contentType) {
    try {
      String charset = new ContentType(contentType).getParameter("charset");
      return charset == null ? null : nonBlank(charset.trim());
    } catch (ParseException | RuntimeException e) {
    }
    Matcher matcher = CHARSET.matcher(contentType);
    if (!matcher.find()) {
      return null;
    }
    return nonBlank(matcher.group(1).trim());
  }

  private static String nonBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static final class Bodies {
    private final String textBody;
    private final String htmlBody;
    private final int attachmentCount;

    Bodies(String textBody, String htmlBody, int attachmentCount) {
      this.textBody = textBody;
      this.htmlBody = htmlBody;
      this.attachmentCount = attachmentCount;
    }

    String textBody() {
      return textBody;
    }

    String htmlBody() {
      return htmlBody;
    }

    int attachmentCount() {
      return attachmentCount;
    }
  }

  private static final class State {
    final Predicate<String> decodes;

    FoundBodies bodies = new FoundBodies();
    final List<MailPart> parts = new ArrayList<>();
    final List<ParsedAttachment> attachments = new ArrayList<>();
    private int attachmentSequence;

    State(Predicate<String> decodes) {
      this.decodes = decodes;
    }

    String nextAttachmentId() {
      attachmentSequence++;
      return String.valueOf(attachmentSequence);
    }

    int attachmentCount() {
      return attachmentSequence;
    }

    FoundBodies takeBodies() {
      FoundBodies found = bodies;
      bodies = new FoundBodies();
      return found;
    }
  }

  private static final class FoundBodies {
    String textBody;
    String otherTextBody;
    String htmlBody;

    String htmlPath;

    void fillFrom(FoundBodies other) {
      if (textBody == null) {
        textBody = other.textBody;
      }
      if (otherTextBody == null) {
        otherTextBody = other.otherTextBody;
      }
      if (htmlBody == null) {
        htmlBody = other.htmlBody;
        htmlPath = other.htmlPath;
      }
    }
  }
}
