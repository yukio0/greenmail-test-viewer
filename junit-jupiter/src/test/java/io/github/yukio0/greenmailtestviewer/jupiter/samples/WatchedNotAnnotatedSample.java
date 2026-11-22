package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import org.junit.jupiter.api.Test;

public class WatchedNotAnnotatedSample {

  @Test
  void sendsAndFails() {
    Mails.send(WatchedSample.Holder.GREEN_MAIL, "watched without annotation");
    throw new AssertionError("fails after sending");
  }
}
