package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import java.util.List;

final class TestNode {
  private final String uniqueId;
  private final String testClass;
  private final String classDisplayName;
  private final String testMethod;
  private final String displayName;
  private final Run run;
  private final boolean fromEarlierRun;
  private final String result;
  private final String error;
  private final List<MailRecord> mails;

  TestNode(
      String uniqueId,
      String testClass,
      String classDisplayName,
      String testMethod,
      String displayName,
      Run run,
      boolean fromEarlierRun,
      String result,
      String error,
      List<MailRecord> mails) {
    this.uniqueId = uniqueId;
    this.testClass = testClass;
    this.classDisplayName = classDisplayName;
    this.testMethod = testMethod;
    this.displayName = displayName;
    this.run = run;
    this.fromEarlierRun = fromEarlierRun;
    this.result = result;
    this.error = error;
    this.mails = List.copyOf(mails);
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

  String testMethod() {
    return testMethod;
  }

  String displayName() {
    return displayName;
  }

  Run run() {
    return run;
  }

  boolean fromEarlierRun() {
    return fromEarlierRun;
  }

  String result() {
    return result;
  }

  String error() {
    return error;
  }

  boolean failed() {
    return "failed".equals(result) || ResultView.RESULT_CLASS_SETUP_FAILED.equals(result);
  }

  List<MailRecord> mails() {
    return mails;
  }
}
