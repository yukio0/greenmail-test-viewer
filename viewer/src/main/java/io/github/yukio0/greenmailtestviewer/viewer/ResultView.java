package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRef;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What the UI shows: the tests of each module with their results and mails. Without a selected run,
 * each test shows its latest run, and runs that overlap the latest run of the module, such as
 * parallel test JVMs, count as the latest run.
 */
final class ResultView {

  static final String RESULT_CLASS_SETUP_FAILED = "classSetupFailed";

  static final String GROUP_OUTSIDE_TESTS = "outsideTests";
  static final String GROUP_OUTSIDE_CLASSES = "outsideClasses";
  static final String GROUP_AMBIGUOUS = "ambiguous";

  private static final String INVOCATION_SEGMENT = "[class-template-invocation:";

  private static final String INVOCATION_NAME_SEPARATOR = " › ";

  private static final String STAGE_BEFORE_ALL = "beforeAll";
  private static final String STAGE_AFTER_ALL = "afterAll";

  // Runs never change, so their index is built once. Weak keys, so that old runs can be garbage
  // collected.
  private static final Map<Run, RunIndex> INDEXES =
      Collections.synchronizedMap(new WeakHashMap<>());

  private final Run selectedRun;
  private final List<ModuleNode> modules;

  private ResultView(Run selectedRun, List<ModuleNode> modules) {
    this.selectedRun = selectedRun;
    this.modules = List.copyOf(modules);
  }

  static ResultView latest(List<Source> sources) {
    List<ModuleNode> modules = new ArrayList<>();
    for (Source source : sources) {
      modules.add(new ModuleBuilder(source, source.runs(), true, knownTests(source)).build());
    }
    return new ResultView(null, modules);
  }

  static ResultView ofRun(Source source, Run run) {
    return ofRun(source, run, knownTests(source));
  }

  static Map<Run, ResultView> ofEachRun(Source source) {
    Map<String, TestRef> knownTests = knownTests(source);
    Map<Run, ResultView> views = new LinkedHashMap<>();
    for (Run run : source.runs()) {
      views.put(run, ofRun(source, run, knownTests));
    }
    return views;
  }

  private static ResultView ofRun(Source source, Run run, Map<String, TestRef> knownTests) {
    return new ResultView(
        run, List.of(new ModuleBuilder(source, List.of(run), false, knownTests).build()));
  }

  private static Map<String, TestRef> knownTests(Source source) {
    Map<String, TestRef> knownTests = new LinkedHashMap<>();
    for (Run run : source.runs()) {
      for (TestRecord record : run.tests()) {
        if (record.isMethod() && record.uniqueId() != null) {
          knownTests.put(
              record.uniqueId(),
              new TestRef(
                  record.testClass(),
                  record.classDisplayName(),
                  record.testMethod(),
                  record.displayName(),
                  record.uniqueId()));
        }
      }
      for (MailRecord mail : run.mails()) {
        if (isTestMail(mail)) {
          knownTests.put(mail.test().uniqueId(), mail.test());
        }
      }
    }
    return knownTests;
  }

  Run selectedRun() {
    return selectedRun;
  }

  List<ModuleNode> modules() {
    return modules;
  }

  List<ShownMail> mails() {
    List<ShownMail> mails = new ArrayList<>();
    for (ModuleNode module : modules) {
      mails.addAll(module.mails());
    }
    mails.sort(
        Comparator.comparing((ShownMail mail) -> capturedAt(mail.mail(), mail.run()))
            .thenComparing(mail -> mail.mail().id()));
    return mails;
  }

  private static Instant capturedAt(MailRecord mail, Run run) {
    return mail.capturedAt() != null ? mail.capturedAt().instant() : run.startedAt().instant();
  }

  private static final class ModuleBuilder {
    private final Source source;

    private final List<Run> considered;

    private final boolean latest;

    private final Map<Run, Integer> order;

    private final Set<Run> current;

    private final Map<String, TestRef> knownTests;

