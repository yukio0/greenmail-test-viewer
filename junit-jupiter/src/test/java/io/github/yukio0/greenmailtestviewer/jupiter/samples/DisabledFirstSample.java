package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DisabledFirstSample {

  @RegisterExtension
  final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  @Order(1)
  @Disabled("for the sample")
  void disabledFirst() {}

  @Test
  @Order(2)
  void sendsAfterTheDisabledTest() {
    Mails.send(greenMail, "after a disabled test");
  }
}
