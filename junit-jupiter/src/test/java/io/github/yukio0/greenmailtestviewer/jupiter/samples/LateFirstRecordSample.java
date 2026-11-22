package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LateFirstRecordSample {

  public static final long WAIT_MILLIS = 1500;

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  @Order(1)
  void a_takesAWhile() throws InterruptedException {
    Thread.sleep(WAIT_MILLIS);
  }

  @Test
  @Order(2)
  void b_sends() {
    Mails.send(greenMail, "late first record");
  }
}
