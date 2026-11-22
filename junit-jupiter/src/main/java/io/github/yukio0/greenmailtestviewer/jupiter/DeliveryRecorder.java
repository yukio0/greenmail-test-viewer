package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.mail.MailAddress;
import com.icegreen.greenmail.mail.MovingMessage;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.user.MessageDeliveryHandler;
import com.icegreen.greenmail.user.UserException;
import com.icegreen.greenmail.user.UserManager;
import jakarta.mail.MessagingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Records which SMTP transaction each stored copy of a mail belongs to, and which transactions are
 * still being delivered.
 *
 * <p>GreenMail stores one copy per recipient. Wrapping its {@link MessageDeliveryHandler}
 * distinguishes the copies of one transaction from identical mails sent separately, and lets a
 * collection skip a transaction until every recipient has its copy.
 */
final class DeliveryRecorder {

  private final Map<MailFolder, Map<Long, Long>> transactionsByUid = new WeakHashMap<>();
  private final Map<MovingMessage, Transaction> transactions = new WeakHashMap<>();
  private final Map<UserManager, Transaction> delivering = new WeakHashMap<>();
  private long lastTransactionId;

  // When a test wraps the handler again, only the outer handler records the delivery.
  private final ThreadLocal<Boolean> recording = ThreadLocal.withInitial(() -> false);

  synchronized void startRecording(Managers managers) {
    UserManager userManager = managers.getUserManager();
    MessageDeliveryHandler current = userManager.getMessageDeliveryHandler();
    if (current instanceof RecordingHandler && ((RecordingHandler) current).owner() == this) {
      return;
    }
    userManager.setMessageDeliveryHandler(
        new RecordingHandler(current, userManager, managers.getImapHostManager()));
  }

  /**
   * The transactions that have now been delivered to all their recipients, taken before a
   * collection reads the mailboxes.
   */
  synchronized Delivered delivered() {
    Set<Long> unfinished = new HashSet<>();
    for (Transaction transaction : delivering.values()) {
      unfinished.add(transaction.id);
    }
    return new Delivered(lastTransactionId, unfinished);
  }

  synchronized Long transactionOf(MailFolder folder, long uid) {
    Map<Long, Long> byUid = transactionsByUid.get(folder);
    return byUid == null ? null : byUid.get(uid);
  }

  synchronized void forget(MailFolder folder, long uid) {
    Map<Long, Long> byUid = transactionsByUid.get(folder);
    if (byUid != null) {
      byUid.remove(uid);
    }
  }

  private synchronized Transaction transactionOf(
      MovingMessage message, MailAddress address, UserManager userManager) {
    Transaction transaction = transactions.get(message);
    if (transaction == null) {
      transaction = new Transaction(++lastTransactionId, recipientsFrom(message, address));
      transactions.put(message, transaction);
      delivering.put(userManager, transaction);
    }
    return transaction;
  }

