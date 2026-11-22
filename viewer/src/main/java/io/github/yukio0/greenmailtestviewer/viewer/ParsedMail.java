package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.ArrayList;
import java.util.List;

/** The bodies, parts and attachments of a mail, as {@link MimeMessageParser} found them. */
final class ParsedMail {

  private final String textBody;
  private final String htmlBody;
  private final String htmlBodyPath;
  private final List<MailPart> parts;
  private final PartTree partTree;
  private final List<ParsedAttachment> attachmentContents;
  private final List<AttachmentSummary> attachments;

  ParsedMail(
      String textBody,
      String htmlBody,
      String htmlBodyPath,
      List<MailPart> parts,
      PartTree partTree,
      List<ParsedAttachment> attachmentContents) {
    this.textBody = textBody;
    this.htmlBody = htmlBody;
    this.htmlBodyPath = htmlBodyPath;
    this.parts = List.copyOf(parts);
    this.partTree = partTree;
    this.attachmentContents = List.copyOf(attachmentContents);
    List<AttachmentSummary> summaries = new ArrayList<>();
    for (ParsedAttachment attachment : attachmentContents) {
      summaries.add(attachment.summary());
    }
    this.attachments = List.copyOf(summaries);
  }

  String textBody() {
    return textBody;
  }

  String htmlBody() {
    return htmlBody;
  }

  String htmlBodyPath() {
    return htmlBodyPath;
  }

  List<MailPart> parts() {
    return parts;
  }

  PartTree partTree() {
    return partTree;
  }

  List<AttachmentSummary> attachments() {
    return attachments;
  }

  List<ParsedAttachment> attachmentContents() {
    return attachmentContents;
  }

  static final class MailPart {
    private final String id;
    private final String contentType;
    private final String mediaType;
    private final String charset;
    private final String transferEncoding;
    private final String disposition;
    private final String fileName;
    private final String contentId;
    private final Long size;

    MailPart(
        String id,
        String contentType,
        String mediaType,
        String charset,
        String transferEncoding,
        String disposition,
        String fileName,
        String contentId,
        Long size) {
      this.id = id;
      this.contentType = contentType;
      this.mediaType = mediaType;
      this.charset = charset;
      this.transferEncoding = transferEncoding;
      this.disposition = disposition;
      this.fileName = fileName;
      this.contentId = contentId;
      this.size = size;
    }

    String id() {
      return id;
    }

    String contentType() {
      return contentType;
    }

    String mediaType() {
      return mediaType;
    }

    String charset() {
      return charset;
    }

    String transferEncoding() {
      return transferEncoding;
    }

    String disposition() {
      return disposition;
    }

    String fileName() {
      return fileName;
    }

    String contentId() {
      return contentId;
    }

    Long size() {
      return size;
    }
  }

  static final class PartTree {
    private final MailPart part;
    private final List<PartTree> children;

    PartTree(MailPart part, List<PartTree> children) {
      this.part = part;
      this.children = List.copyOf(children);
    }

    MailPart part() {
      return part;
    }

    List<PartTree> children() {
      return children;
    }
  }

  static final class AttachmentSummary {
    private final String id;
    private final String fileName;
    private final String contentType;
    private final Long size;
    private final String disposition;
    private final String contentId;
    private final boolean inlineImage;
    private final String decodeError;
    private final String partPath;

    AttachmentSummary(
        String id,
        String fileName,
        String contentType,
        Long size,
        String disposition,
        String contentId,
        boolean inlineImage,
        String decodeError) {
      this(id, fileName, contentType, size, disposition, contentId, inlineImage, decodeError, null);
    }

    AttachmentSummary(
        String id,
        String fileName,
        String contentType,
        Long size,
        String disposition,
        String contentId,
        boolean inlineImage,
        String decodeError,
        String partPath) {
      this.id = id;
      this.fileName = fileName;
      this.contentType = contentType;
      this.size = size;
      this.disposition = disposition;
      this.contentId = contentId;
      this.inlineImage = inlineImage;
      this.decodeError = decodeError;
      this.partPath = partPath;
    }

    String id() {
      return id;
    }

    String fileName() {
      return fileName;
    }

    String contentType() {
      return contentType;
    }

    Long size() {
      return size;
    }

    String disposition() {
      return disposition;
    }

    String contentId() {
      return contentId;
    }

    boolean inlineImage() {
      return inlineImage;
    }

    String decodeError() {
      return decodeError;
    }

    String partPath() {
      return partPath;
    }
  }

  static final class ParsedAttachment {
    private final AttachmentSummary summary;
    private final byte[] bytes;

    ParsedAttachment(AttachmentSummary summary, byte[] bytes) {
      this.summary = summary;
      this.bytes = bytes;
    }

    AttachmentSummary summary() {
      return summary;
    }

    byte[] bytes() {
      return bytes;
    }
  }
}
