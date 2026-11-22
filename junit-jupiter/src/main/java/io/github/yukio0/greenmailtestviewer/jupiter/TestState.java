package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.base.GreenMailOperations;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailFinder.FoundGreenMail;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class TestState {
  private final Set<GreenMailOperations> greenMails =
      Collections.newSetFromMap(new IdentityHashMap<>());

  synchronized List<GreenMailOperations> add(List<FoundGreenMail> found) {
    for (FoundGreenMail candidate : found) {
      greenMails.add(candidate.greenMail());
    }
    return new ArrayList<>(greenMails);
  }
}
