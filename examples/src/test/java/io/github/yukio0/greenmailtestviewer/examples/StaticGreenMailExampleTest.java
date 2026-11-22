package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
class StaticGreenMailExampleTest {

  static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  @BeforeAll
  static void startMailServer() {
    greenMail.start();
  }

  @AfterAll
  static void stopMailServer() {
    greenMail.stop();
  }

  @AfterEach
  void deleteMails() throws FolderException {
    greenMail.purgeEmailFromAllMailboxes();
  }

  @Test
  void sendsOrderConfirmation() {
    GreenMailUtil.sendTextEmail(
        "erin@example.com",
        "shop@example.com",
        "Order confirmation #1001",
        "We received your order.",
        greenMail.getSmtp().getServerSetup());

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void sendsShippingNotice() {
    GreenMailUtil.sendTextEmail(
        "erin@example.com",
        "shop@example.com",
        "Your order #1001 has shipped",
        "Your parcel is on its way.",
        greenMail.getSmtp().getServerSetup());

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }
}
