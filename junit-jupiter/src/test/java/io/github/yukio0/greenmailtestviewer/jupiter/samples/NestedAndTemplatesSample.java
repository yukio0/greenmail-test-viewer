package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@GreenMailTestViewer
public class NestedAndTemplatesSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @ParameterizedTest
  @ValueSource(strings = {"a", "b"})
  void parameterized(String value) {
    Mails.send(greenMail, "parameterized " + value);
  }

  @RepeatedTest(2)
  void repeated(RepetitionInfo repetitionInfo) {
    Mails.send(greenMail, "repeated " + repetitionInfo.getCurrentRepetition());
  }

  @Nested
  class Inner {

    @Test
    void nested() {
      Mails.send(greenMail, "nested");
    }
  }
}
