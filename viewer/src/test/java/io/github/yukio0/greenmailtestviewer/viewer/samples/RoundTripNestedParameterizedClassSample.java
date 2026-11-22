package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

@GreenMailTestViewer
public class RoundTripNestedParameterizedClassSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void signsUp() {
    send("Signed up");
  }

  @Nested
  @ParameterizedClass
  @ValueSource(strings = {"en", "ja"})
  class Localized {

    @Parameter String language;

    @Test
    void welcomes() {
      send("Welcome " + language);
    }
  }

  static void send(String subject) {
    GreenMailUtil.sendTextEmail(
        "receiver@example.com",
        "sender@example.com",
        subject,
        "Sent by a nested parameterized class",
        greenMail.getSmtp().getServerSetup());
  }
}
