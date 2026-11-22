package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.MimeMessageParser.Bodies;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailSummary;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import org.jsoup.Jsoup;

/** Reads {@code .eml} files. Not final, so that a test can make reading fail. */
class MailReader {

  static final int MAX_BODY_TEXT_LENGTH = 100_000;

  // UTF-8 headers are enabled by the system property that ViewerMain sets, not by the session
  // properties.
  private static final Session SESSION = Session.getInstance(new Properties());

  private final MimeMessageParser parser = new MimeMessageParser();
  private final MailAddressFormatter addressFormatter = new MailAddressFormatter();

  MimeMessage read(Path eml) throws IOException {
    return parse(Files.readAllBytes(eml), eml);
  }

  static MimeMessage parse(byte[] bytes, Path eml) throws IOException {
    try {
      return new MimeMessage(SESSION, new ByteArrayInputStream(bytes));
    } catch (MessagingException e) {
      throw new IOException("Could not read the email " + eml, e);
    }
  }

  MailSummary summarize(MimeMessage message) {
    Bodies bodies = parser.parseBodies(message);
    return new MailSummary(
        subject(message),
        from(message),
        recipients(message, RecipientType.TO),
        recipients(message, RecipientType.CC),
        sentAt(message),
        bodyText(bodies),
        otherHtmlText(bodies),
        bodies.attachmentCount());
  }

  static String subject(MimeMessage message) {
    try {
      return message.getSubject();
    } catch (MessagingException e) {
      return rawHeader(message, "Subject");
    }
  }

  String from(MimeMessage message) {
    try {
      return addressFormatter.format(message.getFrom());
    } catch (MessagingException e) {
      return rawHeader(message, "From");
    }
  }

  List<String> recipients(MimeMessage message, RecipientType type) {
    try {
      return addressFormatter.toList(message.getRecipients(type));
    } catch (MessagingException e) {
      try {
        String[] values = message.getHeader(type.toString());
        return values == null ? List.of() : new ArrayList<>(Arrays.asList(values));
      } catch (MessagingException again) {
        return List.of();
      }
    }
  }

  List<String> replyTo(MimeMessage message) {
    try {
      // getReplyTo() returns the sender when the header is missing.
      if (message.getHeader("Reply-To") == null) {
        return List.of();
      }
      return addressFormatter.toList(message.getReplyTo());
    } catch (MessagingException e) {
      return List.of();
    }
  }

  static String sentAt(MimeMessage message) {
    Date date;
    try {
      date = message.getSentDate();
    } catch (MessagingException e) {
      return null;
    }
    if (date == null) {
      return null;
    }
    return date.toInstant()
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private static String bodyText(Bodies bodies) {
    String text = bodies.textBody();
    if (text == null && bodies.htmlBody() != null) {
      text = Jsoup.parse(bodies.htmlBody()).text();
    }
    return collapsed(text);
  }

  private static String otherHtmlText(Bodies bodies) {
    if (bodies.textBody() == null || bodies.htmlBody() == null) {
      return null;
    }
    String html = collapsed(Jsoup.parse(bodies.htmlBody()).text());
    return html == null || html.equals(collapsed(bodies.textBody())) ? null : html;
  }

  private static String collapsed(String text) {
    if (text == null) {
      return null;
    }
    String collapsed = text.replaceAll("\\s+", " ").trim();
    if (collapsed.isEmpty()) {
      return null;
    }
    return collapsed.length() > MAX_BODY_TEXT_LENGTH
        ? collapsed.substring(0, MAX_BODY_TEXT_LENGTH)
        : collapsed;
  }

  private static String rawHeader(MimeMessage message, String name) {
    try {
      return message.getHeader(name, ", ");
    } catch (MessagingException e) {
      return null;
    }
  }
}
