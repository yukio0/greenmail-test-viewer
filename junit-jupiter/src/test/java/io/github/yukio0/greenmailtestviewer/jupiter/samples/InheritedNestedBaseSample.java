package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.base.GreenMailOperations;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

public abstract class InheritedNestedBaseSample {

  protected abstract GreenMailOperations greenMail();

  @Nested
  class Inner {

    @Test
    void sends() {
      Mails.send(greenMail(), "inherited nested");
    }
  }
}
