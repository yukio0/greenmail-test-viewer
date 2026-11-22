package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class SeparateSendsSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void sendsTheSameMailSeparatelyToEachRecipient() throws MessagingException {
    Mails.sendSeparately(greenMail, "separate sends", "a@example.com", "b@example.com");
  }

  @Test
  void sendsOneMailToSeveralRecipients() throws MessagingException {
    Mails.sendToSeveral(
        greenMail, "one send", "to@example.com", "cc@example.com", "bcc@example.com");
  }
}
