package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class RoundTripSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void sendsAMail() {
    GreenMailUtil.sendTextEmail(
        "receiver@example.com",
        "sender@example.com",
        "Round trip",
        "Sent by the extension",
        greenMail.getSmtp().getServerSetup());
  }

  @Test
  void failsWithoutMail() {
    Assertions.fail("expected by the sample");
  }
}
