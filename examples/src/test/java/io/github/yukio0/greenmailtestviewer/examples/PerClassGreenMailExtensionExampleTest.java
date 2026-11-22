package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
class PerClassGreenMailExtensionExampleTest {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

  @BeforeAll
  static void sendMaintenanceNotice() {
    send("ops@example.com", "Maintenance starts tonight");
  }

  @Test
  void sendsInvoice() {
    send("carol@example.com", "Your invoice for November");

    assertThat(greenMail.getReceivedMessages()).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void sendsReceipt() {
    send("dave@example.com", "Receipt for your order");

    assertThat(greenMail.getReceivedMessages()).hasSizeGreaterThanOrEqualTo(2);
  }

  private static void send(String to, String subject) {
    GreenMailUtil.sendTextEmail(
        to,
        "no-reply@example.com",
        subject,
        "Body of " + subject,
        greenMail.getSmtp().getServerSetup());
  }
}
