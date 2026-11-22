package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@GreenMailTestViewer
@ParameterizedClass
@ValueSource(strings = {"en", "ja"})
public class RoundTripParameterizedClassKindsSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Parameter String language;

  @Test
  void welcomes() {
    send("Welcome " + language);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void reminds(int day) {
    send("Reminder " + language + " " + day);
  }

  @TestFactory
  Stream<DynamicTest> confirms() {
    return Stream.of(DynamicTest.dynamicTest("confirm", () -> send("Confirm " + language)));
  }

  @Nested
  class Settings {
    @Test
    void saves() {
      send("Saved " + language);
    }
  }

  static void send(String subject) {
    GreenMailUtil.sendTextEmail(
        "receiver@example.com",
        "sender@example.com",
        subject,
        "Sent by a parameterized class",
        greenMail.getSmtp().getServerSetup());
  }
}
