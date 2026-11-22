package io.github.yukio0.greenmailtestviewer.viewer;

import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an attachment can be shown as an image or as text, for both the detail of a mail
 * and the API that serves it, so that the UI offers only what the API serves.
 */
final class AttachmentPreviewSupport {

  static final long TEXT_PREVIEW_MAX_BYTES = 512L * 1024L;

  static final String KIND_IMAGE = "image";
  static final String KIND_TEXT = "text";
  static final String KIND_NONE = "none";

  static final String UNAVAILABLE_TOO_LARGE = "too-large";
  static final String UNAVAILABLE_UNSUPPORTED = "unsupported";
  static final String UNAVAILABLE_EMPTY = "empty";

  // Image types that cannot run anything. SVG can contain scripts, so it is only downloaded.
  private static final Set<String> INLINE_IMAGE_TYPES =
      Set.of(
          "image/apng",
          "image/avif",
          "image/bmp",
          "image/gif",
          "image/jpeg",
          "image/png",
          "image/webp",
          "image/x-icon",
          "image/vnd.microsoft.icon");
  private static final Set<String> IMAGE_FILE_EXTENSIONS =
      Set.of(".apng", ".avif", ".gif", ".jpg", ".jpeg", ".png", ".svg", ".webp");
  private static final Set<String> TEXT_FILE_EXTENSIONS =
      Set.of(
          ".csv", ".css", ".html", ".htm", ".json", ".log", ".md", ".text", ".txt", ".tsv", ".xml",
          ".yaml", ".yml");
  private static final Set<String> TEXT_MEDIA_TYPES =
      Set.of(
          "application/csv",
          "application/javascript",
          "application/json",
          "application/ld+json",
          "application/rtf",
          "application/x-javascript",
          "application/xml");

  private AttachmentPreviewSupport() {}

  static String previewKind(String contentType, String fileName) {
    String mediaType = MimeMessageParser.mediaType(contentType);
    String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    if (mediaType.startsWith("image/") || endsWithAny(name, IMAGE_FILE_EXTENSIONS)) {
      return KIND_IMAGE;
    }
    if (mediaType.startsWith("text/")
        || TEXT_MEDIA_TYPES.contains(mediaType)
        || endsWithAny(name, TEXT_FILE_EXTENSIONS)) {
      return KIND_TEXT;
    }
    return KIND_NONE;
  }

  static String unavailableReason(String contentType, String fileName, long size) {
    switch (previewKind(contentType, fileName)) {
      case KIND_IMAGE:
        return imageUnavailableReason(contentType, size);
      case KIND_TEXT:
        return textUnavailableReason(contentType, fileName, size);
      default:
        return UNAVAILABLE_UNSUPPORTED;
    }
  }

  static String imageUnavailableReason(String contentType, long size) {
    if (!isInlineImage(contentType)) {
      return UNAVAILABLE_UNSUPPORTED;
    }
    return size > 0 ? null : UNAVAILABLE_EMPTY;
  }

  static boolean isAvailable(String contentType, String fileName, long size) {
    return unavailableReason(contentType, fileName, size) == null;
  }

  static String textUnavailableReason(String contentType, String fileName, long size) {
    if (!KIND_TEXT.equals(previewKind(contentType, fileName))) {
      return UNAVAILABLE_UNSUPPORTED;
    }
    return size > TEXT_PREVIEW_MAX_BYTES ? UNAVAILABLE_TOO_LARGE : null;
  }

  static boolean isInlineImage(String contentType) {
    return INLINE_IMAGE_TYPES.contains(MimeMessageParser.mediaType(contentType));
  }

  static Charset previewCharset(String contentType) {
    return MimeMessageParser.charsetOrUtf8(MimeMessageParser.charsetParameter(contentType));
  }

  private static boolean endsWithAny(String name, Set<String> extensions) {
    for (String extension : extensions) {
      if (name.endsWith(extension)) {
        return true;
      }
    }
    return false;
  }
}
