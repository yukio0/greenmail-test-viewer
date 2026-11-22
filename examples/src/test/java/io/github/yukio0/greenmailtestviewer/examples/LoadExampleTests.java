package io.github.yukio0.greenmailtestviewer.examples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("load-example")
abstract class LoadExampleTests {

  static final String TESTS_PROPERTY = "greenmail.testviewer.examples.load.tests";
  static final String MAILS_PROPERTY = "greenmail.testviewer.examples.load.mails";

  abstract GreenMailExtension greenMail();

  static IntStream tests() {
    return IntStream.rangeClosed(1, Integer.getInteger(TESTS_PROPERTY, 100));
  }

  @ParameterizedTest(name = "test {0}")
  @MethodSource("tests")
  void sendsMails(int test) throws MessagingException {
    int mails = Integer.getInteger(MAILS_PROPERTY, 3);
    for (int mail = 1; mail <= mails; mail++) {
      Transport.send(message(test, mail));
    }
  }

  private MimeMessage message(int test, int mail) throws MessagingException {
    MimeMessage message =
        new MimeMessage(GreenMailUtil.getSession(greenMail().getSmtp().getServerSetup()));
    message.setFrom(new InternetAddress("no-reply@example.com"));
    message.setRecipient(
        RecipientType.TO, new InternetAddress("user" + (test % 10) + "@example.com"));
    message.setSubject("Load test " + test + ", mail " + mail, "UTF-8");
    String text = "Mail " + mail + " of test " + test + ". Open https://example.com/orders/" + test;
    switch (mail % 3) {
      case 1:
        message.setText(text, "UTF-8");
        break;
      case 2:
        message.setContent(alternative(text));
        break;
      default:
        MimeBodyPart body = new MimeBodyPart();
        body.setContent(alternative(text));
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(
            new DataHandler(
                new ByteArrayDataSource(
                    ("order,amount\n" + test + ",1000\n").getBytes(StandardCharsets.UTF_8),
                    "text/csv")));
        attachment.setFileName("order-" + test + ".csv");
        message.setContent(new MimeMultipart(body, attachment));
    }
    return message;
  }

  private static MimeMultipart alternative(String text) throws MessagingException {
    MimeBodyPart plain = new MimeBodyPart();
    plain.setText(text, "UTF-8");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent("<h1>Load test</h1><p>" + text + "</p>", "text/html; charset=UTF-8");
    return new MimeMultipart("alternative", plain, html);
  }
}
