package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;

@GreenMailTestViewer
public class WatchedSample {

  public static final class Holder {
    public static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    private Holder() {}
  }

  @Test
  void sends() {
    Mails.send(Holder.GREEN_MAIL, "watched in test");
  }
}
