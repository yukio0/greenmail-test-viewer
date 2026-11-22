package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
public class SameTestInTwoExecutionsSample {

  public static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  public static volatile CyclicBarrier bothSent;

  @Test
  void sends() throws Exception {
    Mails.send(GREEN_MAIL, "sent by one of two executions");
    bothSent.await(10, TimeUnit.SECONDS);
  }
}
