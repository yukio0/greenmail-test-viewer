package io.github.yukio0.greenmailtestviewer.examples;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;

// Holds GreenMail, so the test class has no GreenMail field for the extension to find.
final class MailTestSupport {

  private final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  GreenMail greenMail() {
    return greenMail;
  }

  void start() {
    greenMail.start();
  }

  void stop() {
    greenMail.stop();
  }

  void send(String to, String subject, String body) {
    GreenMailUtil.sendTextEmail(
        to, "support@example.com", subject, body, greenMail.getSmtp().getServerSetup());
  }

  int receivedCount() {
    return greenMail.getReceivedMessages().length;
  }

  void deleteMails() throws FolderException {
    greenMail.purgeEmailFromAllMailboxes();
  }
}
