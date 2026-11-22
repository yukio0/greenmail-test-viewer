package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class RoundTripDynamicSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @TestFactory
  Stream<DynamicTest> signup() {
    return Stream.of(
        DynamicTest.dynamicTest("welcomes", () -> send("Welcome")),
        DynamicTest.dynamicTest(
            "confirms",
            () -> {
              send("Confirm");
              Assertions.fail("expected by the sample");
            }));
  }

  private static void send(String subject) {
    GreenMailUtil.sendTextEmail(
        "receiver@example.com",
        "sender@example.com",
        subject,
        "Sent by a dynamic test",
        greenMail.getSmtp().getServerSetup());
  }
}