    ModuleBuilder(
        Source source, List<Run> considered, boolean latest, Map<String, TestRef> knownTests) {
      this.source = source;
      this.considered = considered;
      this.latest = latest;
      this.order = runOrder(source);
      this.current = currentRuns(source, considered, latest);
      this.knownTests = knownTests;
    }

    ModuleNode build() {
      Map<String, ClassNode> classes = new HashMap<>();
      for (Map.Entry<String, Evidence> test : latestRunOfEachTest().entrySet()) {
        TestNode node = testNode(test.getKey(), test.getValue().run);
        classNode(
                classes, classUniqueId(node.uniqueId()), node.testClass(), node.classDisplayName())
            .addTest(node);
      }
      addClassesFromClassRecordsAndOutsideTestMails(classes);
      for (ClassNode node : classes.values()) {
        completeClassNode(node);
      }
      noteFailedTestsOfNestedClasses(classes.values());
      return new ModuleNode(
          source, sorted(classes.values()), outsideClassMails(), ambiguousMails());
    }

    private static void noteFailedTestsOfNestedClasses(Collection<ClassNode> classes) {
      for (ClassNode node : classes) {
        if (!node.setupFailed()) {
          continue;
        }
        for (ClassNode other : classes) {
          if (isInside(other.uniqueId(), node.uniqueId()) && other.failedTestCount() > 0) {
            node.noteNestedTestsFailed();
            break;
          }
        }
      }
    }

    private static Map<Run, Integer> runOrder(Source source) {
      Map<Run, Integer> order = new HashMap<>();
      for (int i = 0; i < source.runs().size(); i++) {
        order.put(source.runs().get(i), i);
      }
      return order;
    }

    private static Set<Run> currentRuns(Source source, List<Run> considered, boolean latest) {
      if (!latest || source.latestRun() == null) {
        return new HashSet<>(considered);
      }
      Instant latestStart = source.latestRun().startedAt().instant();
      Set<Run> current = new HashSet<>();
      for (Run run : considered) {
        if (!run.end().isBefore(latestStart)) {
          current.add(run);
        }
      }
      return current;
    }

    private Map<String, Evidence> latestRunOfEachTest() {
      Map<String, Evidence> tests = new HashMap<>();
      for (Run run : considered) {
        for (TestRecord record : run.tests()) {
          if (record.isMethod() && record.uniqueId() != null) {
            Evidence.offer(tests, record.uniqueId(), run, record.finishedAt(), order);
          }
          if (isSetupFailure(record)) {
            for (String test : knownTests.keySet()) {
              if (isInside(test, record.uniqueId())) {
                Evidence.offer(tests, test, run, record.finishedAt(), order);
              }
            }
          }
        }
        for (MailRecord mail : run.mails()) {
          if (isTestMail(mail)) {
            Evidence.offer(tests, mail.test().uniqueId(), run, mail.capturedAt(), order);
          }
        }
      }
      return tests;
    }

    private TestNode testNode(String uniqueId, Run run) {
      RunIndex index = index(run);
      TestRecord method = index.methodRecords.get(uniqueId);
      TestResult result = resultOf(run, uniqueId);
      List<MailRecord> mails = new ArrayList<>(index.testMails.getOrDefault(uniqueId, List.of()));
      TestRef ref;
      if (method != null) {
        ref =
            new TestRef(
                method.testClass(),
                method.classDisplayName(),
                method.testMethod(),
                method.displayName(),
                method.uniqueId());
      } else if (!mails.isEmpty()) {
        ref = mails.get(0).test();
      } else {
        ref = namedAsInFailedSetup(knownTests.get(uniqueId), index, uniqueId);
      }
      return new TestNode(
          uniqueId,
          ref == null ? null : ref.testClass(),
          ref == null ? null : ref.classDisplayName(),
          ref == null ? null : ref.testMethod(),
          ref == null ? null : ref.displayName(),
          run,
          latest && !current.contains(run),
          result == null ? null : result.result(),
          result == null ? null : result.error(),
          mails);
    }

