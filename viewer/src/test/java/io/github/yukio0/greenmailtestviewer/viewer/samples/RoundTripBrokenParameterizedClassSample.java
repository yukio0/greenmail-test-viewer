package io.github.yukio0.greenmailtestviewer.viewer.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

@GreenMailTestViewer
@ParameterizedClass
@MethodSource("languages")
public class RoundTripBrokenParameterizedClassSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Parameter String language;

  static Stream<String> languages() {
    throw new IllegalStateException("cannot provide the languages");
  }

  @Test
  void welcomes() {}
}
