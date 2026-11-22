package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.UserManager;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.Mails;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryRecorderTest {

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
  void deliversTheMailWhenGreenMailLacksTheMethodThatFindsTheInbox() {
    ImapHostManager imapHostManager =
        replacing(
            ImapHostManager.class,
            greenMail.getManagers().getImapHostManager(),
            "getInbox",
            args -> {
              throw new NoSuchMethodError("ImapHostManager.getInbox");
            });
    new DeliveryRecorder().startRecording(managers(imapHostManager));

    Mails.send(greenMail, "inbox not found");

    assertThat(greenMail.waitForIncomingEmail(5_000, 1)).isTrue();
  }

  @Test
  void deliversTheMailWhenGreenMailLacksTheMethodThatReadsTheNextUid() {
    ImapHostManager real = greenMail.getManagers().getImapHostManager();
    ImapHostManager imapHostManager =
        replacing(
            ImapHostManager.class,
            real,
            "getInbox",
            args ->
                replacing(
                    MailFolder.class,
                    real.getInbox((GreenMailUser) args[0]),
                    "getUIDNext",
                    none -> {
                      throw new NoSuchMethodError("MailFolder.getUIDNext");
                    }));
    new DeliveryRecorder().startRecording(managers(imapHostManager));

    Mails.send(greenMail, "next UID not read");

    assertThat(greenMail.waitForIncomingEmail(5_000, 1)).isTrue();
  }

  // The recording sees this ImapHostManager; GreenMail stores the mails with its own.
  private Managers managers(ImapHostManager imapHostManager) {
    UserManager userManager = greenMail.getUserManager();
    return new Managers() {
      @Override
      public UserManager getUserManager() {
        return userManager;
      }

      @Override
      public ImapHostManager getImapHostManager() {
        return imapHostManager;
      }
    };
  }

  @FunctionalInterface
  private interface Replacement {
    Object apply(Object[] args) throws Exception;
  }

  private static <T> T replacing(
      Class<T> type, T target, String methodName, Replacement replacement) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().equals(methodName)) {
                return replacement.apply(args);
              }
              try {
                return method.invoke(target, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            }));
  }
}