    private void addClassesFromClassRecordsAndOutsideTestMails(Map<String, ClassNode> classes) {
      for (Run run : considered) {
        for (TestRecord record : run.tests()) {
          if (record.isClass() && record.uniqueId() != null) {
            classNode(classes, record.uniqueId(), record.testClass(), record.classDisplayName());
          }
        }
        for (MailRecord mail : run.mails()) {
          if (isOutsideTestMail(mail)) {
            classNode(
                classes,
                mail.test().uniqueId(),
                mail.test().testClass(),
                mail.test().classDisplayName());
          }
        }
      }
    }

    // JUnit numbers the invocations, so the same number may have other arguments in other runs.
    private void nameInvocationAsInRun(ClassNode node, Run run) {
      for (TestNode test : node.tests()) {
        if (test.run() == run && test.classDisplayName() != null) {
          node.nameInvocation(test.classDisplayName());
          return;
        }
      }
      RunIndex index = index(run);
      for (MailRecord mail : index.outsideTestMails.getOrDefault(node.uniqueId(), List.of())) {
        if (mail.test().classDisplayName() != null) {
          node.nameInvocation(mail.test().classDisplayName());
          return;
        }
      }
      for (TestRecord record : index.classRecords.getOrDefault(node.uniqueId(), List.of())) {
        if (record.classDisplayName() != null) {
          node.nameInvocation(record.classDisplayName());
          return;
        }
      }
    }

    private void completeClassNode(ClassNode node) {
      Map<String, Evidence> classRun = new HashMap<>();
      for (Run run : considered) {
        RunIndex index = index(run);
        Map<String, Timestamp> latest = index.latestByClass;
        if (latest.containsKey(node.uniqueId())) {
          Evidence.offer(classRun, node.uniqueId(), run, latest.get(node.uniqueId()), order);
        }
        for (TestRecord record : index.setupFailures) {
          if (isInside(node.uniqueId(), record.uniqueId())) {
            Evidence.offer(classRun, node.uniqueId(), run, record.finishedAt(), order);
          }
        }
      }
      Evidence evidence = classRun.get(node.uniqueId());
      if (evidence == null) {
        evidence = new Evidence(node.tests().get(0).run(), null, order);
      }
      node.setRun(evidence.run, latest && !current.contains(evidence.run));
      nameInvocationAsInRun(node, evidence.run);
      RunIndex index = index(evidence.run);
      for (TestRecord record : index.classRecords.getOrDefault(node.uniqueId(), List.of())) {
        if (STAGE_BEFORE_ALL.equals(record.stage())) {
          node.setSetupFailure(record);
        } else if (STAGE_AFTER_ALL.equals(record.stage())) {
          node.setTeardownFailure(record);
        }
        node.nameIfUnknown(record.testClass(), record.classDisplayName());
      }
      node.addOutsideTestMails(index.outsideTestMails.getOrDefault(node.uniqueId(), List.of()));
      node.sortTests(
          Comparator.comparing(
                  (TestNode test) ->
                      nullToEmpty(
                          test.testMethod() != null ? test.testMethod() : test.displayName()),
                  NaturalOrder.INSTANCE)
              .thenComparing(test -> nullToEmpty(test.displayName()), NaturalOrder.INSTANCE)
              .thenComparing(TestNode::uniqueId, NaturalOrder.INSTANCE));
    }

    private Map<MailRecord, Run> outsideClassMails() {
      Map<MailRecord, Run> mails = new LinkedHashMap<>();
      for (Run run : considered) {
        if (!current.contains(run)) {
          continue;
        }
        for (MailRecord mail : run.mails()) {
          if (!isTestMail(mail) && !isOutsideTestMail(mail) && !isAmbiguous(mail)) {
            mails.put(mail, run);
          }
        }
      }
      return mails;
    }

    private Map<MailRecord, Run> ambiguousMails() {
      Map<MailRecord, Run> mails = new LinkedHashMap<>();
      for (Run run : considered) {
        if (!current.contains(run)) {
          continue;
        }
        for (MailRecord mail : run.mails()) {
          if (isAmbiguous(mail)) {
            mails.put(mail, run);
          }
        }
      }
      return mails;
    }

