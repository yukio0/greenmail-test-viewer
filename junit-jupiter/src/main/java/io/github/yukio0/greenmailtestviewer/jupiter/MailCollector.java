package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.imap.ImapHostManager;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.store.MailFolder;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import jakarta.mail.MessagingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Collects the mails that arrived since the last collection. A mail is identified by its mailbox
 * folder (compared by identity) and its UID, so that a restarted GreenMail counts as new.
 */
final class MailCollector {

  private static final Logger LOGGER = System.getLogger(MailCollector.class.getName());

  private final Map<MailFolder, Set<Long>> collectedUids = new WeakHashMap<>();
  private final DeliveryRecorder deliveryRecorder = new DeliveryRecorder();

  void startRecording(GreenMailOperations greenMail) {
    try {
      deliveryRecorder.startRecording(greenMail.getManagers());
    } catch (RuntimeException e) {
      // A GreenMailExtension has no GreenMail until it starts. Recording then starts at a later
      // point of the test lifecycle, or at the next collection.
    }
  }

  /**
   * The mails that arrived in the GreenMail instance since the last call, which are then marked as
   * collected. Empty if GreenMail has not started yet. Mails still being delivered are left for a
   * later call.
   */
  synchronized List<ReceivedMail> collectNew(GreenMailOperations greenMail) {
    List<Delivery> deliveries;
    try {
      Managers managers = greenMail.getManagers();
      deliveryRecorder.startRecording(managers);
      deliveries = readNewDeliveries(managers, deliveryRecorder.delivered());
    } catch (RuntimeException e) {
      return Collections.emptyList();
    }
    return groupBySentMail(deliveries);
  }

  private List<Delivery> readNewDeliveries(
      Managers managers, DeliveryRecorder.Delivered delivered) {
    ImapHostManager imapHostManager = managers.getImapHostManager();
    List<Delivery> deliveries = new ArrayList<>();
    for (GreenMailUser user : managers.getUserManager().listUser()) {
      MailFolder inbox = inboxOf(imapHostManager, user);
      if (inbox == null) {
        continue;
      }
      try {
        readNewDeliveries(user, inbox, delivered, deliveries);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "Could not read the emails stored in GreenMail for "
                + user.getEmail()
                + ". The emails of the other users are still collected.",
            e);
      }
    }
    return deliveries;
  }

  private static MailFolder inboxOf(ImapHostManager imapHostManager, GreenMailUser user) {
    try {
      // Null for a user that another test is deleting.
      return imapHostManager.getInbox(user);
    } catch (FolderException e) {
      return null;
    }
  }

  private void readNewDeliveries(
      GreenMailUser user,
      MailFolder inbox,
      DeliveryRecorder.Delivered delivered,
      List<Delivery> deliveries) {
    Set<Long> collected = collectedUids.computeIfAbsent(inbox, folder -> new HashSet<>());
    for (StoredMessage message : inbox.getMessages()) {
      long uid = message.getUid();
      if (collected.contains(uid)) {
        continue;
      }
      Long transaction = deliveryRecorder.transactionOf(inbox, uid);
      if (transaction != null && !delivered.includes(transaction)) {
        continue;
      }
      collected.add(uid);
      deliveryRecorder.forget(inbox, uid);
      byte[] rawMime;
      try {
        rawMime = rawMime(message);
      } catch (IOException | MessagingException | RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "Could not read an email stored in GreenMail for " + user.getEmail() + ".",
            e);
        continue;
      }
      long receivedAt =
          message.getReceivedDate() == null ? 0L : message.getReceivedDate().getTime();
      deliveries.add(new Delivery(user.getEmail(), rawMime, receivedAt, transaction));
    }
  }

  /**
   * Merges the copies of a mail, one per recipient, into one mail: by their SMTP transaction, or by
   * their content when they were delivered before recording started.
   */
  private static List<ReceivedMail> groupBySentMail(List<Delivery> deliveries) {
    Map<Long, Group> groupsByTransaction = new HashMap<>();
    Map<ByteBuffer, List<Group>> groupsByContent = new HashMap<>();
    List<Group> groups = new ArrayList<>();
    for (Delivery delivery : deliveries) {
      Group target;
      if (delivery.transaction != null) {
        target = groupsByTransaction.get(delivery.transaction);
        if (target == null) {
          target = new Group(delivery.rawMime);
          groupsByTransaction.put(delivery.transaction, target);
          groups.add(target);
        }
      } else {
        List<Group> sameContent =
            groupsByContent.computeIfAbsent(
                ByteBuffer.wrap(sha256(delivery.rawMime)), key -> new ArrayList<>());
        target = null;
        for (Group group : sameContent) {
          if (!group.recipients.contains(delivery.recipient)) {
            target = group;
            break;
          }
        }
        if (target == null) {
          target = new Group(delivery.rawMime);
          sameContent.add(target);
          groups.add(target);
        }
      }
      target.recipients.add(delivery.recipient);
      target.receivedAt = Math.min(target.receivedAt, delivery.receivedAt);
    }
    groups.sort(Comparator.comparingLong(group -> group.receivedAt));
    List<ReceivedMail> mails = new ArrayList<>(groups.size());
    for (Group group : groups) {
      List<String> recipients = new ArrayList<>(group.recipients);
      Collections.sort(recipients);
      mails.add(new ReceivedMail(group.rawMime, recipients));
    }
    return mails;
  }

  private static byte[] rawMime(StoredMessage message) throws IOException, MessagingException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    message.getMimeMessage().writeTo(out);
    return out.toByteArray();
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static final class Delivery {
    private final String recipient;
    private final byte[] rawMime;
    private final long receivedAt;
    private final Long transaction;

    Delivery(String recipient, byte[] rawMime, long receivedAt, Long transaction) {
      this.recipient = recipient;
      this.rawMime = rawMime;
      this.receivedAt = receivedAt;
      this.transaction = transaction;
    }
  }

  private static final class Group {
    private final byte[] rawMime;
    private final Set<String> recipients = new LinkedHashSet<>();
    private long receivedAt = Long.MAX_VALUE;

    Group(byte[] rawMime) {
      this.rawMime = rawMime;
    }
  }
}
