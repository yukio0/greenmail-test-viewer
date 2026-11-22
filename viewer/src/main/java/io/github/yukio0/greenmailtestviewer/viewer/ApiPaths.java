package io.github.yukio0.greenmailtestviewer.viewer;

/** The API paths that responses link to. The UI takes them from the responses. */
final class ApiPaths {

  private ApiPaths() {}

  static String message(String messageId) {
    return "/api/messages/" + UrlPaths.encodeSegment(messageId);
  }

  static String raw(String messageId) {
    return message(messageId) + "/raw";
  }

  static String attachment(String messageId, String attachmentId) {
    return message(messageId) + "/attachments/" + UrlPaths.encodeSegment(attachmentId);
  }

  static String inlineAttachment(String messageId, String attachmentId) {
    return attachment(messageId, attachmentId) + "/inline";
  }

  static String attachmentText(String messageId, String attachmentId) {
    return attachment(messageId, attachmentId) + "/text";
  }
}
