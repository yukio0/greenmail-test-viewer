package io.github.yukio0.greenmailtestviewer.viewer;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the source of a mail for the Raw tab, exactly as its {@code .eml} file stores it. Only a
 * single text part that is not transfer-encoded is read in its own charset, others in UTF-8. Never
 * calls {@link MimeMessage#writeTo}, which may add headers.
 */
final class RawMimeSourceReader {

  private static final String DEFAULT_CONTENT_TYPE = "text/plain";

  private static final Set<String> ASCII_ENCODINGS =
      Set.of("base64", "quoted-printable", "uuencode", "x-uuencode", "x-uue");

  String readDisplaySource(MimeMessage message) {
    StringBuilder source = new StringBuilder();
    for (String headerLine : headerLines(message)) {
      source.append(headerLine).append("\r\n");
    }
    source.append("\r\n");
    source.append(new String(bodyBytes(message), bodyCharset(message)));
    return source.toString();
  }

  private static Charset bodyCharset(MimeMessage message) {
    String contentType = contentType(message);
    if (!MimeMessageParser.mediaType(contentType).startsWith("text/")
        || !isNotTransferEncoded(message)) {
      return StandardCharsets.UTF_8;
    }
    return MimeMessageParser.charsetOrUtf8(MimeMessageParser.charsetParameter(contentType));
  }

  private static boolean isNotTransferEncoded(MimeMessage message) {
    try {
      String encoding = message.getEncoding();
      return encoding == null
          || !ASCII_ENCODINGS.contains(encoding.trim().toLowerCase(Locale.ROOT));
    } catch (MessagingException | RuntimeException e) {
      return false;
    }
  }

  private static byte[] bodyBytes(MimeMessage message) {
    try (InputStream input = message.getRawInputStream()) {
      return input.readAllBytes();
    } catch (MessagingException | IOException | RuntimeException rawFailure) {
      try (InputStream input = message.getInputStream()) {
        return input.readAllBytes();
      } catch (MessagingException e) {
        return new byte[0];
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static List<String> headerLines(MimeMessage message) {
    try {
      return Collections.list(message.getAllHeaderLines());
    } catch (MessagingException e) {
      return List.of();
    }
  }

  private static String contentType(MimeMessage message) {
    try {
      String contentType = message.getContentType();
      return contentType == null ? DEFAULT_CONTENT_TYPE : contentType;
    } catch (Exception e) {
      return DEFAULT_CONTENT_TYPE;
    }
  }
}
