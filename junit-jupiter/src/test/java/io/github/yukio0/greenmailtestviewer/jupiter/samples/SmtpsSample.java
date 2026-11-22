package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class SmtpsSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(
          new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort(), Servers.smtps()});

  @Test
  void sendsOneMailToSeveralRecipientsOverSmtps() throws MessagingException {
    Mails.sendToSeveral(
        greenMail.getSmtps().getServerSetup(),
        "smtps one send",
        "to@example.com",
        "cc@example.com",
        "bcc@example.com");
  }

  @Test
  void sendsTheSameMailSeparatelyOverSmtps() throws MessagingException {
    Mails.sendSeparately(
        greenMail.getSmtps().getServerSetup(),
        "smtps separate sends",
        "a@example.com",
        "b@example.com");
  }

  @Test
  void sendsOverSmtpAndSmtps() {
    Mails.send(greenMail.getSmtp().getServerSetup(), "over smtp");
    Mails.send(greenMail.getSmtps().getServerSetup(), "over smtps");
  }
}