    private static ClassNode classNode(
        Map<String, ClassNode> classes,
        String uniqueId,
        String testClass,
        String classDisplayName) {
      ClassNode node = classes.computeIfAbsent(uniqueId, ClassNode::new);
      node.nameIfUnknown(testClass, classDisplayName);
      return node;
    }

    private static List<ClassNode> sorted(Collection<ClassNode> classes) {
      List<ClassNode> sorted = new ArrayList<>(classes);
      sorted.sort(
          Comparator.comparing(
                  (ClassNode node) -> nullToEmpty(node.testClass()), NaturalOrder.INSTANCE)
              .thenComparing(ClassNode::uniqueId, NaturalOrder.INSTANCE));
      return sorted;
    }
  }

  static TestResult resultOf(Run run, String uniqueId) {
    RunIndex index = index(run);
    TestRecord method = index.methodRecords.get(uniqueId);
    if (method != null) {
      return new TestResult(method.result(), method.error());
    }
    TestRecord setupFailure = setupFailure(index, uniqueId);
    if (setupFailure == null) {
      return null;
    }
    return new TestResult(
        setupFailure.isAborted() ? "aborted" : RESULT_CLASS_SETUP_FAILED, setupFailure.error());
  }

  private static TestRecord setupFailure(RunIndex index, String uniqueId) {
    TestRecord setupFailure = null;
    for (TestRecord record : index.setupFailures) {
      if (isInside(uniqueId, record.uniqueId())) {
        setupFailure = record;
      }
    }
    return setupFailure;
  }

  /**
   * The names of a test known from other runs, with the invocation names of the run whose class
   * setup failed. Invocations inside the failed class did not run, so their names stay as known.
   */
  private static TestRef namedAsInFailedSetup(TestRef ref, RunIndex index, String uniqueId) {
    TestRecord setupFailure = setupFailure(index, uniqueId);
    if (ref == null || setupFailure == null) {
      return ref;
    }
    return new TestRef(
        ref.testClass(),
        invocationNames(setupFailure, ref.classDisplayName(), uniqueId),
        ref.testMethod(),
        ref.displayName(),
        ref.uniqueId());
  }

  private static String invocationNames(
      TestRecord setupFailure, String knownNames, String uniqueId) {
    int around = occurrences(setupFailure.uniqueId(), INVOCATION_SEGMENT);
    int all = occurrences(uniqueId, INVOCATION_SEGMENT);
    if (all == around) {
      return setupFailure.classDisplayName();
    }
    if (around == 0
        || setupFailure.classDisplayName() == null
        || knownNames == null
        || occurrences(knownNames, INVOCATION_NAME_SEPARATOR) != all - 1) {
      return knownNames;
    }
    int inside = 0;
    for (int i = 0; i < around; i++) {
      inside =
          knownNames.indexOf(INVOCATION_NAME_SEPARATOR, inside)
              + INVOCATION_NAME_SEPARATOR.length();
    }
    return setupFailure.classDisplayName()
        + INVOCATION_NAME_SEPARATOR
        + knownNames.substring(inside);
  }

