package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A test class as {@link ResultView} shows it, with the tests it declares. */
final class ClassNode {
  private final String uniqueId;
  private String testClass;
  private String classDisplayName;
  private Run run;
  private boolean fromEarlierRun;
  private TestRecord setupFailure;
  private TestRecord teardownFailure;
  private boolean nestedTestsFailed;
  private final List<TestNode> tests = new ArrayList<>();
  private final List<MailRecord> outsideTestMails = new ArrayList<>();

  ClassNode(String uniqueId) {
    this.uniqueId = uniqueId;
  }

  String uniqueId() {
    return uniqueId;
  }

  String testClass() {
    return testClass;
  }

  String classDisplayName() {
    return classDisplayName;
  }

  Run run() {
    return run;
  }

  boolean fromEarlierRun() {
    return fromEarlierRun;
  }

  TestRecord setupFailure() {
    return setupFailure;
  }

  TestRecord teardownFailure() {
    return teardownFailure;
  }

  List<TestNode> tests() {
    return tests;
  }

  List<MailRecord> outsideTestMails() {
    return outsideTestMails;
  }

  /**
   * The failed tests and a failed teardown. A failed setup counts only when no test of the class or
   * of its nested classes shows it. An aborted setup or teardown is not a failure.
   */
  int failureCount() {
    int failedTests = failedTestCount();
    return failedTests
        + (failed(teardownFailure) ? 1 : 0)
        + (failed(setupFailure) && failedTests == 0 && !nestedTestsFailed ? 1 : 0);
  }

  boolean setupFailed() {
    return failed(setupFailure);
  }

  int failedTestCount() {
    int failedTests = 0;
    for (TestNode test : tests) {
      if (test.failed()) {
        failedTests++;
      }
    }
    return failedTests;
  }

  void nameIfUnknown(String testClass, String classDisplayName) {
    if (this.testClass == null) {
      this.testClass = testClass;
    }
    if (this.classDisplayName == null) {
      this.classDisplayName = classDisplayName;
    }
  }

  void nameInvocation(String classDisplayName) {
    this.classDisplayName = classDisplayName;
  }

  void addTest(TestNode test) {
    tests.add(test);
  }

  void sortTests(Comparator<TestNode> order) {
    tests.sort(order);
  }

  void setRun(Run run, boolean fromEarlierRun) {
    this.run = run;
    this.fromEarlierRun = fromEarlierRun;
  }

  void setSetupFailure(TestRecord setupFailure) {
    this.setupFailure = setupFailure;
  }

  void setTeardownFailure(TestRecord teardownFailure) {
    this.teardownFailure = teardownFailure;
  }

  void addOutsideTestMails(List<MailRecord> mails) {
    outsideTestMails.addAll(mails);
  }

  void noteNestedTestsFailed() {
    nestedTestsFailed = true;
  }

  private static boolean failed(TestRecord classRecord) {
    return classRecord != null && !classRecord.isAborted();
  }
}
