package io.github.yukio0.greenmailtestviewer.viewer;

import static io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.classId;
import static io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.methodId;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultViewTest {

  private static final String SIGNUP = "com.example.SignupTest";
  private static final String ORDER = "com.example.OrderTest";

  @TempDir Path projectDirectory;

  @Test
  void aTestThatStoppedSendingMailsShowsNoMails() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    TestNode test = test(latest(), SIGNUP, "sendsWelcome");

    assertThat(test.run().name()).isEqualTo("run2");
    assertThat(test.mails()).isEmpty();
    assertThat(test.fromEarlierRun()).isFalse();
  }

  @Test
  void aNewerFailedClassSetupReplacesTheLastSuccess() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.outsideTestMail(SIGNUP, "Sent in @BeforeAll", "2026-11-01T11:00:01.000Z");
    second.classFailure(SIGNUP, classId(SIGNUP), "beforeAll", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    ResultView view = latest();
    TestNode test = test(view, SIGNUP, "sendsWelcome");
    assertThat(test.result()).isEqualTo(ResultView.RESULT_CLASS_SETUP_FAILED);
    assertThat(test.error()).contains("beforeAll failed");
    assertThat(test.run().name()).isEqualTo("run2");
    assertThat(test.mails()).isEmpty();
    ClassNode classNode = classNode(view, SIGNUP);
    assertThat(classNode.setupFailure()).isNotNull();
    assertThat(subjects(classNode.outsideTestMails())).containsExactly("Sent in @BeforeAll");
  }

  @Test
  void aFailedSetupOfAnEnclosingClassAlsoAppliesToItsNestedClasses() {
    String outer = "com.example.OuterTest";
    String nestedId = classId(outer) + "/[nested-class:Inner]";
    String nestedTestId = nestedId + "/[method:check()]";
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testWithId(nestedTestId, outer + "$Inner", "check", "passed", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classFailure(outer, classId(outer), "beforeAll", "2026-11-01T11:00:01.000Z");
    second.finish("2026-11-01T11:00:02.000Z");

    ResultView view = latest();

    ClassNode nested = classNodeById(view, nestedId);
    assertThat(nested.tests()).hasSize(1);
    assertThat(nested.tests().get(0).result()).isEqualTo(ResultView.RESULT_CLASS_SETUP_FAILED);
    assertThat(classNode(view, outer).setupFailure()).isNotNull();
    assertThat(nested.run().name()).isEqualTo("run2");
    assertThat(nested.fromEarlierRun()).isFalse();
  }

  @Test
  void aFailedSetupOfAnEnclosingClassCountsOnceWhenOnlyNestedTestsShowIt() {
    String outer = "com.example.OuterTest";
    String nestedId = classId(outer) + "/[nested-class:Inner]";
    String nestedTestId = nestedId + "/[method:check()]";
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testWithId(nestedTestId, outer + "$Inner", "check", "passed", "2026-11-01T10:00:01.000Z");
    first.outsideTestMail(
        outer + "$Inner", nestedId, "Sent in @BeforeAll of Inner", "2026-11-01T10:00:00.500Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classFailure(outer, classId(outer), "beforeAll", "2026-11-01T11:00:01.000Z");
    second.finish("2026-11-01T11:00:02.000Z");

    ResultView view = latest();

    assertThat(classNode(view, outer).failureCount()).isZero();
    assertThat(classNodeById(view, nestedId).failureCount()).isEqualTo(1);
    assertThat(view.modules().get(0).failureCount()).isEqualTo(1);
    assertThat(classNodeById(view, nestedId).outsideTestMails()).isEmpty();
    Source source = source();
    assertThat(ResultView.ofRun(source, run(source, "run2")).modules().get(0).failureCount())
        .isEqualTo(1);
  }

  @Test
  void anAbortedClassSetupAbortsItsTestsRatherThanFailingThem() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classAborted(SIGNUP, classId(SIGNUP), "beforeAll", "2026-11-01T11:00:01.000Z");
    second.finish("2026-11-01T11:00:02.000Z");

    ResultView view = latest();

    TestNode test = test(view, SIGNUP, "sendsWelcome");
    assertThat(test.result()).isEqualTo("aborted");
    assertThat(test.failed()).isFalse();
    assertThat(test.error()).contains("beforeAll aborted");
    assertThat(test.run().name()).isEqualTo("run2");
    ClassNode classNode = classNode(view, SIGNUP);
    assertThat(classNode.setupFailure()).isNotNull();
    assertThat(classNode.failureCount()).isZero();
    assertThat(view.modules().get(0).failureCount()).isZero();
  }

  @Test
  void aTeardownThatFailedAfterAnAbortedSetupIsAFailureOfItsOwn() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classAborted(SIGNUP, classId(SIGNUP), "beforeAll", "2026-11-01T11:00:01.000Z");
    second.classFailure(SIGNUP, classId(SIGNUP), "afterAll", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    ResultView view = latest();

    assertThat(test(view, SIGNUP, "sendsWelcome").result()).isEqualTo("aborted");
    ClassNode classNode = classNode(view, SIGNUP);
    assertThat(classNode.setupFailure().result()).isEqualTo("aborted");
    assertThat(classNode.teardownFailure().result()).isEqualTo("failed");
    assertThat(classNode.failureCount()).isEqualTo(1);
    assertThat(view.modules().get(0).failureCount()).isEqualTo(1);
  }

  @Test
  void anAbortedClassTeardownIsNotAFailure() {
    RecordFiles output = output("app");
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    run.classAborted(SIGNUP, classId(SIGNUP), "afterAll", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");

    ResultView view = latest();

    ClassNode classNode = classNode(view, SIGNUP);
    assertThat(classNode.teardownFailure()).isNotNull();
    assertThat(classNode.failureCount()).isZero();
    assertThat(test(view, SIGNUP, "sendsWelcome").result()).isEqualTo("passed");
  }

  @Test
  void aFailedClassTeardownOnlyMarksTheClass() {
    RecordFiles output = output("app");
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.test(SIGNUP, "rejectsDuplicates", "failed", "2026-11-01T10:00:03.000Z");
    run.classFailure(SIGNUP, classId(SIGNUP), "afterAll", "2026-11-01T10:00:04.000Z");
    run.finish("2026-11-01T10:00:05.000Z");

    ResultView view = latest();

    ClassNode classNode = classNode(view, SIGNUP);
    assertThat(classNode.teardownFailure()).isNotNull();
    assertThat(classNode.setupFailure()).isNull();
    assertThat(test(view, SIGNUP, "sendsWelcome").result()).isEqualTo("passed");
    assertThat(test(view, SIGNUP, "sendsWelcome").mails()).hasSize(1);
    assertThat(test(view, SIGNUP, "rejectsDuplicates").result()).isEqualTo("failed");
  }

  @Test
  void countsTheFailedTestsAndTheFailuresOfClassesThatNoTestShows() {
    String payment = "com.example.PaymentTest";
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(payment, "pays", "passed", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.test(SIGNUP, "sendsWelcome", "failed", "2026-11-01T11:00:01.000Z");
    second.test(SIGNUP, "sendsInvoice", "passed", "2026-11-01T11:00:02.000Z");
    second.classFailure(SIGNUP, classId(SIGNUP), "afterAll", "2026-11-01T11:00:03.000Z");
    second.classFailure(ORDER, classId(ORDER), "beforeAll", "2026-11-01T11:00:04.000Z");
    second.classFailure(payment, classId(payment), "beforeAll", "2026-11-01T11:00:05.000Z");
    second.finish("2026-11-01T11:00:06.000Z");

    ResultView view = latest();

    assertThat(classNode(view, SIGNUP).failureCount()).isEqualTo(2);
    assertThat(classNode(view, ORDER).failureCount()).isEqualTo(1);
    assertThat(test(view, payment, "pays").failed()).isTrue();
    assertThat(classNode(view, payment).failureCount()).isEqualTo(1);
    assertThat(view.modules().get(0).failureCount()).isEqualTo(4);
    Source source = source();
    assertThat(ResultView.ofRun(source, run(source, "run1")).modules().get(0).failureCount())
        .isZero();
    for (Map.Entry<Run, ResultView> each : ResultView.ofEachRun(source).entrySet()) {
      assertThat(each.getValue().modules().get(0).failureCount())
          .as(each.getKey().name())
          .isEqualTo(ResultView.ofRun(source, each.getKey()).modules().get(0).failureCount());
    }
  }

  @Test
  void namesAnInvocationOfAParameterizedClassAsTheLatestRunOfTheClassHasIt() {
    String localized = "com.example.LocalizedTest";
    for (String[] module : new String[][] {{"app", "a"}, {"web", "b"}}) {
      RecordFiles output = output(module[0]);
      RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
      first.classInvocationTest(
          localized, 1, "[1] language=en", "a", "A en", "2026-11-01T10:00:01.000Z");
      first.classInvocationTest(
          localized, 1, "[1] language=en", "b", "B en", "2026-11-01T10:00:02.000Z");
      first.finish("2026-11-01T10:00:03.000Z");
      RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
      second.classInvocationTest(
          localized, 1, "[1] language=fr", module[1], "Again", "2026-11-01T11:00:01.000Z");
      second.finish("2026-11-01T11:00:02.000Z");
    }

    List<ModuleNode> modules = latest().modules();

    assertThat(modules).hasSize(2);
    for (ModuleNode module : modules) {
      assertThat(module.classes())
          .singleElement()
          .extracting(ClassNode::classDisplayName)
          .isEqualTo("[1] language=fr");
    }
  }

  @Test
  void namesAnInvocationWhoseSetupFailedAsTheRunOfTheFailureHasIt() {
    String localized = "com.example.LocalizedTest";
    String invocation =
        "[engine:junit-jupiter]/[class-template:" + localized + "]/[class-template-invocation:#1]";
    String outer = "com.example.OuterTest";
    String nestedInvocation =
        classId(outer) + "/[nested-class-template:Inner]/[class-template-invocation:#1]";
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.classInvocationTest(
        localized, 1, "[1] language=en", "welcomes", "Welcome en", "2026-11-01T10:00:01.000Z");
    first.invocationTest(
        nestedInvocation,
        outer + "$Inner",
        "[1] language=en",
        "checks",
        "Checked en",
        "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classFailure(
        localized, "[1] language=fr", invocation, "beforeAll", "2026-11-01T11:00:01.000Z");
    second.classFailure(outer, classId(outer), "beforeAll", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    ResultView afterFailure = latest();
    assertThat(classNodeById(afterFailure, invocation))
        .satisfies(node -> {
          assertThat(node.classDisplayName()).isEqualTo("[1] language=fr");
          assertThat(node.tests())
              .singleElement()
              .extracting(TestNode::result, TestNode::classDisplayName)
              .containsExactly(ResultView.RESULT_CLASS_SETUP_FAILED, "[1] language=fr");
        });
    assertThat(classNodeById(afterFailure, nestedInvocation))
        .satisfies(node -> {
          assertThat(node.classDisplayName()).isEqualTo("[1] language=en");
          assertThat(node.tests())
              .singleElement()
              .extracting(TestNode::result, TestNode::classDisplayName)
              .containsExactly(ResultView.RESULT_CLASS_SETUP_FAILED, "[1] language=en");
        });

    RunFiles third = output.run("run3", "2026-11-01T12:00:00.000Z");
    third.classInvocationTest(
        localized, 1, "[1] language=de", "welcomes", "Welcome de", "2026-11-01T12:00:01.000Z");
    third.finish("2026-11-01T12:00:02.000Z");

    Source source = source();
    ClassNode failed = classNodeById(ResultView.ofRun(source, run(source, "run2")), invocation);
    assertThat(failed.classDisplayName()).isEqualTo("[1] language=fr");
    assertThat(failed.tests())
        .singleElement()
        .extracting(TestNode::classDisplayName)
        .isEqualTo("[1] language=fr");
    assertThat(classNodeById(latest(), invocation).classDisplayName()).isEqualTo("[1] language=de");
  }

  @Test
  void namesAnInvocationNestedInOneWhoseSetupFailedFromBothRuns() {
    String localized = "com.example.LocalizedTest";
    String invocation =
        "[engine:junit-jupiter]/[class-template:" + localized + "]/[class-template-invocation:#1]";
    String nested = invocation + "/[nested-class-template:Sizes]/[class-template-invocation:#1]";
    String odd = "com.example.OddTest";
    String oddInvocation =
        "[engine:junit-jupiter]/[class-template:" + odd + "]/[class-template-invocation:#1]";
    String oddNested =
        oddInvocation + "/[nested-class-template:Sizes]/[class-template-invocation:#1]";
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.invocationTest(
        nested,
        localized + "$Sizes",
        "[1] language=en › [1] size=10",
        "fits",
        "Fits",
        "2026-11-01T10:00:01.000Z");
    first.invocationTest(
        oddNested,
        odd + "$Sizes",
        "[1] a › b › [1] size=10",
        "fits",
        "Fits odd",
        "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classFailure(
        localized, "[1] language=fr", invocation, "beforeAll", "2026-11-01T11:00:01.000Z");
    second.classFailure(odd, "[1] c › d", oddInvocation, "beforeAll", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    ResultView view = latest();

    assertThat(classNodeById(view, nested))
        .satisfies(node -> {
          assertThat(node.run().name()).isEqualTo("run2");
          assertThat(node.classDisplayName()).isEqualTo("[1] language=fr › [1] size=10");
          assertThat(node.tests())
              .singleElement()
              .extracting(TestNode::classDisplayName)
              .isEqualTo("[1] language=fr › [1] size=10");
        });
    assertThat(classNodeById(view, oddNested).classDisplayName())
        .isEqualTo("[1] a › b › [1] size=10");
  }

  @Test
  void testsThatDidNotRunInTheLatestRunAreFromAnEarlierRun() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    first.test(ORDER, "sendsConfirmation", "passed", "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T11:00:01.000Z");
    second.finish("2026-11-01T11:00:02.000Z");

    ResultView view = latest();

    assertThat(test(view, SIGNUP, "sendsWelcome").fromEarlierRun()).isFalse();
    TestNode order = test(view, ORDER, "sendsConfirmation");
    assertThat(order.fromEarlierRun()).isTrue();
    assertThat(order.run().name()).isEqualTo("run1");
    assertThat(order.result()).isEqualTo("passed");
    assertThat(classNode(view, ORDER).fromEarlierRun()).isTrue();
  }

  @Test
  void runsThatOverlapTheLatestRunCountAsTheLatestRun() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:05.000Z");
    first.finish("2026-11-01T10:00:10.000Z");
    RunFiles second = output.run("run2", "2026-11-01T10:00:01.000Z");
    second.test(ORDER, "sendsConfirmation", "passed", "2026-11-01T10:00:06.000Z");
    second.finish("2026-11-01T10:00:08.000Z");

    ResultView view = latest();

    assertThat(test(view, SIGNUP, "sendsWelcome").fromEarlierRun()).isFalse();
    assertThat(test(view, ORDER, "sendsConfirmation").fromEarlierRun()).isFalse();
  }

  @Test
  void aSelectedRunShowsOnlyItsOwnResults() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "First welcome", "2026-11-01T10:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    first.test(ORDER, "sendsConfirmation", "passed", "2026-11-01T10:00:03.000Z");
    first.finish("2026-11-01T10:00:04.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.testMail(SIGNUP, "sendsWelcome", "Second welcome", "2026-11-01T11:00:01.000Z");
    second.test(SIGNUP, "sendsWelcome", "failed", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    Source source = source();
    ResultView firstView = ResultView.ofRun(source, run(source, "run1"));
    ResultView secondView = ResultView.ofRun(source, run(source, "run2"));

    assertThat(test(firstView, SIGNUP, "sendsWelcome").result()).isEqualTo("passed");
    assertThat(subjects(test(firstView, SIGNUP, "sendsWelcome").mails()))
        .containsExactly("First welcome");
    assertThat(test(firstView, ORDER, "sendsConfirmation").fromEarlierRun()).isFalse();
    assertThat(test(secondView, SIGNUP, "sendsWelcome").result()).isEqualTo("failed");
    assertThat(classNames(secondView)).containsExactly(SIGNUP);
  }

  @Test
  void aTestShowsTheNameItHadInTheShownRun() {
    String invocation =
        classId(SIGNUP) + "/[test-template:sends(String)]/[test-template-invocation:#1]";
    RecordFiles output = output("app");
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testWithId(
            invocation,
            SIGNUP,
            "sends",
            "[1] old@example.com",
            "passed",
            "2026-11-01T10:00:01.000Z")
        .finish("2026-11-01T10:00:02.000Z");
    output
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testWithId(
            invocation,
            SIGNUP,
            "sends",
            "[1] new@example.com",
            "passed",
            "2026-11-01T11:00:01.000Z")
        .finish("2026-11-01T11:00:02.000Z");

    Source source = source();
    TestNode inFirstRun =
        classNode(ResultView.ofRun(source, run(source, "run1")), SIGNUP).tests().get(0);
    TestNode latest = classNode(latest(), SIGNUP).tests().get(0);

    assertThat(inFirstRun.displayName()).isEqualTo("[1] old@example.com");
    assertThat(latest.displayName()).isEqualTo("[1] new@example.com");
  }

  @Test
  void aSelectedRunWithAFailedClassSetupShowsTheKnownTestsOfTheClass() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.classFailure(SIGNUP, classId(SIGNUP), "beforeAll", "2026-11-01T11:00:01.000Z");
    second.finish("2026-11-01T11:00:02.000Z");

    Source source = source();
    TestNode test = test(ResultView.ofRun(source, run(source, "run2")), SIGNUP, "sendsWelcome");

    assertThat(test.result()).isEqualTo(ResultView.RESULT_CLASS_SETUP_FAILED);
  }

  @Test
  void theSameTestInTwoModulesIsNotMixedUp() {
    RunFiles web = output("web").run("run1", "2026-11-01T10:00:00.000Z");
    web.testMail(SIGNUP, "sendsWelcome", "Web welcome", "2026-11-01T10:00:01.000Z");
    web.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    RunFiles batch = output("batch").run("run1", "2026-11-01T10:00:00.000Z");
    batch.testMail(SIGNUP, "sendsWelcome", "Batch welcome", "2026-11-01T10:00:01.000Z");
    batch.test(SIGNUP, "sendsWelcome", "failed", "2026-11-01T10:00:02.000Z");

    ResultView view = latest();

    assertThat(view.modules())
        .extracting(module -> module.source().name())
        .containsExactly("batch", "web");
    ModuleNode batchModule = view.modules().get(0);
    ModuleNode webModule = view.modules().get(1);
    TestNode batchTest = batchModule.classes().get(0).tests().get(0);
    TestNode webTest = webModule.classes().get(0).tests().get(0);
    assertThat(batchTest.result()).isEqualTo("failed");
    assertThat(subjects(batchTest.mails())).containsExactly("Batch welcome");
    assertThat(webTest.result()).isEqualTo("passed");
    assertThat(subjects(webTest.mails())).containsExactly("Web welcome");
  }

  @Test
  void ambiguousMailsAreShownWithTheModuleAndTheirCandidates() {
    RunFiles run = output("app").run("run1", "2026-11-01T10:00:00.000Z");
    run.ambiguousMail(
        "Sent while two tests ran",
        "2026-11-01T10:00:01.000Z",
        new String[] {SIGNUP, "first"},
        new String[] {SIGNUP, "second"});
    run.test(SIGNUP, "first", "passed", "2026-11-01T10:00:02.000Z");
    run.test(SIGNUP, "second", "passed", "2026-11-01T10:00:02.000Z");

    ResultView view = latest();

    List<MailRecord> ambiguous = view.modules().get(0).ambiguousMails();
    assertThat(subjects(ambiguous)).containsExactly("Sent while two tests ran");
    assertThat(ambiguous.get(0).candidates())
        .extracting(candidate -> candidate.testMethod())
        .containsExactly("first", "second");
    ShownMail shown = view.mails().get(0);
    assertThat(shown.group()).isEqualTo(ResultView.GROUP_AMBIGUOUS);
    assertThat(shown.test()).isNull();
  }

  @Test
  void outsideTestMailsAreFromTheLatestRunOfTheClassAndOutsideClassMailsFromTheLatestRun() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.outsideClassMail("Old outside classes", "2026-11-01T10:00:00.500Z");
    first.outsideTestMail(SIGNUP, "Old outside tests", "2026-11-01T10:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    first.outsideTestMail(ORDER, "Order outside tests", "2026-11-01T10:00:03.000Z");
    first.test(ORDER, "sendsConfirmation", "passed", "2026-11-01T10:00:04.000Z");
    first.finish("2026-11-01T10:00:05.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.outsideClassMail("New outside classes", "2026-11-01T11:00:00.500Z");
    second.outsideTestMail(SIGNUP, "New outside tests", "2026-11-01T11:00:01.000Z");
    second.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T11:00:02.000Z");
    second.finish("2026-11-01T11:00:03.000Z");

    ResultView view = latest();

    assertThat(subjects(classNode(view, SIGNUP).outsideTestMails()))
        .containsExactly("New outside tests");
    assertThat(subjects(classNode(view, ORDER).outsideTestMails()))
        .containsExactly("Order outside tests");
    assertThat(subjects(view.modules().get(0).outsideClassMails()))
        .containsExactly("New outside classes");
  }

  @Test
  void aTestThatIsStillRunningShowsItsNewMailsWithoutAResult() {
    RecordFiles output = output("app");
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Old welcome", "2026-11-01T10:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    first.finish("2026-11-01T10:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.testMail(SIGNUP, "sendsWelcome", "New welcome", "2026-11-01T11:00:01.000Z");

    TestNode test = test(latest(), SIGNUP, "sendsWelcome");

    assertThat(test.result()).isNull();
    assertThat(subjects(test.mails())).containsExactly("New welcome");
  }

  @Test
  void invocationsOfParameterizedTestsAreSortedByNumber() {
    String template = classId(SIGNUP) + "/[test-template:sends(int)]";
    RunFiles run = output("app").run("run1", "2026-11-01T10:00:00.000Z");
    for (int i : new int[] {10, 2, 1}) {
      run.testWithId(
          template + "/[test-template-invocation:#" + i + "]",
          SIGNUP,
          "sends",
          "passed",
          "2026-11-01T10:00:01.000Z");
    }

    List<String> ids =
        classNode(latest(), SIGNUP).tests().stream()
            .map(TestNode::uniqueId)
            .collect(Collectors.toList());

    assertThat(ids)
        .containsExactly(
            template + "/[test-template-invocation:#1]",
            template + "/[test-template-invocation:#2]",
            template + "/[test-template-invocation:#10]");
  }

  @Test
  void findsTheClassThatDeclaresATest() {
    assertThat(ResultView.classUniqueId(methodId(SIGNUP, "sends"))).isEqualTo(classId(SIGNUP));
    assertThat(
            ResultView.classUniqueId(
                classId(SIGNUP) + "/[nested-class:Inner]/[method:check(java.lang.String)]"))
        .isEqualTo(classId(SIGNUP) + "/[nested-class:Inner]");
    assertThat(
            ResultView.classUniqueId(
                classId(SIGNUP) + "/[test-template:sends(int)]/[test-template-invocation:#1]"))
        .isEqualTo(classId(SIGNUP));
    assertThat(ResultView.classUniqueId(classId(SIGNUP))).isEqualTo(classId(SIGNUP));
  }

  @Test
  void findsTheInvocationOfAParameterizedClassThatDeclaresATest() {
    String invocation =
        "[engine:junit-jupiter]/[class-template:com.example.LocalizedTest]"
            + "/[class-template-invocation:#1]";
    String nestedInvocation =
        classId(SIGNUP) + "/[nested-class-template:Inner]/[class-template-invocation:#2]";

    assertThat(ResultView.classUniqueId(invocation + "/[method:welcomes()]")).isEqualTo(invocation);
    assertThat(
            ResultView.classUniqueId(
                invocation + "/[test-template:sends(int)]/[test-template-invocation:#1]"))
        .isEqualTo(invocation);
    assertThat(ResultView.classUniqueId(invocation + "/[test-factory:signup()]/[dynamic-test:#1]"))
        .isEqualTo(invocation);
    assertThat(ResultView.classUniqueId(invocation + "/[nested-class:Inner]/[method:check()]"))
        .isEqualTo(invocation + "/[nested-class:Inner]");
    assertThat(ResultView.classUniqueId(nestedInvocation + "/[method:check()]"))
        .isEqualTo(nestedInvocation);
  }

  private RecordFiles output(String module) {
    return RecordFiles.create(
        projectDirectory.resolve(module).resolve("build/greenmail-test-viewer"));
  }

  private ResultView latest() {
    return ResultView.latest(snapshot().sources());
  }

  private RecordStore.Snapshot snapshot() {
    RecordStore store = new RecordStore(List.of(projectDirectory));
    store.refresh(true);
    return store.snapshot();
  }

  private Source source() {
    return snapshot().sources().get(0);
  }

  private static Run run(Source source, String name) {
    return source.runs().stream().filter(run -> run.name().equals(name)).findFirst().orElseThrow();
  }

  private static TestNode test(ResultView view, String testClass, String method) {
    return classNode(view, testClass).tests().stream()
        .filter(test -> method.equals(test.testMethod()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no test " + testClass + "#" + method));
  }

  private static ClassNode classNode(ResultView view, String testClass) {
    return classNodeById(view, classId(testClass));
  }

  private static ClassNode classNodeById(ResultView view, String uniqueId) {
    return view.modules().stream()
        .flatMap(module -> module.classes().stream())
        .filter(node -> node.uniqueId().equals(uniqueId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no class " + uniqueId));
  }

  private static List<String> classNames(ResultView view) {
    return view.modules().stream()
        .flatMap(module -> module.classes().stream())
        .map(ClassNode::testClass)
        .collect(Collectors.toList());
  }

  private static List<String> subjects(List<MailRecord> mails) {
    return mails.stream().map(mail -> mail.summary().subject()).collect(Collectors.toList());
  }
}
