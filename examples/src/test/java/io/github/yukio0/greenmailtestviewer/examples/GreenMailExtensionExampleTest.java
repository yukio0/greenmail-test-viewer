package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
class GreenMailExtensionExampleTest {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void sendsWelcomeMail() {
    GreenMailUtil.sendTextEmail(
        "alice@example.com",
        "no-reply@example.com",
        "Welcome, Alice",
        "Thank you for signing up.",
        greenMail.getSmtp().getServerSetup());

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsPasswordResetMail() {
    GreenMailUtil.sendTextEmail(
        "bob@example.com",
        "no-reply@example.com",
        "Reset your password",
        "Open the link below to set a new password.\n\n"
            + "https://shop.example.com/reset-password?token=4f2a9c7e",
        greenMail.getSmtp().getServerSetup());

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
    assertThat(GreenMailUtil.getBody(greenMail.getReceivedMessages()[0]))
        .contains("https://shop.example.com/reset-password?token=");
  }

  @Test
  void sendsNoMailWhenNothingHappens() {
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }
}
