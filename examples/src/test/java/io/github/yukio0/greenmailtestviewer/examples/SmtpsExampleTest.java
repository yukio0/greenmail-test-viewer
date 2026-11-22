package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
class SmtpsExampleTest {

  @RegisterExtension static final GreenMailExtension greenMail = new GreenMailExtension(smtps());

  private static ServerSetup smtps() {
    ServerSetup smtps = ServerSetupTest.SMTPS.dynamicPort();
    smtps.setServerStartupTimeout(10_000);
    return smtps;
  }

  @Test
  void sendsInvoiceOverSmtps() throws MessagingException {
    Session session = smtpsSession();
    MimeMessage message = new MimeMessage(session);
    message.setFrom(new InternetAddress("billing@example.com"));
    message.setRecipient(RecipientType.TO, new InternetAddress("paul@example.com"));
    message.setSubject("Your invoice #2001", "UTF-8");
    message.setText("Thank you for your order. Your invoice is ready.", "UTF-8");

    try (Transport transport = session.getTransport("smtps")) {
      transport.connect();
      transport.sendMessage(message, message.getAllRecipients());
    }

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  private static Session smtpsSession() {
    ServerSetup server = greenMail.getSmtps().getServerSetup();
    Properties properties = new Properties();
    properties.setProperty("mail.smtps.host", server.getBindAddress());
    properties.setProperty("mail.smtps.port", String.valueOf(server.getPort()));
    properties.setProperty("mail.smtps.ssl.trust", "*");
    properties.setProperty("mail.smtps.ssl.checkserveridentity", "false");
    return Session.getInstance(properties);
  }
}
