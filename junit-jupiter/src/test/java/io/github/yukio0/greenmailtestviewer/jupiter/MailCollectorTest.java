package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.user.UserException;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.Mails;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MailCollectorTest {

  private GreenMail greenMail;

  @BeforeEach
  void startGreenMail() {
    greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    greenMail.start();
  }

  @AfterEach
  void stopGreenMail() {
    greenMail.stop();
  }

  @Test
  void returnsTheMailsOfTheOtherUsersWhenOneHasNoInbox() throws Exception {
    MailCollector collector = new MailCollector();
    collector.startRecording(greenMail);
    Mails.sendToSeveral(greenMail, "Shared", "a@example.com", "b@example.com", "c@example.com");
    Mails.send(greenMail, "Single");
    GreenMailUser gone = greenMail.setUser("gone@example.com", "secret");
    ImapHostManager imapHostManager = greenMail.getManagers().getImapHostManager();
    imapHostManager.getStore().deleteMailbox(imapHostManager.getInbox(gone));

    List<ReceivedMail> first = collector.collectNew(greenMail);

    assertThat(first)
        .extracting(MailCollectorTest::subject, ReceivedMail::deliveredTo)
        .containsExactlyInAnyOrder(
            tuple("Shared", List.of("a@example.com", "b@example.com", "c@example.com")),
            tuple("Single", List.of("to@example.com")));

    Mails.send(greenMail, "Later");
    assertThat(collector.collectNew(greenMail))
        .extracting(MailCollectorTest::subject, ReceivedMail::deliveredTo)
        .containsExactly(tuple("Later", List.of("to@example.com")));
  }

  @Test
  void collectsAMailOnceItIsDeliveredToAllItsRecipients() throws Exception {
    CountDownLatch goOn = new CountDownLatch(1);
    CountDownLatch deliveringToSecond = pauseBeforeSecondDelivery(greenMail, goOn);
    MailCollector collector = new MailCollector();
    collector.startRecording(greenMail);
    ExecutorService threads = Executors.newCachedThreadPool();
    try {
      Future<?> sent = sendSharedFrom(threads);
      await(deliveringToSecond);

      assertThat(collectWithin(threads, collector::collectNew, greenMail)).isEmpty();

      goOn.countDown();
      sent.get(10, TimeUnit.SECONDS);
      assertThat(collector.collectNew(greenMail))
          .extracting(MailCollectorTest::subject, ReceivedMail::deliveredTo)
          .containsExactly(
              tuple("Shared", List.of("a@example.com", "b@example.com", "c@example.com")));
    } finally {
      goOn.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  void collectsAMailWhoseRecordingStartedWhileItWasDelivered() throws Exception {
    CountDownLatch goOn = new CountDownLatch(1);
    CountDownLatch deliveringToSecond = pauseBeforeSecondDelivery(greenMail, goOn);
    MailCollector collector = new MailCollector();
    ExecutorService threads = Executors.newCachedThreadPool();
    try {
      Future<?> sent = sendSharedFrom(threads);
      await(deliveringToSecond);
      collector.startRecording(greenMail);
      goOn.countDown();
      sent.get(10, TimeUnit.SECONDS);

      List<String> recipients = new ArrayList<>();
      for (ReceivedMail mail : collector.collectNew(greenMail)) {
        assertThat(subject(mail)).isEqualTo("Shared");
        recipients.addAll(mail.deliveredTo());
      }
      assertThat(recipients)
          .containsExactlyInAnyOrder("a@example.com", "b@example.com", "c@example.com");
    } finally {
      goOn.countDown();
      threads.shutdownNow();
    }
  }

  @Test
  void collectsTheCopiesOfAMailThatCouldNotBeDeliveredToAll() throws Exception {
    UserManager userManager = greenMail.getUserManager();
    MessageDeliveryHandler delivery = userManager.getMessageDeliveryHandler();
    AtomicInteger deliveries = new AtomicInteger();
    userManager.setMessageDeliveryHandler((message, address) -> {
      if (deliveries.incrementAndGet() == 2) {
        throw new UserException("No such user");
      }
      return delivery.handle(message, address);
    });
    MailCollector collector = new MailCollector();
    collector.startRecording(greenMail);

    assertThatThrownBy(() ->
            Mails.sendToSeveral(
                greenMail, "Shared", "a@example.com", "b@example.com", "c@example.com"))
        .isInstanceOf(MessagingException.class);

    List<ReceivedMail> mails = collector.collectNew(greenMail);
    assertThat(mails).extracting(MailCollectorTest::subject).containsExactly("Shared");
    assertThat(mails.get(0).deliveredTo()).hasSize(1);
  }

  @Test
  void collectsAMailWhoseEndWasNotSeenOnceTheNextIsDelivered() throws Exception {
    MailCollector collector = new MailCollector();
    collector.startRecording(greenMail);
    UserManager userManager = greenMail.getUserManager();
    MessageDeliveryHandler recording = userManager.getMessageDeliveryHandler();
    for (String address : List.of("a@example.com", "b@example.com", "c@example.com")) {
      greenMail.setUser(address, "secret");
    }
    AtomicInteger deliveries = new AtomicInteger();
    userManager.setMessageDeliveryHandler((message, address) ->
        deliveries.incrementAndGet() == 3
            ? userManager.getUserByEmail(address.getEmail())
            : recording.handle(message, address));
    Mails.sendToSeveral(greenMail, "Shared", "a@example.com", "b@example.com", "c@example.com");

    List<ReceivedMail> first = collector.collectNew(greenMail);
    assertThat(first).extracting(MailCollectorTest::subject).containsExactly("Shared");
    assertThat(first.get(0).deliveredTo()).hasSize(1);

    Mails.send(greenMail, "Next");
    List<ReceivedMail> second = collector.collectNew(greenMail);
    assertThat(second).extracting(MailCollectorTest::subject).containsExactly("Shared", "Next");
    assertThat(second.get(0).deliveredTo())
        .hasSize(2)
        .doesNotContainAnyElementsOf(first.get(0).deliveredTo());
  }

  @Test
  void collectsAnotherGreenMailWhileOneIsDelivering() throws Exception {
    MailCollector collector = new MailCollector();
    assertCollectsAnotherGreenMailWhileOneIsDelivering(collector::collectNew);
  }

  @Test
  void theExtensionTakesTheMailsOfAnotherGreenMailWhileOneIsDelivering() throws Exception {
    assertCollectsAnotherGreenMailWhileOneIsDelivering(greenMail -> {
      GreenMailTestViewerExtension.OwnedMails taken =
          GreenMailTestViewerExtension.takeNewMails(greenMail, MailOwner.outsideClass(), null);
      return taken == null ? List.of() : taken.mails;
    });
  }

  private void assertCollectsAnotherGreenMailWhileOneIsDelivering(
      Function<GreenMailOperations, List<ReceivedMail>> collect) throws Exception {
    GreenMail other = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    other.start();
    CountDownLatch goOn = new CountDownLatch(1);
    ExecutorService threads = Executors.newCachedThreadPool();
    try {
      CountDownLatch deliveringToSecond = pauseBeforeSecondDelivery(greenMail, goOn);
      collect.apply(greenMail);
      Mails.send(other, "Other");
      Future<?> sent = sendSharedFrom(threads);
      await(deliveringToSecond);

      assertThat(collectWithin(threads, collect, greenMail)).isEmpty();
      assertThat(collectWithin(threads, collect, other))
          .extracting(MailCollectorTest::subject)
          .containsExactly("Other");

      goOn.countDown();
      sent.get(10, TimeUnit.SECONDS);
      assertThat(collect.apply(greenMail))
          .extracting(MailCollectorTest::subject, ReceivedMail::deliveredTo)
          .containsExactly(
              tuple("Shared", List.of("a@example.com", "b@example.com", "c@example.com")));
    } finally {
      goOn.countDown();
      threads.shutdownNow();
      other.stop();
    }
  }

  private static List<ReceivedMail> collectWithin(
      ExecutorService threads,
      Function<GreenMailOperations, List<ReceivedMail>> collect,
      GreenMailOperations greenMail)
      throws Exception {
    return threads.submit(() -> collect.apply(greenMail)).get(5, TimeUnit.SECONDS);
  }

  private static CountDownLatch pauseBeforeSecondDelivery(
      GreenMail greenMail, CountDownLatch goOn) {
    CountDownLatch deliveringToSecond = new CountDownLatch(1);
    UserManager userManager = greenMail.getUserManager();
    MessageDeliveryHandler delivery = userManager.getMessageDeliveryHandler();
    AtomicInteger deliveries = new AtomicInteger();
    userManager.setMessageDeliveryHandler((message, address) -> {
      if (deliveries.incrementAndGet() == 2) {
        deliveringToSecond.countDown();
        await(goOn);
      }
      return delivery.handle(message, address);
    });
    return deliveringToSecond;
  }

  private Future<?> sendSharedFrom(ExecutorService thread) {
    return thread.submit(() -> {
      Mails.sendToSeveral(greenMail, "Shared", "a@example.com", "b@example.com", "c@example.com");
      return null;
    });
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String subject(ReceivedMail mail) {
    String raw = new String(mail.rawMime(), StandardCharsets.UTF_8);
    for (String line : raw.split("\r\n")) {
      if (line.startsWith("Subject: ")) {
        return line.substring("Subject: ".length());
      }
    }
    return null;
  }
}
