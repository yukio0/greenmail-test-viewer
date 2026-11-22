package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class PausedDeliverySample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  private final CountDownLatch goOn = new CountDownLatch(1);
  private final ExecutorService application = Executors.newSingleThreadExecutor();
  private Future<?> sent;

  @Test
  void sendsWhileTheServerHangs() throws InterruptedException {
    UserManager users = greenMail.getUserManager();
    MessageDeliveryHandler delivery = users.getMessageDeliveryHandler();
    CountDownLatch deliveringToSecond = new CountDownLatch(1);
    AtomicInteger deliveries = new AtomicInteger();
    users.setMessageDeliveryHandler((message, address) -> {
      if (deliveries.incrementAndGet() == 2) {
        deliveringToSecond.countDown();
        awaitAfterEach();
      }
      return delivery.handle(message, address);
    });
    sent =
        application.submit(() -> {
          Mails.sendToSeveral(
              greenMail, "paused delivery", "a@example.com", "b@example.com", "c@example.com");
          return null;
        });
    deliveringToSecond.await(10, TimeUnit.SECONDS);
  }

  private void awaitAfterEach() {
    try {
      if (!goOn.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("@AfterEach was not reached");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @AfterEach
  void letTheServerGoOn() throws Exception {
    goOn.countDown();
    try {
      sent.get(10, TimeUnit.SECONDS);
    } finally {
      application.shutdownNow();
    }
  }
}
