package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class PerMethodExtensionSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void sendsOneMail() {
    Mails.send(greenMail, "per-method one");
  }

  @Test
  void sendsTwoMails() {
    Mails.send(greenMail, "per-method two a");
    Mails.send(greenMail, "per-method two b");
  }

  @Test
  void sendsNoMail() {}
}
