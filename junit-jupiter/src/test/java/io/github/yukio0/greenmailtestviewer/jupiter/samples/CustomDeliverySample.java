package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserException;
import com.icegreen.greenmail.user.UserImpl;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class CustomDeliverySample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(
          new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort(), Servers.smtps()});

  @BeforeEach
  void forwardEveryMailToTheArchive() throws UserException {
    UserManager users = greenMail.getUserManager();
    GreenMailUser archive = users.createUser("archive@example.com", "archive", "secret");
    GreenMailUser relay =
        new UserImpl(
            "relay@example.com", "relay", "secret", greenMail.getManagers().getImapHostManager()) {
          @Override
          public void deliver(MovingMessage message) {
            archive.deliver(message);
          }
        };
    users.setMessageDeliveryHandler((message, address) -> relay);
  }

  @Test
  void sendsOverSmtp() {
    Mails.send(greenMail.getSmtp().getServerSetup(), "relayed over smtp");
  }

  @Test
  void sendsOverSmtps() {
    Mails.send(greenMail.getSmtps().getServerSetup(), "relayed over smtps");
  }
}
