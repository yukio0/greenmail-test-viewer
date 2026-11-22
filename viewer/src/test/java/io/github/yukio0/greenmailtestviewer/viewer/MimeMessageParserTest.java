package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.MailPart;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.ParsedAttachment;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.PartTree;
import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MimeMessageParserTest {

  private static final Session SESSION = Session.getInstance(new Properties());

  private final MimeMessageParser parser = new MimeMessageParser();

  @Test
  void parsesTextPlainBody() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setText("hello plain", "UTF-8");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("hello plain");
    assertThat(parsed.htmlBody()).isNull();
    assertThat(parsed.attachments()).isEmpty();
  }

  @Test
  void readsAPartOfADigestWithoutATypeAsAnAttachedMail() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: multipart/digest; boundary=\"digest\"\r\n\r\n"
                + "--digest\r\n"
                + "\r\n"
                + "From: a@example.com\r\n"
                + "Subject: Inner\r\n"
                + "Content-Type: multipart/mixed; boundary=\"inner\"\r\n\r\n"
                + "--inner\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<p>Inner HTML</p>\r\n"
                + "--inner\r\n"
                + "Content-Type: application/pdf\r\n"
                + "Content-Disposition: attachment; filename=\"terms.pdf\"\r\n\r\n"
                + "PDF\r\n"
                + "--inner--\r\n"
                + "--digest\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "A note of the digest\r\n"
                + "--digest--\r\n");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.htmlBody()).isEqualTo("<p>Inner HTML</p>");
    assertThat(parsed.attachments())
        .extracting(AttachmentSummary::fileName)
        .containsExactly("terms.pdf");
    assertThat(parsed.textBody()).isEqualTo("A note of the digest");
    List<PartTree> digestParts = parsed.partTree().children();
    assertThat(digestParts)
        .extracting(part -> part.part().mediaType())
        .containsExactly("message/rfc822", "text/plain");
    assertThat(parser.parseBodies(message).htmlBody()).isEqualTo("<p>Inner HTML</p>");
  }

  @Test
  void parsesTextHtmlBody() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent("<p>hello html</p>", "text/html; charset=UTF-8");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody()).isNull();
    assertThat(parsed.htmlBody()).isEqualTo("<p>hello html</p>");
    assertThat(parsed.attachments()).isEmpty();
  }

  @Test
  void parsesMultipartAlternativeTextAndHtmlBodies() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart("alternative", textPart("hello plain"), htmlPart("<p>hello html</p>")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("hello plain");
    assertThat(parsed.htmlBody()).isEqualTo("<p>hello html</p>");
    assertThat(parsed.attachments()).isEmpty();
  }

  @Test
  void keepsAnAttachedMultipartAsOneAttachmentRatherThanTheBody() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: multipart/mixed; boundary=\"outer\"\r\n"
                + "\r\n"
                + "--outer\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<p>main body</p>\r\n"
                + "--outer\r\n"
                + "Content-Type: multipart/alternative; boundary=\"inner\"\r\n"
                + "Content-Disposition: attachment\r\n"
                + "\r\n"
                + "--inner\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "attached text\r\n"
                + "--inner\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<p>attached html</p>\r\n"
                + "--inner--\r\n"
                + "--outer--\r\n");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.htmlBody()).contains("main body").doesNotContain("attached html");
    assertThat(String.valueOf(parsed.textBody())).doesNotContain("attached text");
    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).contentType()).startsWith("multipart/alternative");
    assertThat(parsed.attachments().get(0).disposition()).isEqualTo(Part.ATTACHMENT);
  }

  @Test
  void parsesMultipartMixedBodyAndAttachmentSummary() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed", textPart("hello mixed"), attachmentPart("sample.txt", "attachment content")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("hello mixed");
    assertThat(parsed.attachments()).hasSize(1);
    AttachmentSummary attachment = parsed.attachments().get(0);
    assertThat(attachment.id()).isEqualTo("1");
    assertThat(attachment.fileName()).isEqualTo("sample.txt");
    assertThat(attachment.contentType()).startsWith("text/plain");
    assertThat(attachment.disposition()).isEqualTo(Part.ATTACHMENT);
  }

  @Test
  void readsOtherTextTypesAsTheTextBody() throws MessagingException {
    for (String type : List.of("text/csv", "text/markdown", "text/calendar")) {
      MimeMessage message =
          rawMessage("Content-Type: " + type + "; charset=UTF-8\r\n\r\nitem,price\r\npen,100\r\n");

      assertThat(parser.parse(message).textBody()).as(type).contains("item,price");
    }
  }

  @Test
  void readsOtherTextTypesInTheirCharset() throws MessagingException {
    byte[] body = "見積書\r\n".getBytes(Charset.forName("Shift_JIS"));
    MimeMessage message =
        rawMessage(
            "Content-Type: text/csv; charset=Shift_JIS\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(body)
                + "\r\n");

    assertThat(parser.parse(message).textBody()).contains("見積書");
  }

  @Test
  void prefersPlainTextToOtherTextTypes() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: multipart/alternative; boundary=\"part\"\r\n\r\n"
                + "--part\r\n"
                + "Content-Type: text/calendar; charset=UTF-8\r\n\r\n"
                + "BEGIN:VCALENDAR\r\n"
                + "--part\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "See you on Monday.\r\n"
                + "--part--\r\n");

    assertThat(parser.parse(message).textBody())
        .contains("See you on Monday.")
        .doesNotContain("VCALENDAR");
  }

  @Test
  void keepsTheContentOfTheAttachmentsItHasRead() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed", textPart("hello mixed"), attachmentPart("sample.txt", "attachment content")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachmentContents()).hasSize(1);
    ParsedAttachment attachment = parsed.attachmentContents().get(0);
    assertThat(attachment.summary()).isSameAs(parsed.attachments().get(0));
    assertThat(new String(attachment.bytes(), StandardCharsets.UTF_8))
        .isEqualTo("attachment content");
  }

  @Test
  void findsAttachmentById() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed", textPart("hello mixed"), attachmentPart("sample.txt", "attachment content")));

    ParsedAttachment attachment = parser.findAttachment(message, "1");

    assertThat(attachment).isNotNull();
    assertThat(attachment.summary().fileName()).isEqualTo("sample.txt");
    assertThat(new String(attachment.bytes(), StandardCharsets.UTF_8))
        .isEqualTo("attachment content");
  }

  @Test
  void parsesAttachmentWithoutFileNameWhenDispositionIsAttachment() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart("mixed", textPart("body"), attachmentPart(null, "anonymous attachment")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    AttachmentSummary attachment = parsed.attachments().get(0);
    assertThat(attachment.id()).isEqualTo("1");
    assertThat(attachment.fileName()).isNull();
    assertThat(attachment.size()).isEqualTo(20L);
    assertThat(attachment.disposition()).isEqualTo(Part.ATTACHMENT);
  }

  @Test
  void parsesAttachmentWithEmptyFileNameAsUnnamedAttachment() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart("mixed", textPart("body"), attachmentPart("", "empty name")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).id()).isEqualTo("1");
    assertThat(parsed.attachments().get(0).fileName()).isNull();
  }

  @Test
  void parsesJapaneseAttachmentFileName() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart("mixed", textPart("body"), attachmentPart("日本語.txt", "こんにちは")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("日本語.txt");
  }

  @Test
  void decodesMimeEncodedAttachmentFileName()
      throws MessagingException, UnsupportedEncodingException {
    MimeMessage message = baseMessage();
    String encodedFileName = MimeUtility.encodeText("日本語.txt", "UTF-8", "B");
    MimeBodyPart attachment = part("encoded name", "text/plain; charset=UTF-8");
    attachment.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
    message.setContent(new MimeMultipart("mixed", textPart("body"), attachment));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("日本語.txt");
  }

  @Test
  void keepsStableIdsWhenMultipleAttachmentsHaveTheSameFileName() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed",
            textPart("body"),
            attachmentPart("same.txt", "first"),
            attachmentPart("same.txt", "second")));

    ParsedMail parsed = parser.parse(message);
    ParsedAttachment second = parser.findAttachment(message, "2");

    assertThat(parsed.attachments()).extracting(AttachmentSummary::id).containsExactly("1", "2");
    assertThat(parsed.attachments())
        .extracting(AttachmentSummary::fileName)
        .containsExactly("same.txt", "same.txt");
    assertThat(second).isNotNull();
    assertThat(new String(second.bytes(), StandardCharsets.UTF_8)).isEqualTo("second");
  }

  @Test
  void decodesOnlyTheAttachmentThatIsAskedFor() throws MessagingException {
    List<String> read = new ArrayList<>();
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed",
            textPart("body"),
            watchedAttachment("first.txt", "first", read),
            watchedAttachment("second.txt", "second", read)));

    ParsedAttachment second = parser.findAttachment(message, "2");

    assertThat(second.summary().fileName()).isEqualTo("second.txt");
    assertThat(new String(second.bytes(), StandardCharsets.UTF_8)).isEqualTo("second");
    assertThat(read).containsOnly("second.txt");
  }

  @Test
  void theMailListCountsTheAttachmentsWithoutDecodingThem() throws MessagingException {
    List<String> read = new ArrayList<>();
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed",
            textPart("body"),
            watchedAttachment("first.txt", "first", read),
            watchedAttachment("second.txt", "second", read)));

    assertThat(new MailReader().summarize(message).attachmentCount()).isEqualTo(2);
    assertThat(read).isEmpty();
    assertThat(parser.parse(message).attachmentContents())
        .extracting(attachment -> new String(attachment.bytes(), StandardCharsets.UTF_8))
        .containsExactly("first", "second");
  }

  @Test
  void listsFilesWithoutANameAsAttachments() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed",
            textPart("See the invoice."),
            part("%PDF-1.4", "application/pdf"),
            imagePart(null, null, null),
            attachmentPart("terms.txt", "Terms")));
    message.saveChanges();

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody()).isEqualTo("See the invoice.");
    assertThat(parsed.attachments())
        .extracting(
            AttachmentSummary::id,
            AttachmentSummary::fileName,
            attachment -> MimeMessageParser.mediaType(attachment.contentType()))
        .containsExactly(
            tuple("1", null, "application/pdf"),
            tuple("2", null, "image/png"),
            tuple("3", "terms.txt", "text/plain"));
    assertThat(new MailReader().summarize(message).attachmentCount()).isEqualTo(3);
    assertThat(new String(parser.findAttachment(message, "1").bytes(), StandardCharsets.UTF_8))
        .isEqualTo("%PDF-1.4");
  }

  @Test
  void aMailThatIsOnlyAFileHasItAsItsAttachment() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: application/pdf\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + Base64.getEncoder().encodeToString("%PDF-1.4".getBytes(StandardCharsets.UTF_8))
                + "\r\n");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody()).isNull();
    assertThat(parsed.htmlBody()).isNull();
    assertThat(parsed.attachments())
        .extracting(AttachmentSummary::id, AttachmentSummary::fileName)
        .containsExactly(tuple("1", null));
    assertThat(new String(parser.findAttachment(message, "1").bytes(), StandardCharsets.UTF_8))
        .isEqualTo("%PDF-1.4");
  }

  @Test
  void parsesAttachmentWithBrokenContentTypeWithoutThrowing() throws MessagingException {
    MimeMessage message = baseMessage();
    MimeBodyPart attachment = attachmentPart("bad.txt", "broken content type");
    attachment.setHeader("Content-Type", "text/@@@; name=\"bad.txt\"");
    message.setContent(new MimeMultipart("mixed", textPart("body"), attachment));

    ParsedMail parsed = parser.parse(message);
    ParsedAttachment found = parser.findAttachment(message, "1");

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("bad.txt");
    assertThat(parsed.attachments().get(0).contentType()).startsWith("text/@@@");
    assertThat(found).isNotNull();
    assertThat(new String(found.bytes(), StandardCharsets.UTF_8)).isEqualTo("broken content type");
  }

  @Test
  void keepsBrokenBase64AttachmentAsDecodeErrorWithoutThrowing() throws MessagingException {
    MimeMessage message = rawMessageWithBrokenBase64Attachment(null);

    ParsedMail parsed = parser.parse(message);
    ParsedAttachment attachment = parser.findAttachment(message, "1");

    assertThat(parsed.textBody().trim()).isEqualTo("body");
    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("broken.png");
    assertThat(parsed.attachments().get(0).decodeError()).startsWith("Failed to decode attachment");
    assertThat(attachment).isNotNull();
    assertThat(attachment.bytes()).isNull();
  }

  @Test
  void doesNotThrowWhenContentDispositionIsMalformed() throws MessagingException {
    MimeMessage message = baseMessage();
    MimeBodyPart attachment = part("broken disposition", "text/plain; charset=UTF-8");
    attachment.setHeader("Content-Type", "text/plain; name=\"broken.txt\"");
    attachment.setHeader("Content-Disposition", "attachment; filename=\"broken.txt");
    message.setContent(new MimeMultipart("mixed", textPart("body"), attachment));

    assertThatCode(() -> parser.parse(message)).doesNotThrowAnyException();
    assertThat(parser.parse(message).textBody().trim()).isEqualTo("body");
  }

  @Test
  void parsesMultipartRelatedHtmlAndInlineImageAttachment() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "related",
            htmlPart("<p>Hello <img src=\"cid:logo\"></p>"),
            imagePart("logo.png", "logo", Part.INLINE)));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.htmlBody()).isEqualTo("<p>Hello <img src=\"cid:logo\"></p>");
    assertThat(parsed.attachments()).hasSize(1);
    AttachmentSummary image = parsed.attachments().get(0);
    assertThat(image.fileName()).isEqualTo("logo.png");
    assertThat(image.contentType()).startsWith("image/png");
    assertThat(image.disposition()).isEqualTo(Part.INLINE);
    assertThat(image.contentId()).isEqualTo("logo");
    assertThat(image.inlineImage()).isTrue();
  }

  @Test
  void takesTheBodyFromTheRootThatTheStartParameterNames() throws MessagingException {
    MimeMessage message = relatedMail("; start=\"<root@example.com>\"");

    ParsedMail parsed = parser.parse(message);
    MimeMessageParser.Bodies bodies = parser.parseBodies(message);

    assertThat(parsed.htmlBody()).isEqualTo("<p>The root</p>");
    assertThat(bodies.htmlBody()).isEqualTo("<p>The root</p>");
    assertThat(parsed.parts())
        .extracting(MailPart::id)
        .containsExactly("message", "message.1", "message.2", "message.3");
    assertThat(parsed.attachments()).extracting(AttachmentSummary::id).containsExactly("1");
    assertThat(parsed.attachments().get(0).contentId()).isEqualTo("logo@example.com");
  }

  @Test
  void takesTheBodyFromTheRootAlsoWhenItIsMarkedAsAnAttachment() throws MessagingException {
    for (String rootHeaders :
        List.of(
            "Content-Disposition: attachment; filename=\"page.html\"\r\n",
            "Content-Disposition: inline; filename=\"page.html\"\r\n")) {
      MimeMessage message = relatedMail("; start=\"<root@example.com>\"", rootHeaders);

      ParsedMail parsed = parser.parse(message);

      assertThat(parsed.htmlBody()).as(rootHeaders).isEqualTo("<p>The root</p>");
      assertThat(parser.parseBodies(message).htmlBody())
          .as(rootHeaders)
          .isEqualTo("<p>The root</p>");
      assertThat(new MailReader().summarize(message).bodyText())
          .as(rootHeaders)
          .isEqualTo("The root");
      assertThat(parsed.attachments())
          .as(rootHeaders)
          .extracting(AttachmentSummary::contentId)
          .containsExactly("logo@example.com");
    }
  }

  @Test
  void takesTheBodyFromTheFirstPartWithoutAStartThatNamesAPart() throws MessagingException {
    for (String start : List.of("", "; start=\"<missing@example.com>\"")) {
      MimeMessage message = relatedMail(start);

      assertThat(parser.parse(message).htmlBody()).as(start).isEqualTo("<p>A fragment</p>");
      assertThat(parser.parseBodies(message).htmlBody()).as(start).isEqualTo("<p>A fragment</p>");
    }
  }

  @Test
  void doesNotTakeATextThatTheRootUsesForTheTextBody() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: multipart/related; boundary=\"part\"; type=\"text/html\"\r\n"
                + "\r\n"
                + "--part\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<link rel=\"stylesheet\" href=\"cid:style\"><p>Hello</p>\r\n"
                + "--part\r\n"
                + "Content-Type: text/css; charset=UTF-8\r\n"
                + "Content-ID: <style>\r\n"
                + "\r\n"
                + "p { color: red; }\r\n"
                + "--part--\r\n");

    ParsedMail parsed = parser.parse(message);
    MimeMessageParser.Bodies bodies = parser.parseBodies(message);

    assertThat(parsed.textBody()).isNull();
    assertThat(bodies.textBody()).isNull();
    assertThat(parsed.htmlBody()).contains("<p>Hello</p>");
    assertThat(parsed.parts()).extracting(MailPart::mediaType).contains("text/css");
  }

  @Test
  void takesThePlainTextAndHtmlOfOtherPartsOfARelatedWhenTheRootHasNone()
      throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart("related", textPart("plain body"), htmlPart("<p>html body</p>")));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("plain body");
    assertThat(parsed.htmlBody()).isEqualTo("<p>html body</p>");
  }

  private static MimeMessage relatedMail(String startParameter) throws MessagingException {
    return relatedMail(startParameter, "");
  }

  private static MimeMessage relatedMail(String startParameter, String rootHeaders)
      throws MessagingException {
    return rawMessage(
        "Content-Type: multipart/related; boundary=\"part\"; type=\"text/html\""
            + startParameter
            + "\r\n"
            + "\r\n"
            + "--part\r\n"
            + "Content-Type: text/html; charset=UTF-8\r\n"
            + "Content-ID: <fragment@example.com>\r\n"
            + "\r\n"
            + "<p>A fragment</p>\r\n"
            + "--part\r\n"
            + "Content-Type: text/html; charset=UTF-8\r\n"
            + "Content-ID: <root@example.com>\r\n"
            + rootHeaders
            + "\r\n"
            + "<p>The root</p>\r\n"
            + "--part\r\n"
            + "Content-Type: image/png\r\n"
            + "Content-ID: <logo@example.com>\r\n"
            + "Content-Transfer-Encoding: base64\r\n"
            + "\r\n"
            + "iVBORw0KGgo=\r\n"
            + "--part--\r\n");
  }

  @Test
  void parsesInlineImageWithContentIdWithoutFileName() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "related",
            htmlPart("<p>Hello <img src=\"cid:logo\"></p>"),
            imagePart(null, "logo", Part.INLINE)));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    AttachmentSummary image = parsed.attachments().get(0);
    assertThat(image.fileName()).isNull();
    assertThat(image.contentId()).isEqualTo("logo");
    assertThat(image.disposition()).isEqualTo(Part.INLINE);
    assertThat(image.inlineImage()).isTrue();
  }

  @Test
  void marksCidImageAsInlineImageEvenWithoutInlineDisposition() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "related",
            htmlPart("<p>Hello <img src=\"cid:logo\"></p>"),
            imagePart("logo.png", "logo", null)));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).contentId()).isEqualTo("logo");
    assertThat(parsed.attachments().get(0).inlineImage()).isTrue();
  }

  @Test
  void doesNotMarkImageAttachmentWithoutContentIdAsInlineImage() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(
        new MimeMultipart(
            "mixed", textPart("body"), imagePart("photo.png", null, Part.ATTACHMENT)));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("photo.png");
    assertThat(parsed.attachments().get(0).inlineImage()).isFalse();
  }

  @Test
  void keepsBrokenBase64CidImageAsInlineImageDecodeError() throws MessagingException {
    MimeMessage message = rawMessageWithBrokenBase64Attachment("logo");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).contentId()).isEqualTo("logo");
    assertThat(parsed.attachments().get(0).inlineImage()).isTrue();
    assertThat(parsed.attachments().get(0).decodeError()).startsWith("Failed to decode attachment");
  }

  @Test
  void parsesNestedMultipartBodiesAndAttachments() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(nestedMixed("nested.txt"));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("plain body");
    assertThat(parsed.htmlBody()).isEqualTo("<p>html body</p>");
    assertThat(parsed.attachments()).hasSize(1);
    assertThat(parsed.attachments().get(0).fileName()).isEqualTo("nested.txt");
  }

  @Test
  void recordsSourceMetadataForEachMimePart() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(nestedMixed("sample.txt"));

    List<MailPart> parts = parser.parse(message).parts();

    assertThat(parts).anyMatch(part -> part.mediaType().startsWith("multipart/mixed"));
    assertThat(parts).anyMatch(part -> part.mediaType().startsWith("multipart/alternative"));
    assertThat(parts)
        .anyMatch(part ->
            part.mediaType().equals("text/plain") && "UTF-8".equalsIgnoreCase(part.charset()));
    assertThat(parts)
        .anyMatch(part ->
            part.mediaType().equals("text/html") && "UTF-8".equalsIgnoreCase(part.charset()));
    assertThat(parts)
        .anyMatch(part ->
            "sample.txt".equals(part.fileName()) && Part.ATTACHMENT.equals(part.disposition()));
  }

  @Test
  void buildsMimePartTreeForNestedMultipartMessage() throws MessagingException {
    MimeMessage message = baseMessage();
    message.setContent(nestedMixed("tree.txt"));

    PartTree tree = parser.parse(message).partTree();

    assertThat(tree.part().mediaType()).startsWith("multipart/mixed");
    assertThat(tree.children())
        .extracting(child -> child.part().id())
        .containsExactly("message.1", "message.2");
    assertThat(tree.children().get(0).part().mediaType()).startsWith("multipart/alternative");
    assertThat(tree.children().get(0).children())
        .extracting(child -> child.part().mediaType())
        .containsExactly("text/plain", "text/html");
    assertThat(tree.children().get(1).part().fileName()).isEqualTo("tree.txt");
    assertThat(tree.children().get(1).part().disposition()).isEqualTo(Part.ATTACHMENT);
  }

  @Test
  void parsesNestedMessageRfc822Body() throws MessagingException {
    MimeMessage nested = baseMessage();
    nested.setText("inner body", "UTF-8");
    MimeBodyPart nestedPart = new MimeBodyPart();
    nestedPart.setContent(nested, "message/rfc822");
    MimeMessage message = baseMessage();
    message.setContent(new MimeMultipart("mixed", nestedPart));

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.textBody().trim()).isEqualTo("inner body");
    assertThat(parsed.attachments()).isEmpty();
  }

  @Test
  void readsBodiesWithoutAKnownCharsetAsUtf8() throws MessagingException {
    String[][] cases = {
      {"text/plain", "8bit"},
      {"text/html", "8bit"},
      {"text/plain", "utf-8"},
      {"text/plain; charset=x-unknown", "8bit"},
    };
    for (String[] testCase : cases) {
      MimeMessage message =
          new MimeMessage(
              SESSION,
              new ByteArrayInputStream(
                  ("Subject: Quote\r\nContent-Type: "
                          + testCase[0]
                          + "\r\nContent-Transfer-Encoding: "
                          + testCase[1]
                          + "\r\n\r\n見積書をお送りします。\r\n")
                      .getBytes(StandardCharsets.UTF_8)));

      ParsedMail parsed = parser.parse(message);
      String body = testCase[0].startsWith("text/html") ? parsed.htmlBody() : parsed.textBody();

      assertThat(body).as(String.join(", ", testCase)).isEqualTo("見積書をお送りします。\r\n");
      assertThat(new MailReader().summarize(message).bodyText())
          .as(String.join(", ", testCase))
          .isEqualTo("見積書をお送りします。");
    }
  }

  @Test
  void readsACharsetWrittenAsRfc2231Has() throws MessagingException {
    byte[] head =
        ("Subject: Menu\r\n"
                + "Content-Type: text/plain; charset*=us-ascii''ISO-8859-1\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
    byte[] body = "Café menu".getBytes(StandardCharsets.ISO_8859_1);
    byte[] bytes = new byte[head.length + body.length];
    System.arraycopy(head, 0, bytes, 0, head.length);
    System.arraycopy(body, 0, bytes, head.length, body.length);
    MimeMessage message = new MimeMessage(SESSION, new ByteArrayInputStream(bytes));

    assertThat(parser.parse(message).textBody()).isEqualTo("Café menu");
    assertThat(new MailReader().summarize(message).bodyText()).isEqualTo("Café menu");
    assertThat(MimeMessageParser.charsetParameter("text/plain; charset*=us-ascii''ISO-8859-1"))
        .isEqualTo("ISO-8859-1");
  }

  @Test
  void tellsWhereTheHtmlBodyAndTheAttachmentsAre() throws MessagingException {
    MimeMessage message =
        rawMessage(
            "Content-Type: multipart/alternative; boundary=\"alternative\"\r\n"
                + "\r\n"
                + "--alternative\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Plain\r\n"
                + "--alternative\r\n"
                + "Content-Type: multipart/related; boundary=\"related\"\r\n"
                + "\r\n"
                + "--related\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "\r\n"
                + "<img src=\"cid:logo\">\r\n"
                + "--related\r\n"
                + "Content-Type: image/png\r\n"
                + "Content-ID: <logo>\r\n"
                + "\r\n"
                + "png\r\n"
                + "--related--\r\n"
                + "--alternative--\r\n");

    ParsedMail parsed = parser.parse(message);

    assertThat(parsed.htmlBodyPath()).isEqualTo("message.2.1");
    assertThat(parsed.attachments())
        .extracting(AttachmentSummary::partPath)
        .containsExactly("message.2.2");
  }

  @Test
  void readsBodiesInLegacyCharsets() throws MessagingException {
    String raw =
        "From: alice@example.com\r\n"
            + "Subject: sjis\r\n"
            + "Content-Type: text/plain; charset=Shift_JIS\r\n"
            + "\r\n";
    byte[] body = "これはShift_JISのメールです。".getBytes(Charset.forName("Shift_JIS"));
    byte[] bytes = new byte[raw.length() + body.length];
    System.arraycopy(raw.getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, raw.length());
    System.arraycopy(body, 0, bytes, raw.length(), body.length);

    ParsedMail parsed = parser.parse(new MimeMessage(SESSION, new ByteArrayInputStream(bytes)));

    assertThat(parsed.textBody()).isEqualTo("これはShift_JISのメールです。");
  }

  @Test
  void joinsContentTypesFoldedOverSeveralLines() throws MessagingException {
    String raw =
        "From: alice@example.com\r\n"
            + "Content-Type: multipart/mixed; boundary=\"b\"\r\n"
            + "\r\n"
            + "--b\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\n"
            + "body\r\n"
            + "--b\r\n"
            + "Content-Type: text/plain; charset=UTF-8;\r\n"
            + "\tname=\"notes.txt\"\r\n"
            + "Content-Disposition: attachment\r\n"
            + "\r\n"
            + "notes\r\n"
            + "--b--\r\n";

    ParsedMail parsed =
        parser.parse(
            new MimeMessage(
                SESSION, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));

    assertThat(parsed.attachments().get(0).contentType())
        .isEqualTo("text/plain; charset=UTF-8; name=\"notes.txt\"");
  }

  private static MimeMultipart nestedMixed(String fileName) throws MessagingException {
    MimeBodyPart alternative = new MimeBodyPart();
    alternative.setContent(
        new MimeMultipart("alternative", textPart("plain body"), htmlPart("<p>html body</p>")));
    return new MimeMultipart("mixed", alternative, attachmentPart(fileName, "attachment body"));
  }

  private static MimeMessage rawMessage(String mimeHeadersAndBody) throws MessagingException {
    String raw =
        "From: sender@example.com\r\n"
            + "To: receiver@example.com\r\n"
            + "Subject: Other text\r\n"
            + "MIME-Version: 1.0\r\n"
            + mimeHeadersAndBody;
    return new MimeMessage(
        SESSION, new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
  }

  private static MimeMessage baseMessage() throws MessagingException {
    MimeMessage message = new MimeMessage(SESSION);
    message.setFrom("alice@example.com");
    message.setRecipients(RecipientType.TO, "bob@example.com");
    message.setSubject("test");
    return message;
  }

  private static MimeBodyPart textPart(String text) throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setText(text, "UTF-8");
    return part;
  }

  private static MimeBodyPart htmlPart(String html) throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setContent(html, "text/html; charset=UTF-8");
    return part;
  }

  private static MimeBodyPart part(String content, String contentType) throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setDataHandler(
        new DataHandler(
            new ByteArrayDataSource(content.getBytes(StandardCharsets.UTF_8), contentType)));
    return part;
  }

  private static MimeBodyPart attachmentPart(String fileName, String content)
      throws MessagingException {
    MimeBodyPart part = part(content, "text/plain; charset=UTF-8");
    if (fileName != null) {
      part.setFileName(fileName);
    }
    part.setDisposition(Part.ATTACHMENT);
    return part;
  }

  private static MimeBodyPart watchedAttachment(String fileName, String content, List<String> read)
      throws MessagingException {
    MimeBodyPart part =
        new MimeBodyPart() {
          @Override
          public Object getContent() throws IOException, MessagingException {
            read.add(fileName);
            return super.getContent();
          }

          @Override
          public InputStream getInputStream() throws IOException, MessagingException {
            read.add(fileName);
            return super.getInputStream();
          }
        };
    part.setText(content, "UTF-8");
    part.setFileName(fileName);
    part.setDisposition(Part.ATTACHMENT);
    return part;
  }

  private static MimeBodyPart imagePart(String fileName, String contentId, String disposition)
      throws MessagingException {
    MimeBodyPart part = part("image bytes", "image/png");
    if (fileName != null) {
      part.setFileName(fileName);
    }
    if (disposition != null) {
      part.setDisposition(disposition);
    }
    if (contentId != null) {
      part.setHeader("Content-ID", "<" + contentId + ">");
    }
    return part;
  }

  private static MimeMessage rawMessageWithBrokenBase64Attachment(String contentId)
      throws MessagingException {
    List<String> lines =
        Arrays.asList(
            "From: alice@example.com",
            "To: bob@example.com",
            "Subject: broken attachment",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"broken-boundary\"",
            "",
            "--broken-boundary",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "body",
            "--broken-boundary",
            "Content-Type: image/png; name=\"broken.png\"",
            "Content-Disposition: attachment; filename=\"broken.png\"",
            contentId == null ? null : "Content-ID: <" + contentId + ">",
            "Content-Transfer-Encoding: base64",
            "",
            "=",
            "--broken-boundary--",
            "");
    String raw = lines.stream().filter(line -> line != null).collect(Collectors.joining("\r\n"));
    return new MimeMessage(SESSION, new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
  }
}
