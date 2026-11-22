package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Points the {@code cid:} images of an HTML body to the attachments of the mail: images, their
 * {@code srcset}, {@code background} attributes and the {@code url()} of styles. Reports which
 * Content-IDs were found, which were not, and which images are not shown.
 */
final class CidImageSourceRewriter {

  private static final String CID_PREFIX = "cid:";
  private static final Document.OutputSettings OUTPUT_SETTINGS =
      new Document.OutputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.html);

  private static final Pattern HEAD_ELEMENT = Pattern.compile("(?i)\\s*<head[\\s>/]");

  private static final Pattern CSS_CID_URL =
      Pattern.compile("(?i)url\\(\\s*(['\"]?)\\s*(cid:[^'\")\\s]+)\\s*\\1\\s*\\)");

  String rewrite(String html, String messageId, List<AttachmentSummary> attachments) {
    return rewriteWithReport(html, messageId, attachments).html();
  }

  Result rewriteWithReport(String html, String messageId, List<AttachmentSummary> attachments) {
    return rewriteWithReport(
        html, attachments, attachment -> ApiPaths.inlineAttachment(messageId, attachment.id()));
  }

  Result rewriteWithReport(
      String html,
      List<AttachmentSummary> attachments,
      Function<AttachmentSummary, String> imageSource) {
    return rewriteWithReport(html, null, attachments, imageSource);
  }

  Result rewriteWithReport(
      String html,
      String htmlPath,
      List<AttachmentSummary> attachments,
      Function<AttachmentSummary, String> imageSource) {
    Images images = new Images(attachments, htmlPath, imageSource);
    boolean withHead = HEAD_ELEMENT.matcher(html).lookingAt();
    Document document = parse(html, withHead);
    document.outputSettings(OUTPUT_SETTINGS);
    boolean changed = false;
    for (Element image : document.select("img[src]")) {
      changed |= rewriteAttribute(image, "src", images);
    }
    for (Element element : document.select("[background]")) {
      changed |= rewriteAttribute(element, "background", images);
    }
    for (Element element : document.select("[srcset]")) {
      List<SrcSet.Candidate> candidates = new ArrayList<>();
      boolean hasCid = false;
      for (SrcSet.Candidate candidate : SrcSet.parse(element.attr("srcset"))) {
        String contentId = cidContentId(candidate.url());
        if (contentId == null) {
          candidates.add(candidate);
          continue;
        }
        hasCid = true;
        String source = images.sourceOf(contentId);
        if (source == null) {
          continue;
        }
        candidates.add(candidate.withUrl(source));
      }
      if (hasCid) {
        changed = true;
        if (candidates.isEmpty()) {
          element.removeAttr("srcset");
        } else {
          element.attr("srcset", SrcSet.format(candidates));
        }
      }
    }
    for (Element element : document.select("[style]")) {
      String style = element.attr("style");
      String rewritten = rewriteCss(style, images);
      if (!rewritten.equals(style)) {
        element.attr("style", rewritten);
        changed = true;
      }
    }
    for (Element style : document.select("style")) {
      for (DataNode data : style.dataNodes()) {
        String css = data.getWholeData();
        String rewritten = rewriteCss(css, images);
        if (!rewritten.equals(css)) {
          data.setWholeData(rewritten);
          changed = true;
        }
      }
    }
    return new Result(
        changed ? html(document, withHead) : html,
        images.resolvedCount,
        new ArrayList<>(images.resolved),
        new ArrayList<>(images.missing),
        new ArrayList<>(images.notShown.values()));
  }

  static final String NOT_SHOWN_UNDECODABLE = "undecodable";

  static String notShownReason(AttachmentSummary attachment) {
    if (attachment.decodeError() != null) {
      return NOT_SHOWN_UNDECODABLE;
    }
    Long size = attachment.size();
    return AttachmentPreviewSupport.imageUnavailableReason(
        attachment.contentType(), size == null ? 0 : size);
  }

  static boolean canBeShown(AttachmentSummary attachment) {
    return notShownReason(attachment) == null;
  }

  // Parsed as a document when it has a head, so that the attributes of the body are kept; otherwise
  // as a fragment, so that the styles at its start are not moved into a head.
  private static Document parse(String html, boolean withHead) {
    return withHead ? Jsoup.parse(html) : Jsoup.parseBodyFragment(html);
  }

  private static String html(Document document, boolean withHead) {
    return withHead
        ? document.head().outerHtml() + document.body().outerHtml()
        : document.body().html();
  }

  private static boolean rewriteAttribute(Element element, String name, Images images) {
    String contentId = cidContentId(element.attr(name));
    if (contentId == null) {
      return false;
    }
    String source = images.sourceOf(contentId);
    if (source == null) {
      return false;
    }
    element.attr(name, source);
    return true;
  }

  private static String rewriteCss(String css, Images images) {
    if (!css.toLowerCase(Locale.ROOT).contains(CID_PREFIX)) {
      return css;
    }
    Matcher matcher = CSS_CID_URL.matcher(css);
    StringBuilder rewritten = new StringBuilder();
    while (matcher.find()) {
      String contentId = cidContentId(matcher.group(2));
      String source = contentId == null ? null : images.sourceOf(contentId);
      matcher.appendReplacement(
          rewritten,
          Matcher.quoteReplacement(source == null ? matcher.group() : "url(\"" + source + "\")"));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  private static String cidContentId(String source) {
    String value = source.trim();
    if (!value.regionMatches(true, 0, CID_PREFIX, 0, CID_PREFIX.length())) {
      return null;
    }
    return matchingKey(UrlPaths.decodeSegment(value.substring(CID_PREFIX.length())));
  }

  private static String matchingKey(String contentId) {
    if (contentId == null) {
      return null;
    }
    String normalized = MimeMessageParser.normalizeContentId(contentId);
    return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
  }

  private static final class Images {
    private final Map<String, AttachmentSummary> byContentId = new HashMap<>();
    private final Function<AttachmentSummary, String> imageSource;
    final LinkedHashSet<String> resolved = new LinkedHashSet<>();
    final LinkedHashSet<String> missing = new LinkedHashSet<>();
    final LinkedHashMap<String, NotShownImage> notShown = new LinkedHashMap<>();
    int resolvedCount;

    // Among the images with the same Content-ID (which only alternatives of one image may share),
    // picks the one that can be shown and is nearest to the HTML body, or else the first.
    Images(
        List<AttachmentSummary> attachments,
        String htmlPath,
        Function<AttachmentSummary, String> imageSource) {
      this.imageSource = imageSource;
      Map<String, Integer> nearness = new HashMap<>();
      for (AttachmentSummary attachment : attachments) {
        String contentId = matchingKey(attachment.contentId());
        if (contentId == null) {
          continue;
        }
        int near = sharedSegments(htmlPath, attachment.partPath());
        AttachmentSummary known = byContentId.get(contentId);
        boolean shown = canBeShown(attachment);
        if (known == null
            || (shown && !canBeShown(known))
            || (shown == canBeShown(known) && near > nearness.get(contentId))) {
          byContentId.put(contentId, attachment);
          nearness.put(contentId, near);
        }
      }
    }

    private static int sharedSegments(String a, String b) {
      if (a == null || b == null) {
        return 0;
      }
      String[] first = a.split("\\.");
      String[] second = b.split("\\.");
      int shared = 0;
      while (shared < first.length
          && shared < second.length
          && first[shared].equals(second[shared])) {
        shared++;
      }
      return shared;
    }

    String sourceOf(String contentId) {
      AttachmentSummary attachment = byContentId.get(contentId);
      if (attachment == null) {
        missing.add(contentId);
        return null;
      }
      if (!canBeShown(attachment)) {
        notShown.putIfAbsent(contentId, new NotShownImage(contentId, attachment));
        return null;
      }
      resolved.add(contentId);
      resolvedCount++;
      return imageSource.apply(attachment);
    }
  }

  static final class NotShownImage {
    private final String contentId;
    private final AttachmentSummary attachment;

    NotShownImage(String contentId, AttachmentSummary attachment) {
      this.contentId = contentId;
      this.attachment = attachment;
    }

    String contentId() {
      return contentId;
    }

    AttachmentSummary attachment() {
      return attachment;
    }

    String reason() {
      return notShownReason(attachment);
    }
  }

  static final class Result {
    private final String html;
    private final int resolvedCount;
    private final List<String> resolvedContentIds;
    private final List<String> missingContentIds;
    private final List<NotShownImage> notShownImages;

    Result(
        String html,
        int resolvedCount,
        List<String> resolvedContentIds,
        List<String> missingContentIds,
        List<NotShownImage> notShownImages) {
      this.html = html;
      this.resolvedCount = resolvedCount;
      this.resolvedContentIds = List.copyOf(resolvedContentIds);
      this.missingContentIds = List.copyOf(missingContentIds);
      this.notShownImages = List.copyOf(notShownImages);
    }

    String html() {
      return html;
    }

    int resolvedCount() {
      return resolvedCount;
    }

    List<String> resolvedContentIds() {
      return resolvedContentIds;
    }

    List<String> missingContentIds() {
      return missingContentIds;
    }

    List<NotShownImage> notShownImages() {
      return notShownImages;
    }
  }
}
