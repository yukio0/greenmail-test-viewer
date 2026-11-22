package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class PerClassExtensionSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

  @BeforeAll
  static void sendSetupMail() {
    Mails.send(greenMail, "per-class before all");
  }

  @Test
  void first() {
    Mails.send(greenMail, "per-class first");
  }

  @Test
  void second() {
    Mails.send(greenMail, "per-class second");
  }
}
