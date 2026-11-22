package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.util.GreenMail;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
public class SharedSecondSample {

  static final GreenMail greenMail = SharedGreenMail.GREEN_MAIL;

  @Test
  void sends() {
    Mails.send(greenMail, "shared second");
  }
}
