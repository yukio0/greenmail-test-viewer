package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
@Tag("failing-example")
class FailingTestExampleTest {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void expectsAnotherSubject() throws MessagingException {
    sendMail("kate@example.com", "Welcome to our shop", "Thank you for signing up.");

    assertThat(greenMail.getReceivedMessages()[0].getSubject()).isEqualTo("Welcome, Kate");
  }

  @Test
  void expectsAMailThatIsNotSent() {
    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Test
  void throwsAnExceptionAfterSendingAMail() {
    sendMail("leo@example.com", "Your invoice", "Your invoice is attached.");

    throw new IllegalStateException(
        "The invoice could not be attached: invoice-1001.pdf is missing");
  }

  @Test
  void stopsWhenAnAssumptionDoesNotHold() {
    sendMail("mia@example.com", "Your weekly digest", "Here is what happened this week.");

    assumeTrue(false, "The digest is only checked on Mondays");
  }

  @Test
  @Disabled("The newsletter is not ready yet")
  void sendsNewsletter() {
    sendMail("nina@example.com", "Our newsletter", "News from our shop.");
  }

  private static void sendMail(String to, String subject, String text) {
    GreenMailUtil.sendTextEmail(
        to, "no-reply@example.com", subject, text, greenMail.getSmtp().getServerSetup());
  }
}
