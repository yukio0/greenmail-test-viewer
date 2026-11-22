package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.base.GreenMailOperations;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tests currently running with each GreenMail instance, so that mails that arrive while several
 * of them use the same instance are recorded as ambiguous rather than given to the wrong test.
 */
final class ActiveTests {

  private final Map<GreenMailOperations, Map<String, MailOwner>> testsByGreenMail =
      new IdentityHashMap<>();

  synchronized void start(GreenMailOperations greenMail, String key, MailOwner test) {
    testsByGreenMail.computeIfAbsent(greenMail, ignored -> new LinkedHashMap<>()).put(key, test);
  }

  synchronized void finish(GreenMailOperations greenMail, String key) {
    Map<String, MailOwner> tests = testsByGreenMail.get(greenMail);
    if (tests != null) {
      tests.remove(key);
      if (tests.isEmpty()) {
        testsByGreenMail.remove(greenMail);
      }
    }
  }

  /**
   * The owner of the mails just collected from a GreenMail instance: the intended owner, unless
   * other tests are using the same instance at the same time. A {@code @TestFactory} method does
   * not count as another test for its own dynamic tests.
   */
  synchronized MailOwner ownerOf(
      GreenMailOperations greenMail, MailOwner intended, String intendedKey) {
    Map<String, MailOwner> running = testsByGreenMail.get(greenMail);
    if (running == null || running.isEmpty()) {
      return intended;
    }
    List<MailOwner> candidates = new ArrayList<>();
    if (intended.phase() == MailOwner.Phase.TEST) {
      candidates.add(intended);
    }
    for (Map.Entry<String, MailOwner> test : running.entrySet()) {
      String key = test.getKey();
      boolean enclosesIntended = intendedKey != null && intendedKey.startsWith(key + "/");
      if (!key.equals(intendedKey) && !enclosesIntended) {
        candidates.add(test.getValue());
      }
    }
    if (candidates.size() == 1 && intended.phase() == MailOwner.Phase.TEST) {
      return intended;
    }
    return MailOwner.ambiguous(candidates);
  }
}
