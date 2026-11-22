package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
public class NoGreenMailAnnotatedSample {

  @Test
  void doesNotUseGreenMail() {}
}
