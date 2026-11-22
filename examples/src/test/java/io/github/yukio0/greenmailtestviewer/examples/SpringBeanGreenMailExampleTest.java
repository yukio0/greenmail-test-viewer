package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@GreenMailTestViewer
@SpringBootTest(classes = SpringBeanGreenMailExampleTest.MailConfiguration.class)
class SpringBeanGreenMailExampleTest {

  @Autowired GreenMail greenMail;

  @Autowired JavaMailSender mailSender;

  @AfterEach
  void deleteMails() throws FolderException {
    greenMail.purgeEmailFromAllMailboxes();
  }

  @Test
  void sendsWelcomeMail() {
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom("no-reply@example.com");
    message.setTo("frank@example.com");
    message.setSubject("Welcome, Frank (sent with Spring)");
    message.setText("Thank you for signing up.");

    mailSender.send(message);

    assertThat(greenMail.getReceivedMessages()).hasSize(1);
  }

  @Configuration(proxyBeanMethods = false)
  static class MailConfiguration {

    @Bean(initMethod = "start", destroyMethod = "stop")
    GreenMail greenMail() {
      return new GreenMail(ServerSetupTest.SMTP.dynamicPort());
    }

    @Bean
    JavaMailSender mailSender(GreenMail greenMail) {
      JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
      mailSender.setHost("127.0.0.1");
      mailSender.setPort(greenMail.getSmtp().getPort());
      return mailSender;
    }
  }
}