  // Called by reflection, because GreenMail 2.1.0 returns a List and later versions a Set. Returns
  // 1 when the recipients cannot be determined, so that the delivery ends after each copy rather
  // than never.
  private static int recipientsFrom(MovingMessage message, MailAddress address) {
    try {
      Object addresses = MovingMessage.class.getMethod("getToAddresses").invoke(message);
      if (addresses instanceof Collection) {
        Collection<?> all = (Collection<?>) addresses;
        int before = 0;
        for (Object each : all) {
          if (each == address) {
            return all.size() - before;
          }
          before++;
        }
        return Math.max(1, all.size());
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
    }
    return 1;
  }

  private synchronized void deliveredToOne(UserManager userManager, Transaction transaction) {
    transaction.delivered++;
    if (transaction.delivered >= transaction.recipients) {
      end(userManager, transaction);
    }
  }

  private synchronized void end(UserManager userManager, Transaction transaction) {
    delivering.remove(userManager, transaction);
  }

  private synchronized void remember(MailFolder folder, long uid, long transaction) {
    transactionsByUid.computeIfAbsent(folder, key -> new HashMap<>()).put(uid, transaction);
  }

  private synchronized void unremember(MailFolder folder, long uid, long transaction) {
    Map<Long, Long> byUid = transactionsByUid.get(folder);
    if (byUid != null) {
      byUid.remove(uid, transaction);
    }
  }

  static final class Delivered {
    private final long lastTransaction;
    private final Set<Long> unfinished;

    Delivered(long lastTransaction, Set<Long> unfinished) {
      this.lastTransaction = lastTransaction;
      this.unfinished = unfinished;
    }

    boolean includes(long transaction) {
      return transaction <= lastTransaction && !unfinished.contains(transaction);
    }
  }

  private static final class Transaction {
    private final long id;
    private final int recipients;
    private int delivered;

    Transaction(long id, int recipients) {
      this.id = id;
      this.recipients = recipients;
    }
  }

  private final class RecordingHandler implements MessageDeliveryHandler {
    private final MessageDeliveryHandler delegate;
    private final UserManager userManager;
    private final ImapHostManager imapHostManager;

    RecordingHandler(
        MessageDeliveryHandler delegate, UserManager userManager, ImapHostManager imapHostManager) {
      this.delegate = delegate;
      this.userManager = userManager;
      this.imapHostManager = imapHostManager;
    }

    DeliveryRecorder owner() {
      return DeliveryRecorder.this;
    }

    @Override
    public GreenMailUser handle(MovingMessage message, MailAddress mailAddress)
        throws MessagingException, UserException {
      if (recording.get()) {
        return delegate.handle(message, mailAddress);
      }
      Transaction transaction;
      try {
        transaction = transactionOf(message, mailAddress, userManager);
      } catch (RuntimeException | LinkageError e) {
        return delegate.handle(message, mailAddress);
      }
      GreenMailUser user;
      recording.set(true);
      try {
        user = delegate.handle(message, mailAddress);
      } catch (RuntimeException | Error | MessagingException | UserException e) {
        end(userManager, transaction);
        throw e;
      } finally {
        recording.set(false);
      }
      // A proxy, so that methods that later GreenMail versions add still work.
      return (GreenMailUser)
          Proxy.newProxyInstance(
              GreenMailUser.class.getClassLoader(),
              new Class<?>[] {GreenMailUser.class},
              (proxy, method, args) ->
                  isDeliveryOf(method, args, message) && !recording.get()
                      ? deliver(user, method, args, transaction)
                      : invoke(user, method, args));
    }

    private Object deliver(
        GreenMailUser user, Method method, Object[] args, Transaction transaction)
        throws Throwable {
      MailFolder inbox = inboxOf(user);
      long uid = uidNext(inbox);
      if (uid >= 0) {
        // Remembered before the copy is stored, so that a collection never sees it without its
        // transaction.
        remember(inbox, uid, transaction.id);
      }
      Object result;
      recording.set(true);
      try {
        result = invoke(user, method, args);
      } catch (Throwable e) {
        keepIfStored(inbox, uid, transaction);
        end(userManager, transaction);
        throw e;
      } finally {
        recording.set(false);
      }
      keepIfStored(inbox, uid, transaction);
      deliveredToOne(userManager, transaction);
      return result;
    }

    private void keepIfStored(MailFolder inbox, long uid, Transaction transaction) {
      if (uid >= 0 && uidNext(inbox) != uid + 1) {
        unremember(inbox, uid, transaction.id);
      }
    }

    private Object invoke(GreenMailUser user, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(user, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    // Called during GreenMail's delivery, where any error, even a LinkageError, loses the mail.
    private MailFolder inboxOf(GreenMailUser user) {
      try {
        return imapHostManager.getInbox(user);
      } catch (Exception | LinkageError e) {
        return null;
      }
    }

    private long uidNext(MailFolder folder) {
      if (folder == null) {
        return -1;
      }
      try {
        return folder.getUIDNext();
      } catch (RuntimeException | LinkageError e) {
        return -1;
      }
    }

    private boolean isDeliveryOf(Method method, Object[] args, MovingMessage message) {
      return method.getName().equals("deliver")
          && args != null
          && args.length == 1
          && args[0] == message;
    }
  }
}
