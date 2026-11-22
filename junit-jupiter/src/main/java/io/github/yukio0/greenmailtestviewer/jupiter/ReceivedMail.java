package io.github.yukio0.greenmailtestviewer.jupiter;

import java.util.Collections;
import java.util.List;

final class ReceivedMail {

  private final byte[] rawMime;
  private final List<String> deliveredTo;

  ReceivedMail(byte[] rawMime, List<String> deliveredTo) {
    this.rawMime = rawMime;
    this.deliveredTo = Collections.unmodifiableList(deliveredTo);
  }

  byte[] rawMime() {
    return rawMime;
  }

  List<String> deliveredTo() {
    return deliveredTo;
  }
}
