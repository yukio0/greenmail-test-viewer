package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public final class Mails {

  private Mails() {}

  public static void send(GreenMailOperations greenMail, String subject) {
    send(greenMail.getSmtp().getServerSetup(), subject);
  }

  public static void send(ServerSetup server, String subject) {
    GreenMailUtil.sendTextEmail(
        "to@example.com", "from@example.com", subject, "Body of " + subject, server);
  }

  public static void sendToSeveral(
      GreenMailOperations greenMail, String subject, String to, String cc, String bcc)
      throws MessagingException {
    sendToSeveral(greenMail.getSmtp().getServerSetup(), subject, to, cc, bcc);
  }

  public static void sendToSeveral(
      ServerSetup server, String subject, String to, String cc, String bcc)
      throws MessagingException {
    MimeMessage message = new MimeMessage(GreenMailUtil.getSession(server));
    message.setFrom(new InternetAddress("from@example.com"));
    message.setRecipient(RecipientType.TO, new InternetAddress(to));
    message.setRecipient(RecipientType.CC, new InternetAddress(cc));
    message.setRecipient(RecipientType.BCC, new InternetAddress(bcc));
    message.setSubject(subject);
    message.setText("Body of " + subject);
    Transport.send(message);
  }

  public static void sendSeparately(GreenMailOperations greenMail, String subject, String... to)
      throws MessagingException {
    sendSeparately(greenMail.getSmtp().getServerSetup(), subject, to);
  }

  public static void sendSeparately(ServerSetup server, String subject, String... to)
      throws MessagingException {
    Session session = GreenMailUtil.getSession(server);
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress("from@example.com"));
    message.setRecipient(RecipientType.TO, new InternetAddress(to[0]));
    message.setSubject(subject);
    message.setText("Body of " + subject);
    message.saveChanges();
    try (Transport transport = session.getTransport(server.getProtocol())) {
      transport.connect();
      for (String recipient : to) {
        transport.sendMessage(message, new Address[] {new InternetAddress(recipient)});
      }
    }
  }
}
