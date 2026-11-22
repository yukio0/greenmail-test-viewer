package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.store.FolderException;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewerExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
class WatchedGreenMailExampleTest {

  static final MailTestSupport mail = new MailTestSupport();

  @BeforeAll
  static void startMailServer() {
    mail.start();
    GreenMailTestViewerExtension.watch(mail.greenMail());
  }

  @AfterAll
  static void stopMailServer() {
    mail.stop();
  }

  @AfterEach
  void deleteMails() throws FolderException {
    mail.deleteMails();
  }

  @Test
  void confirmsANewSupportTicket() {
    mail.send(
        "grace@example.com",
        "We received your request #4711",
        "Our support team will reply within one business day.");

    assertThat(mail.receivedCount()).isEqualTo(1);
  }

  @Test
  void sendsTheAnswerToATicket() {
    mail.send(
        "grace@example.com",
        "Re: We received your request #4711",
        "You can reset your password from the sign-in page.");

    assertThat(mail.receivedCount()).isEqualTo(1);
  }
}
