package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import org.junit.jupiter.api.Test;

public class InheritedAnnotationSample extends AnnotatedBaseSample {

  @Test
  void sends() {
    Mails.send(greenMail, "inherited annotation");
  }
}
