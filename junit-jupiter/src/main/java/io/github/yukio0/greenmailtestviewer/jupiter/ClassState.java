package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.base.GreenMailOperations;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailFinder.FoundGreenMail;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentest4j.TestAbortedException;

/** What the extension knows about one test class: its GreenMail instances and its failures. */
final class ClassState {

  static final String BEFORE_ALL = "beforeAll";

  static final String AFTER_ALL = "afterAll";

  private static final Logger LOGGER =
      System.getLogger(GreenMailTestViewerExtension.class.getName());

  private final Set<GreenMailOperations> greenMails =
      Collections.newSetFromMap(new IdentityHashMap<>());
  private final Set<String> loggedLocations = new HashSet<>();
  private final List<ReportedResult> keptResults = new ArrayList<>();
  private final Map<String, Throwable> stageFailures = new LinkedHashMap<>();
  private final Map<Throwable, Set<Throwable>> ownSuppressed = new IdentityHashMap<>();
  private boolean testReported;
  private boolean mailsCollected;

  synchronized void add(List<FoundGreenMail> found, boolean log) {
    for (FoundGreenMail candidate : found) {
      greenMails.add(candidate.greenMail());
      if (log && loggedLocations.add(candidate.location())) {
        LOGGER.log(
            Level.INFO,
            "Recording the emails of the GreenMail instance in {0}",
            candidate.location());
      }
    }
  }

  synchronized List<GreenMailOperations> greenMails() {
    return new ArrayList<>(greenMails);
  }

  synchronized boolean isEmpty() {
    return greenMails.isEmpty();
  }

  synchronized void mailsCollected() {
    mailsCollected = true;
  }

  synchronized boolean hasMails() {
    return mailsCollected;
  }

  synchronized void failedAt(String stage, Throwable error) {
    Throwable kept = stageFailures.get(stage);
    if (kept == null || (isAborted(kept) && !isAborted(error))) {
      stageFailures.put(stage, error);
      Set<Throwable> suppressed = Collections.newSetFromMap(new IdentityHashMap<>());
      suppressed.addAll(Arrays.asList(error.getSuppressed()));
      ownSuppressed.put(error, suppressed);
    }
  }

  synchronized void testReported() {
    testReported = true;
  }

  synchronized void keepResult(ReportedResult result) {
    keptResults.add(result);
  }

  synchronized List<ReportedResult> takeKeptResults() {
    List<ReportedResult> taken = new ArrayList<>(keptResults);
    keptResults.clear();
    return taken;
  }

  /**
   * What failed at each stage of the class, from the exception that JUnit reports for it, which
   * carries the other failures as suppressed exceptions. A failure from another extension counts as
   * a teardown failure if the setup had already ended, which is known because tests of the class
   * were reported.
   */
  synchronized Map<String, Throwable> failures(Throwable reported) {
    Set<Throwable> reportedOnes = Collections.newSetFromMap(new IdentityHashMap<>());
    reportedOnes.add(reported);
    reportedOnes.addAll(Arrays.asList(reported.getSuppressed()));
    Map<String, Throwable> failures = new LinkedHashMap<>();
    for (Map.Entry<String, Throwable> failure : stageFailures.entrySet()) {
      if (reportedOnes.contains(failure.getValue())) {
        failures.put(failure.getKey(), failure.getValue());
      }
    }
    if (!failures.containsValue(reported)) {
      boolean setUp = failures.containsKey(BEFORE_ALL) || testReported;
      Throwable abortedSetup = setUp || isAborted(reported) ? null : abortFromElsewhere(reported);
      if (abortedSetup != null) {
        Map<String, Throwable> setupAndTeardown = new LinkedHashMap<>();
        setupAndTeardown.put(BEFORE_ALL, abortedSetup);
        setupAndTeardown.put(AFTER_ALL, reported);
        return setupAndTeardown;
      }
      failures.put(setUp ? AFTER_ALL : BEFORE_ALL, reported);
    } else if (failures.get(BEFORE_ALL) == reported) {
      Throwable teardown = failures.get(AFTER_ALL);
      Throwable later = addedLater(reported);
      if (later != null && (teardown == null || (isAborted(teardown) && !isAborted(later)))) {
        failures.put(AFTER_ALL, later);
      }
    }
    return failures;
  }

  private Throwable addedLater(Throwable failure) {
    Set<Throwable> own = ownSuppressed.getOrDefault(failure, Set.of());
    Set<Throwable> remembered = Collections.newSetFromMap(new IdentityHashMap<>());
    remembered.addAll(stageFailures.values());
    Throwable abort = null;
    for (Throwable suppressed : failure.getSuppressed()) {
      if (own.contains(suppressed) || remembered.contains(suppressed)) {
        continue;
      }
      if (!isAborted(suppressed)) {
        return suppressed;
      }
      if (abort == null) {
        abort = suppressed;
      }
    }
    return abort;
  }

  private Throwable abortFromElsewhere(Throwable reported) {
    Set<Throwable> remembered = Collections.newSetFromMap(new IdentityHashMap<>());
    remembered.addAll(stageFailures.values());
    for (Throwable suppressed : reported.getSuppressed()) {
      if (isAborted(suppressed) && !remembered.contains(suppressed)) {
        return suppressed;
      }
    }
    return null;
  }

  static boolean isAborted(Throwable error) {
    return error instanceof TestAbortedException;
  }
}
