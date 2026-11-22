package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
class MailContentExampleTest {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void sendsHtmlMailWithTextAlternative() throws MessagingException {
    MimeMessage message = newMessage("grace@example.com", "Your monthly newsletter");
    MimeBodyPart text = new MimeBodyPart();
    text.setText("This month: new features and tips.", "UTF-8");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(
        "<h1>Monthly newsletter</h1><p>This month: <b>new features</b> and tips.</p>",
        "text/html; charset=UTF-8");
    message.setContent(new MimeMultipart("alternative", text, html));

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsJapaneseMail() throws MessagingException, UnsupportedEncodingException {
    MimeMessage message = newMessage("hanako@example.com", null);
    message.setFrom(new InternetAddress("no-reply@example.com", "サンプル商店", "UTF-8"));
    message.setSubject("ご注文ありがとうございます", "UTF-8");
    message.setText("山田花子 様\n\nご注文を承りました。発送まで今しばらくお待ちください。", "UTF-8");

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsMailWithAttachments() throws MessagingException, UnsupportedEncodingException {
    MimeMessage message = newMessage("ivan@example.com", "Quotation and price list");
    MimeBodyPart text = new MimeBodyPart();
    text.setText("Please find the attached files.", "UTF-8");
    MimeBodyPart csv = attachment("price-list.csv", "text/csv", "item,price\npen,100\n");
    MimeBodyPart japanese =
        attachment(MimeUtility.encodeText("見積書.txt", "UTF-8", "B"), "text/plain", "見積金額: 1,000円\n");
    message.setContent(new MimeMultipart(text, csv, japanese));

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsHtmlMailWithInlineImage() throws MessagingException, IOException {
    MimeMessage message = newMessage("judy@example.com", "Mail with an inline logo");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(
        "<p><img src=\"cid:logo@example.com\" alt=\"Logo\"> Welcome to our shop.</p>",
        "text/html; charset=UTF-8");
    MimeBodyPart image = new MimeBodyPart();
    try (InputStream logo = MailContentExampleTest.class.getResourceAsStream("logo.png")) {
      image.setDataHandler(new DataHandler(new ByteArrayDataSource(logo, "image/png")));
    }
    image.setContentID("<logo@example.com>");
    image.setDisposition(MimeBodyPart.INLINE);
    image.setFileName("logo.png");
    message.setContent(new MimeMultipart("related", html, image));

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsOneMailToSeveralRecipients() throws MessagingException {
    MimeMessage message = newMessage("team@example.com", "Weekly report");
    message.addRecipient(RecipientType.CC, new InternetAddress("manager@example.com"));
    message.addRecipient(RecipientType.BCC, new InternetAddress("audit@example.com"));
    message.setText("The weekly report is ready.", "UTF-8");

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(3);
  }

  @Test
  void sendsMailWithMarkupInIt() throws MessagingException {
    MimeMessage message =
        newMessage("mallory@example.com", "<img src=x onerror=alert(1)> Markup in the subject");
    MimeBodyPart text = new MimeBodyPart();
    text.setText("<script>alert('text')</script> Markup in the text body.", "UTF-8");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(
        "<h1 onclick=\"alert('click')\">Markup in the HTML body</h1>"
            + "<script>alert('html')</script>"
            + "<img src=x onerror=\"alert('image')\" alt=\"Broken image\">"
            + "<img src=\"https://tracker.example.com/open.png\" alt=\"Tracking image\">"
            + "<p><a href=\"javascript:alert('link')\">A link with a script</a></p>",
        "text/html; charset=UTF-8");
    MimeBodyPart attachment =
        attachment("<b>markup</b>.html", "text/html", "<script>alert('attachment')</script>");
    MimeBodyPart body = new MimeBodyPart();
    body.setContent(new MimeMultipart("alternative", text, html));
    message.setContent(new MimeMultipart(body, attachment));

    Transport.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  private static MimeMessage newMessage(String to, String subject) throws MessagingException {
    MimeMessage message =
        new MimeMessage(GreenMailUtil.getSession(greenMail.getSmtp().getServerSetup()));
    message.setFrom(new InternetAddress("no-reply@example.com"));
    message.setRecipient(RecipientType.TO, new InternetAddress(to));
    if (subject != null) {
      message.setSubject(subject, "UTF-8");
    }
    return message;
  }

  private static MimeBodyPart attachment(String fileName, String contentType, String content)
      throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setDataHandler(
        new DataHandler(
            new ByteArrayDataSource(content.getBytes(StandardCharsets.UTF_8), contentType)));
    part.setFileName(fileName);
    part.setDisposition(MimeBodyPart.ATTACHMENT);
    return part;
  }
}
