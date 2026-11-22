package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@GreenMailTestViewer
public class StaticGreenMailSample {

  static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  @BeforeAll
  static void startMailServer() {
    greenMail.start();
    Mails.send(greenMail, "static before all");
  }

  @AfterAll
  static void stopMailServer() {
    greenMail.stop();
  }

  @BeforeEach
  void sendBeforeEach(TestInfo testInfo) {
    Mails.send(greenMail, "static before each " + testInfo.getTestMethod().get().getName());
  }

  @AfterEach
  void deleteMails() throws FolderException {
    greenMail.purgeEmailFromAllMailboxes();
  }

  @Test
  void first() {
    Mails.send(greenMail, "static first");
  }

  @Test
  void second() {}
}