  private static int occurrences(String text, String part) {
    int count = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
      count++;
    }
    return count;
  }

  private static RunIndex index(Run run) {
    return INDEXES.computeIfAbsent(run, RunIndex::new);
  }

  private static final class RunIndex {
    private final Map<String, TestRecord> methodRecords = new HashMap<>();

    private final Map<String, List<MailRecord>> testMails = new HashMap<>();

    private final List<TestRecord> setupFailures = new ArrayList<>();

    private final Map<String, List<TestRecord>> classRecords = new HashMap<>();

    private final Map<String, List<MailRecord>> outsideTestMails = new HashMap<>();

    private final Map<String, Timestamp> latestByClass = new HashMap<>();

    RunIndex(Run run) {
      for (TestRecord record : run.tests()) {
        String uniqueId = record.uniqueId();
        if (record.isMethod() && uniqueId != null) {
          methodRecords.put(uniqueId, record);
        }
        if (isSetupFailure(record)) {
          setupFailures.add(record);
        }
        if (record.isClass() && uniqueId != null) {
          classRecords.computeIfAbsent(uniqueId, key -> new ArrayList<>()).add(record);
        }
        noteTime(uniqueId, record.finishedAt());
      }
      for (MailRecord mail : run.mails()) {
        if (isTestMail(mail)) {
          testMails.computeIfAbsent(mail.test().uniqueId(), key -> new ArrayList<>()).add(mail);
        }
        if (isOutsideTestMail(mail)) {
          outsideTestMails
              .computeIfAbsent(mail.test().uniqueId(), key -> new ArrayList<>())
              .add(mail);
        }
        if (!isAmbiguous(mail)) {
          noteTime(mail.test().uniqueId(), mail.capturedAt());
        }
      }
    }

    private void noteTime(String uniqueId, Timestamp time) {
      if (uniqueId == null) {
        return;
      }
      for (int end = uniqueId.indexOf("]/["); end >= 0; end = uniqueId.indexOf("]/[", end + 1)) {
        noteLatest(uniqueId.substring(0, end + 1), time);
      }
      noteLatest(uniqueId, time);
    }

    private void noteLatest(String key, Timestamp time) {
      if (!latestByClass.containsKey(key)) {
        latestByClass.put(key, time);
        return;
      }
      Timestamp known = latestByClass.get(key);
      if (time != null && (known == null || time.instant().isAfter(known.instant()))) {
        latestByClass.put(key, time);
      }
    }
  }

  private static boolean isTestMail(MailRecord mail) {
    return MailRecord.PHASE_TEST.equals(mail.phase()) && mail.test().uniqueId() != null;
  }

  private static boolean isOutsideTestMail(MailRecord mail) {
    return MailRecord.PHASE_OUTSIDE_TEST.equals(mail.phase()) && mail.test().uniqueId() != null;
  }

  private static boolean isAmbiguous(MailRecord mail) {
    return MailRecord.PHASE_AMBIGUOUS.equals(mail.phase());
  }

  private static boolean isSetupFailure(TestRecord record) {
    return record.isClass() && STAGE_BEFORE_ALL.equals(record.stage()) && record.uniqueId() != null;
  }

  private static boolean isInside(String uniqueId, String classUniqueId) {
    return uniqueId.startsWith(classUniqueId + "/");
  }

  /**
   * The unique ID of the class that declares a test: up to its last class segment, where an
   * invocation of a class template counts as a class of its own.
   */
  static String classUniqueId(String testUniqueId) {
    String[] segments = testUniqueId.split("\\]/\\[", -1);
    int last = -1;
    for (int i = 0; i < segments.length; i++) {
      String segment =
          i == 0 ? segments[i].substring(Math.min(1, segments[i].length())) : segments[i];
      if (segment.startsWith("class:")
          || segment.startsWith("nested-class:")
          || segment.startsWith("class-template-invocation:")) {
        last = i;
      }
    }
    if (last < 0) {
      last = Math.max(0, segments.length - 2);
    }
    StringBuilder result = new StringBuilder();
    for (int i = 0; i <= last; i++) {
      if (i > 0) {
        result.append("]/[");
      }
      result.append(segments[i]);
    }
    if (last < segments.length - 1) {
      result.append(']');
    }
    return result.toString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static final class Evidence {
    private final Run run;
    private final Instant time;
    private final int runOrder;

    Evidence(Run run, Timestamp time, Map<Run, Integer> order) {
      this.run = run;
      this.time = time == null ? null : time.instant();
      this.runOrder = order.get(run);
    }

    boolean isNewerThan(Evidence other) {
      if (runOrder != other.runOrder) {
        return runOrder > other.runOrder;
      }
      if (time == null) {
        return false;
      }
      return other.time == null || time.isAfter(other.time);
    }

    static void offer(
        Map<String, Evidence> evidences,
        String key,
        Run run,
        Timestamp time,
        Map<Run, Integer> order) {
      Evidence candidate = new Evidence(run, time, order);
      Evidence existing = evidences.get(key);
      if (existing == null || candidate.isNewerThan(existing)) {
        evidences.put(key, candidate);
      }
    }
  }
}
