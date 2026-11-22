package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;

@GreenMailTestViewer
@ParameterizedClass
@ValueSource(strings = {"en", "ja"})
public class RoundTripParameterizedClassSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Parameter String language;

  @Test
  void welcomes() {
    GreenMailUtil.sendTextEmail(
        "receiver@example.com",
        "sender@example.com",
        "Welcome " + language,
        "Sent by a parameterized class",
        greenMail.getSmtp().getServerSetup());
  }
}
