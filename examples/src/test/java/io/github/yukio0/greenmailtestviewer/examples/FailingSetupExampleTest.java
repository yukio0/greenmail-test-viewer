package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
@Tag("failing-example")
class FailingSetupExampleTest {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @BeforeAll
  static void loadTestData() {
    throw new IllegalStateException("The test data could not be loaded: customers.csv is missing");
  }

  @Test
  void sendsReminderMail() {
    GreenMailUtil.sendTextEmail(
        "oscar@example.com",
        "no-reply@example.com",
        "Your cart is waiting",
        "You left items in your cart.",
        greenMail.getSmtp().getServerSetup());

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }
}
