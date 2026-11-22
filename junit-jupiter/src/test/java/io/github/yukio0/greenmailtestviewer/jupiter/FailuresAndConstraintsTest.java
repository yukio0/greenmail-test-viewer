package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yukio0.greenmailtestviewer.jupiter.RecordedRun.RecordedMail;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.ConstraintSamples;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.DynamicTestsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.LifecycleFailureSamples;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.Mails;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.OverlappingExecutionSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.PerMethodExtensionSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.RecipientsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.RecordingFailureSamples;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SameTestInTwoExecutionsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SharedGreenMail;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.testkit.engine.EngineTestKit;

class FailuresAndConstraintsTest {

  @TempDir Path outputDirectory;

  @Nested
  class LifecycleFailures {

    @Test
    void greenMailCreatedByAFailingBeforeEachIsFoundWhenHandlingTheFailure() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.CreatedInBeforeEachFails.class);

      RecordedMail mail = run.mail("created in before each");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testMethod()).isEqualTo("neverRuns");
      assertThat(run.test("neverRuns").path("result").asText()).isEqualTo("failed");
    }

    @Test
    void greenMailInAnInstanceFieldIsFoundInBeforeAllWithOneInstancePerClass() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.PerClassInstanceBeforeAllFails.class);

      assertThat(run.mail("per class instance before all").phase()).isEqualTo("outsideTest");
      assertThat(
              run.classFailure(LifecycleFailureSamples.PerClassInstanceBeforeAllFails.class)
                  .path("stage")
                  .asText())
          .isEqualTo("beforeAll");
    }

    @Test
    void failingBeforeAllIsRecordedForTheClassAndItsMailIsOutsideOfTests() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.BeforeAllFails.class);

      JsonNode failure = run.classFailure(LifecycleFailureSamples.BeforeAllFails.class);
      assertThat(failure.path("result").asText()).isEqualTo("failed");
      assertThat(failure.path("stage").asText()).isEqualTo("beforeAll");
      assertThat(failure.path("error").asText())
          .isEqualTo("java.lang.IllegalStateException: before all failed");
      assertThat(failure.path("uniqueId").asText()).contains("BeforeAllFails]");
      assertThat(failure.has("testMethod")).isFalse();

      RecordedMail mail = run.mail("before all failure");
      assertThat(mail.phase()).isEqualTo("outsideTest");
      assertThat(mail.testClass())
          .isEqualTo(LifecycleFailureSamples.BeforeAllFails.class.getName());
      assertThat(run.tests()).hasSize(1);
    }

    @Test
    void mailOfFailingBeforeEachBelongsToTheTest() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.BeforeEachFails.class);

      RecordedMail mail = run.mail("before each failure");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testMethod()).isEqualTo("neverRuns");
      JsonNode test = run.test("neverRuns");
      assertThat(test.path("result").asText()).isEqualTo("failed");
      assertThat(test.path("error").asText())
          .isEqualTo("java.lang.IllegalStateException: before each failed");
      assertThat(run.classFailures()).isEmpty();
    }

    @Test
    void mailSentInAfterEachIsRecordedBeforeGreenMailExtensionStopsGreenMail() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.AfterEachSends.class);

      RecordedMail mail = run.mail("after each mail");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testMethod()).isEqualTo("passes");
      assertThat(run.test("passes").path("result").asText()).isEqualTo("passed");
    }

    @Test
    void mailOfFailingAfterEachBelongsToTheTest() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.AfterEachFails.class);

      assertThat(run.mail("after each failure").testMethod()).isEqualTo("passes");
      JsonNode test = run.test("passes");
      assertThat(test.path("result").asText()).isEqualTo("failed");
      assertThat(test.path("error").asText())
          .isEqualTo("java.lang.IllegalStateException: after each failed");
    }

    @Test
    void anAssumptionThatDoesNotHoldInBeforeAllIsRecordedAsAborted() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.BeforeAllAborts.class);

      JsonNode record = run.classFailure(LifecycleFailureSamples.BeforeAllAborts.class);
      assertThat(record.path("result").asText()).isEqualTo("aborted");
      assertThat(record.path("stage").asText()).isEqualTo("beforeAll");
      assertThat(record.path("error").asText())
          .startsWith("org.opentest4j.TestAbortedException")
          .contains("no mail server here");
      assertThat(run.mail("before all abort").phase()).isEqualTo("outsideTest");
    }

    @Test
    void anAssumptionThatDoesNotHoldInAfterAllIsRecordedAsAborted() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.AfterAllAborts.class);

      JsonNode record = run.classFailure(LifecycleFailureSamples.AfterAllAborts.class);
      assertThat(record.path("result").asText()).isEqualTo("aborted");
      assertThat(record.path("stage").asText()).isEqualTo("afterAll");
      assertThat(run.test("passes").path("result").asText()).isEqualTo("passed");
    }

    @Test
    void aTeardownThatFailsAfterAnAbortedSetupIsRecordedAtItsOwnStage() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.BeforeAllAbortsAndAfterAllFails.class);

      Class<?> sample = LifecycleFailureSamples.BeforeAllAbortsAndAfterAllFails.class;
      JsonNode setup = run.classFailure(sample, "beforeAll");
      assertThat(setup.path("result").asText()).isEqualTo("aborted");
      assertThat(setup.path("error").asText())
          .startsWith("org.opentest4j.TestAbortedException")
          .contains("no mail server here");
      JsonNode teardown = run.classFailure(sample, "afterAll");
      assertThat(teardown.path("result").asText()).isEqualTo("failed");
      assertThat(teardown.path("error").asText())
          .startsWith("java.lang.IllegalStateException: after all failed after the abort");
      assertThat(run.classFailures()).hasSize(2);
    }

    @Test
    void aTeardownOfAnotherExtensionThatFailsAfterAnAbortedSetupIsATeardownFailure()
        throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.BeforeAllAbortsAndOtherExtensionAfterAllFails.class);

      Class<?> sample = LifecycleFailureSamples.BeforeAllAbortsAndOtherExtensionAfterAllFails.class;
      assertThat(run.classFailure(sample, "beforeAll").path("result").asText())
          .isEqualTo("aborted");
      JsonNode teardown = run.classFailure(sample, "afterAll");
      assertThat(teardown.path("result").asText()).isEqualTo("failed");
      assertThat(teardown.path("error").asText()).contains("other extension after all failed");
    }

    @Test
    void aTeardownOfAnotherExtensionThatFailsAfterItsSetupAbortedIsATeardownFailure()
        throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.OtherExtensionBeforeAllAbortsAndAfterAllFails.class);

      Class<?> sample = LifecycleFailureSamples.OtherExtensionBeforeAllAbortsAndAfterAllFails.class;
      JsonNode setup = run.classFailure(sample, "beforeAll");
      assertThat(setup.path("result").asText()).isEqualTo("aborted");
      assertThat(setup.path("error").asText()).contains("other extension found no mail server");
      JsonNode teardown = run.classFailure(sample, "afterAll");
      assertThat(teardown.path("result").asText()).isEqualTo("failed");
      assertThat(teardown.path("error").asText()).contains("other extension after all failed");
      assertThat(run.classFailures()).hasSize(2);
    }

    @Test
    void aSetupAndATeardownThatBothFailAreBothRecorded() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.BeforeAllAndAfterAllFail.class);

      Class<?> sample = LifecycleFailureSamples.BeforeAllAndAfterAllFail.class;
      assertThat(run.classFailure(sample, "beforeAll").path("error").asText())
          .startsWith("java.lang.IllegalStateException: before all failed");
      assertThat(run.classFailure(sample, "afterAll").path("error").asText())
          .startsWith("java.lang.IllegalStateException: after all failed");
    }

    @Test
    void aTeardownOfAnotherExtensionThatFailsAfterTheSetupFailedIsRecordedToo() throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.BeforeAllAndOtherExtensionAfterAllFail.class);

      Class<?> sample = LifecycleFailureSamples.BeforeAllAndOtherExtensionAfterAllFail.class;
      assertThat(run.classFailure(sample, "beforeAll").path("error").asText())
          .startsWith("java.lang.IllegalStateException: before all failed");
      JsonNode teardown = run.classFailure(sample, "afterAll");
      assertThat(teardown.path("result").asText()).isEqualTo("failed");
      assertThat(teardown.path("error").asText()).contains("other extension after all failed");
      assertThat(run.classFailures()).hasSize(2);
    }

    @Test
    void aTeardownFailureIsRecordedRatherThanAnAbortOfTheSameStage() throws IOException {
      RecordedRun run =
          runSamples(
              LifecycleFailureSamples.BeforeAllFailsAfterAllAbortsAndOtherExtensionAfterAllFails
                  .class);

      Class<?> sample =
          LifecycleFailureSamples.BeforeAllFailsAfterAllAbortsAndOtherExtensionAfterAllFails.class;
      assertThat(run.classFailure(sample, "beforeAll").path("error").asText())
          .startsWith("java.lang.IllegalStateException: before all failed");
      JsonNode teardown = run.classFailure(sample, "afterAll");
      assertThat(teardown.path("result").asText()).isEqualTo("failed");
      assertThat(teardown.path("error").asText()).contains("other extension after all failed");
      assertThat(run.classFailures()).hasSize(2);
    }

    @Test
    void aSuppressedExceptionOfTheSetupItselfIsNotATeardownFailure() throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.BeforeAllFailsWithItsOwnSuppressed.class);

      Class<?> sample = LifecycleFailureSamples.BeforeAllFailsWithItsOwnSuppressed.class;
      assertThat(run.classFailure(sample, "beforeAll").path("error").asText())
          .startsWith("java.lang.IllegalStateException: before all failed");
      assertThat(run.classFailures()).hasSize(1);
    }

    @Test
    void failingAfterAllKeepsTheTestResults() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.AfterAllFails.class);

      JsonNode failure = run.classFailure(LifecycleFailureSamples.AfterAllFails.class);
      assertThat(failure.path("stage").asText()).isEqualTo("afterAll");
      assertThat(run.test("passes").path("result").asText()).isEqualTo("passed");
      assertThat(run.mail("after all test").testMethod()).isEqualTo("passes");
      assertThat(run.mail("after all failure").phase()).isEqualTo("outsideTest");
    }

    @Test
    void failingAfterAllOfAClassWithOnlyDisabledTestsIsNotTakenForASetupFailure()
        throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.AllDisabledAfterAllFails.class);

      assertThat(
              run.classFailure(LifecycleFailureSamples.AllDisabledAfterAllFails.class)
                  .path("stage")
                  .asText())
          .isEqualTo("afterAll");
    }

    @Test
    void failingBeforeAllOfAnotherExtensionIsASetupFailure() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.OtherExtensionBeforeAllFails.class);

      JsonNode failure =
          run.classFailure(LifecycleFailureSamples.OtherExtensionBeforeAllFails.class);
      assertThat(failure.path("stage").asText()).isEqualTo("beforeAll");
      assertThat(failure.path("error").asText()).contains("other extension before all failed");
    }

    @Test
    void failingAfterAllOfAnotherExtensionIsATeardownFailure() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.OtherExtensionAfterAllFails.class);

      assertThat(
              run.classFailure(LifecycleFailureSamples.OtherExtensionAfterAllFails.class)
                  .path("stage")
                  .asText())
          .isEqualTo("afterAll");
      assertThat(run.test("passes").path("result").asText()).isEqualTo("passed");
    }

    @Test
    void disabledTestsTellATeardownFailureOfAnotherExtensionFromASetupFailure() throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.AllDisabledOtherExtensionAfterAllFails.class);

      assertThat(
              run.classFailure(LifecycleFailureSamples.AllDisabledOtherExtensionAfterAllFails.class)
                  .path("stage")
                  .asText())
          .isEqualTo("afterAll");
      assertThat(run.test("disabled").path("result").asText()).isEqualTo("disabled");
    }

    @Test
    void failingBeforeAllOfANestedClassIsRecordedForTheNestedClass() throws IOException {
      RecordedRun run = runSamples(LifecycleFailureSamples.NestedBeforeAllFails.class);

      JsonNode failure = run.classFailure(LifecycleFailureSamples.NestedBeforeAllFails.Inner.class);
      assertThat(failure.path("stage").asText()).isEqualTo("beforeAll");
      assertThat(failure.path("uniqueId").asText()).contains("[nested-class:Inner]");
      assertThat(run.classFailures()).hasSize(1);
      assertThat(run.test("outerPasses").path("result").asText()).isEqualTo("passed");
    }

    @Test
    void aNestedClassAndItsEnclosingClassFailAtTheirOwnStages() throws IOException {
      RecordedRun run =
          runSamples(LifecycleFailureSamples.NestedBeforeAllAndOuterAfterAllFail.class);

      assertThat(
              run.classFailure(
                      LifecycleFailureSamples.NestedBeforeAllAndOuterAfterAllFail.Inner.class)
                  .path("stage")
                  .asText())
          .isEqualTo("beforeAll");
      JsonNode outer =
          run.classFailure(LifecycleFailureSamples.NestedBeforeAllAndOuterAfterAllFail.class);
      assertThat(outer.path("stage").asText()).isEqualTo("afterAll");
      assertThat(outer.path("error").asText()).contains("outer after all failed");
    }
  }

  @Nested
  class ParallelTests {

    @Test
    void theSameTestInTwoExecutionsAtTheSameTimeWithOneGreenMailIsAmbiguous() throws Exception {
      SameTestInTwoExecutionsSample.GREEN_MAIL.start();
      SameTestInTwoExecutionsSample.bothSent = new CyclicBarrier(2);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        List<Future<?>> executions = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
          executions.add(
              executor.submit(
                  () -> run(Collections.emptyMap(), true, SameTestInTwoExecutionsSample.class)));
        }
        for (Future<?> execution : executions) {
          execution.get(60, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
        SameTestInTwoExecutionsSample.GREEN_MAIL.stop();
      }

      List<RecordedMail> mails = new ArrayList<>();
      for (Path runDirectory : RecordedRun.runDirectories(outputDirectory)) {
        mails.addAll(RecordedRun.readRunDirectory(runDirectory).mails());
      }
      assertThat(mails).hasSize(2).allMatch(mail -> mail.phase().equals("ambiguous"));
    }

    @Test
    void testsRunningAtTheSameTimeWithTheirOwnGreenMailAreToldApart() throws IOException {
      run(parallelExecution(), true, ConstraintSamples.ParallelWithOwnGreenMail.class);
      RecordedRun run = RecordedRun.read(outputDirectory);

      RecordedMail first = run.mail("own greenmail first");
      assertThat(first.phase()).isEqualTo("test");
      assertThat(first.testMethod()).isEqualTo("first");
      RecordedMail second = run.mail("own greenmail second");
      assertThat(second.phase()).isEqualTo("test");
      assertThat(second.testMethod()).isEqualTo("second");
    }

    @Test
    void mailsOfTestsThatUseTheSameGreenMailAtTheSameTimeAreAmbiguous() throws IOException {
      ConstraintSamples.Parallel.GREEN_MAIL.start();
      try {
        run(parallelExecution(), true, ConstraintSamples.Parallel.class);
        RecordedRun run = RecordedRun.read(outputDirectory);

        RecordedMail second = run.mail("parallel second");
        assertThat(second.phase()).isEqualTo("ambiguous");
        assertThat(second.testClass()).isNull();
        assertThat(candidateMethods(second)).containsExactlyInAnyOrder("first", "second");
        RecordedMail first = run.mail("parallel first");
        assertThat(first.phase()).isEqualTo("ambiguous");
        assertThat(candidateMethods(first)).contains("first");
      } finally {
        ConstraintSamples.Parallel.GREEN_MAIL.stop();
      }
    }

    private List<String> candidateMethods(RecordedMail mail) {
      List<String> methods = new ArrayList<>();
      mail.json.path("candidates").forEach(test -> methods.add(test.path("testMethod").asText()));
      return methods;
    }

    private Map<String, String> parallelExecution() {
      Map<String, String> parallel = new LinkedHashMap<>();
      parallel.put("junit.jupiter.execution.parallel.enabled", "true");
      parallel.put("junit.jupiter.execution.parallel.config.strategy", "fixed");
      parallel.put("junit.jupiter.execution.parallel.config.fixed.parallelism", "2");
      return parallel;
    }
  }

  @Nested
  class RecordingFailures {

    @Test
    void aTestWhoseMailCouldNotBeRecordedDoesNotStayRunning() throws IOException {
      Path records = outputDirectory.resolve("records");
      run(
          Map.of(OutputDirectory.CONFIGURATION_PARAMETER, records.toString()),
          false,
          RecordingFailureSamples.FailsInAfterEach.class);

      RecordedMail second = RecordedRun.read(records).mail("second mail");
      assertThat(second.phase()).isEqualTo("test");
      assertThat(second.testMethod()).isEqualTo("second");
    }

    @Test
    void failedSetupIsRecordedAlsoWhenAMailCouldNotBeRecordedAfterAll() throws IOException {
      run(Collections.emptyMap(), false, RecordingFailureSamples.FailsInAfterAll.class);
      Path messages = RecordedRun.runDirectories(outputDirectory).get(0).resolve("messages");
      Files.delete(messages);
      Files.createDirectory(messages);

      JsonNode failure =
          RecordedRun.read(outputDirectory)
              .classFailure(RecordingFailureSamples.FailsInAfterAll.class);
      assertThat(failure.path("stage").asText()).isEqualTo("beforeAll");
    }

    @Test
    void otherKeptResultsAreRecordedWhenOneOfThemCannotBeWritten() throws IOException {
      run(Collections.emptyMap(), true, RecordingFailureSamples.FailsForOneOfTheKeptResults.class);
      Path blocked =
          RecordedRun.runDirectories(outputDirectory).get(0).resolve("tests/000001.json");
      Files.delete(blocked.resolve("blocker"));
      Files.delete(blocked);

      RecordedRun run = RecordedRun.read(outputDirectory);
      assertThat(run.tests())
          .extracting(test -> test.path("testMethod").asText() + "=" + test.path("result").asText())
          .containsExactlyInAnyOrder(
              "disabledSecond=disabled", "sends=passed", "passesLater=passed");
    }

    @Test
    void otherMailsAreRecordedWhenOneOfThemCannotBeWritten() throws IOException {
      RecordedRun run = runSamples(RecordingFailureSamples.FailsForOneOfSeveralMails.class);

      assertThat(run.mails("first of two")).isEmpty();
      RecordedMail second = run.mail("second of two");
      assertThat(second.testMethod()).isEqualTo("sendsTwoMails");
    }

    @Test
    void anUnsupportedGreenMailStopsTheRecordingOfTheExecutionButNotTheTests() throws IOException {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(Collections.emptyMap(), true, RecordingFailureSamples.IncompatibleGreenMail.class);

        assertThat(logs.warnings())
            .singleElement()
            .asString()
            .startsWith("Stopped recording the emails from GreenMail in this JUnit execution");
      }
      assertThat(outputDirectory.resolve("runs")).doesNotExist();

      // The next execution records again.
      RecordedRun run = runSamples(PerMethodExtensionSample.class);
      assertThat(run.mail("per-method one").testMethod()).isEqualTo("sendsOneMail");
    }
  }

  @Nested
  class AsynchronousMails {

    @Test
    void mailsSentAfterTheTestAreLostWithoutWaiting() throws Exception {
      RecordedRun run = runSamples(ConstraintSamples.AsyncWithoutWait.class);
      ConstraintSamples.AsyncWithoutWait.sender.join(10_000);

      assertThat(run.mails("async without wait")).isEmpty();
      assertThat(run.test("sendsLater").path("result").asText()).isEqualTo("passed");
    }

    @Test
    void asyncWaitMillisWaitsForMailsSentAfterTheTest() throws Exception {
      RecordedRun run = runSamples(ConstraintSamples.AsyncWithWait.class);
      ConstraintSamples.AsyncWithWait.sender.join(10_000);

      RecordedMail mail = run.mail("async with wait");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testMethod()).isEqualTo("sendsLater");
    }
  }

  @Nested
  class Limitations {

    @Test
    void classesDisabledAsAWholeLeaveNoRecords() {
      run(Collections.emptyMap(), false, ConstraintSamples.DisabledClass.class);

      assertThat(outputDirectory.resolve("runs")).doesNotExist();
    }

    @Test
    void sharedGreenMailFoundOnlyRightBeforeTheTestGivesEarlierMailsToTheTest() throws IOException {
      SharedGreenMail.GREEN_MAIL.start();
      try {
        Mails.send(SharedGreenMail.GREEN_MAIL, "sent before the class");

        RecordedRun run = runSamples(ConstraintSamples.SharedGreenMailAssignedInBeforeEach.class);

        assertThat(run.mail("sent before the class").testMethod()).isEqualTo("sends");
        assertThat(run.mail("assigned in before each").testMethod()).isEqualTo("sends");
      } finally {
        SharedGreenMail.GREEN_MAIL.stop();
      }
    }

    @Test
    void failuresOfContainersInsideATestClassAreNotRecorded() throws IOException {
      RecordedRun run = runSamples(ConstraintSamples.ContainerFailures.class);

      assertThat(run.tests())
          .extracting(
              test -> test.path("displayName").asText(), test -> test.path("result").asText())
          .containsExactly(tuple("group › first", "passed"));
      assertThat(run.mail("first of the group").displayName()).isEqualTo("group › first");
      assertThat(run.mails()).hasSize(1);
    }

    @Test
    void dynamicTestsHaveTheResultsThatAnotherExtensionAroundTheViewerChanged() throws IOException {
      RecordedRun run = runSamples(ConstraintSamples.ResultsChangedAroundTheViewer.class);

      assertThat(resultsByName(run))
          .containsOnly(entry("passes", "passed"), entry("fails", "failed"));
    }

    @Test
    void dynamicTestsHaveTheirResultsWhenTheViewerIsRegisteredFirst() throws IOException {
      RecordedRun run = runSamples(ConstraintSamples.ResultsChangedInsideTheViewer.class);

      assertThat(resultsByName(run))
          .containsOnly(entry("passes", "failed"), entry("fails", "passed"));
    }

    private Map<String, String> resultsByName(RecordedRun run) {
      return run.tests().stream()
          .collect(
              Collectors.toMap(
                  test -> test.path("displayName").asText(), test -> test.path("result").asText()));
    }
  }

  @Nested
  class RunsAndCleanup {

    @Test
    void runningAgainInTheSameJvmCreatesANewRunDirectory() throws IOException {
      run(Collections.emptyMap(), true, PerMethodExtensionSample.class);
      run(Collections.emptyMap(), true, RecipientsSample.class);

      List<Path> runs = RecordedRun.runDirectories(outputDirectory);
      assertThat(runs).hasSize(2);
      List<Integer> mailCounts = new ArrayList<>();
      for (Path runDirectory : runs) {
        mailCounts.add(RecordedRun.readRunDirectory(runDirectory).mails().size());
      }
      assertThat(mailCounts).containsExactlyInAnyOrder(3, 1);
    }

    @Test
    void doesNotDeleteARunThatAnotherJvmIsStillCreating() throws Exception {
      Path runsDirectory = Files.createDirectories(outputDirectory.resolve("runs"));
      Process otherJvm = startJvm(CreatingRunHolder.class, runsDirectory.toString(), "creating");
      BufferedReader otherOutput = outputOf(otherJvm);
      try {
        assertThat(otherOutput.readLine()).isEqualTo("creating");
        FutureTask<Void> thisRun =
            new FutureTask<>(
                () ->
                    run(
                        Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "0"),
                        true,
                        RecipientsSample.class),
                null);
        new Thread(thisRun).start();
        Thread.sleep(1000);
        assertThat(thisRun.isDone()).as("waits while the other JVM creates its run").isFalse();

        otherJvm.getOutputStream().write("go\n".getBytes(StandardCharsets.UTF_8));
        otherJvm.getOutputStream().flush();
        assertThat(otherOutput.readLine()).isEqualTo("running");
        thisRun.get(30, TimeUnit.SECONDS);

        assertThat(runNames()).hasSize(2).contains("creating");
      } finally {
        otherJvm.getOutputStream().close();
        assertThat(otherJvm.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
    }

    @Test
    void ordersRunsByTheirStartTimeAcrossTimeZones() throws IOException {
      createFinishedRun("20261102-000500-1", "2026-11-02T00:05:00.000+09:00");
      createFinishedRun("20261101-150600-2", "2026-11-01T15:06:00.000Z");

      run(Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "1"), true, RecipientsSample.class);

      assertThat(runNames())
          .hasSize(2)
          .contains("20261101-150600-2")
          .doesNotContain("20261102-000500-1");
    }

    @Test
    void writesAsciiDigitsWhateverTheDefaultLocale() throws IOException {
      Locale original = Locale.getDefault();
      Locale.setDefault(Locale.forLanguageTag("ar-EG"));
      try {
        run(Collections.emptyMap(), true, PerMethodExtensionSample.class);
      } finally {
        Locale.setDefault(original);
      }

      Path runDirectory = RecordedRun.runDirectories(outputDirectory).get(0);
      assertThat(runDirectory.getFileName().toString())
          .matches("[0-9]{8}T[0-9]{6}Z-[0-9]+-[0-9a-f]{8}");
      List<String> recordFiles = new ArrayList<>();
      for (String directory : List.of("messages", "tests")) {
        try (Stream<Path> files = Files.list(runDirectory.resolve(directory))) {
          files.forEach(file -> recordFiles.add(file.getFileName().toString()));
        }
      }
      assertThat(recordFiles).hasSize(9).allMatch(name -> name.matches("[0-9]{6}\\.(json|eml)"));
      assertThat(Files.readString(runDirectory.resolve("run.json")))
          .containsPattern("\"startedAt\": \"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}");
    }

    @Test
    void anExecutionThatOverlapsAnotherOneInTheSameJvmGetsARunOfItsOwn() throws IOException {
      OverlappingExecutionSample.outputDirectory = outputDirectory;
      run(
          Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "0"),
          true,
          OverlappingExecutionSample.class);

      List<List<String>> subjectsOfEachRun = new ArrayList<>();
      for (Path runDirectory : RecordedRun.runDirectories(outputDirectory)) {
        RecordedRun run = RecordedRun.readRunDirectory(runDirectory);
        subjectsOfEachRun.add(
            run.mails().stream().map(mail -> mail.subject).sorted().collect(Collectors.toList()));
        JsonNode runJson = new ObjectMapper().readTree(runDirectory.resolve("run.json").toFile());
        assertThat(runJson.has("finishedAt")).as("finished: %s", runDirectory).isTrue();
      }
      assertThat(subjectsOfEachRun)
          .containsExactlyInAnyOrder(
              List.of("outer after", "outer before"), List.of("several recipients"));
    }

    @Test
    void cleaningUpKeepsTheLockOfARunThatThisJvmIsWriting() throws Exception {
      RunWriter inProgress = RunWriter.create(outputDirectory, 20);
      try {
        RunWriter.create(outputDirectory, 0).finish();

        assertThat(inProgress.runDirectory()).exists();
        Process checker =
            startJvm(LockChecker.class, inProgress.runDirectory().resolve("run.lock").toString());
        assertThat(outputOf(checker).readLine()).isEqualTo("held");
        assertThat(checker.waitFor(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        inProgress.finish();
      }
    }

    @Test
    void cleaningUpThroughAnotherPathKeepsTheLockOfARunThatThisJvmIsWriting() throws Exception {
      Path real = Files.createDirectories(outputDirectory.resolve("real"));
      Path alias = outputDirectory.resolve("alias");
      OldRunsTest.link(alias, real);
      RunWriter inProgress = RunWriter.create(real, 20);
      try {
        RunWriter.create(alias, 0).finish();

        assertThat(inProgress.runDirectory()).exists();
        Process checker =
            startJvm(LockChecker.class, inProgress.runDirectory().resolve("run.lock").toString());
        assertThat(outputOf(checker).readLine()).isEqualTo("held");
        assertThat(checker.waitFor(10, TimeUnit.SECONDS)).isTrue();
      } finally {
        inProgress.finish();
      }
    }

    @Test
    void finishedRunHasItsFinishTimeAndNoLongerHoldsTheLock() throws IOException {
      RecordedRun run = runSamples(RecipientsSample.class);

      JsonNode runJson =
          new ObjectMapper().readTree(run.runDirectory().resolve("run.json").toFile());
      assertThat(runJson.path("finishedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
      Path lockFile = run.runDirectory().resolve("run.lock");
      assertThat(lockFile).exists();
      try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
          FileLock lock = channel.tryLock()) {
        assertThat(lock).as("lock of a finished run").isNotNull();
      }
    }

    @Test
    void cleaningUpLeavesOtherFoldersAlone() throws IOException {
      createFinishedRun("20200101-000001-1");
      Path notes = Files.createDirectories(outputDirectory.resolve("runs/manual-data"));
      Files.writeString(notes.resolve("notes.txt"), "kept");
      Path lookalike = Files.createDirectories(outputDirectory.resolve("runs/20200101-000002-1"));
      Path otherTool =
          Files.createDirectories(outputDirectory.resolve("runs/20200101-120000-1234"));
      Files.writeString(otherTool.resolve("run.json"), "{\"experiment\": \"other tool\"}");
      Files.writeString(otherTool.resolve("notes.txt"), "kept");

      run(Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "0"), true, RecipientsSample.class);

      assertThat(notes.resolve("notes.txt")).hasContent("kept");
      assertThat(lookalike).isDirectory();
      assertThat(otherTool.resolve("run.json")).hasContent("{\"experiment\": \"other tool\"}");
      assertThat(otherTool.resolve("notes.txt")).hasContent("kept");
      assertThat(outputDirectory.resolve("runs/20200101-000001-1")).doesNotExist();
    }

    @Test
    void keepsTwentyFinishedRunsByDefault() throws IOException {
      for (int i = 1; i <= OldRuns.DEFAULT_KEEP_RUNS + 1; i++) {
        createFinishedRun(String.format(Locale.ROOT, "20200101-%06d-1", i));
      }

      run(Collections.emptyMap(), true, RecipientsSample.class);

      assertThat(runNames())
          .hasSize(OldRuns.DEFAULT_KEEP_RUNS + 1)
          .doesNotContain("20200101-000001-1")
          .contains("20200101-000002-1", "20200101-000021-1");
    }

    @Test
    void keepsOnlyTheNewestFinishedRuns() throws IOException {
      for (int i = 1; i <= 5; i++) {
        createFinishedRun("20200101-00000" + i + "-1");
      }

      run(Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "2"), true, RecipientsSample.class);

      assertThat(runNames())
          .hasSize(3)
          .contains("20200101-000005-1", "20200101-000004-1")
          .doesNotContain("20200101-000003-1", "20200101-000002-1", "20200101-000001-1");
    }

    @Test
    void doesNotDeleteRunsThatAnotherJvmIsStillWriting() throws Exception {
      for (int i = 1; i <= 3; i++) {
        createFinishedRun("20200101-00000" + i + "-1");
      }
      Path runningLock = outputDirectory.resolve("runs/20200101-000001-1/run.lock");
      Process otherJvm = startLockHolder(runningLock);
      try {
        run(
            Collections.singletonMap(OldRuns.KEEP_RUNS_PARAMETER, "0"),
            true,
            RecipientsSample.class);

        assertThat(runNames())
            .hasSize(2)
            .contains("20200101-000001-1")
            .doesNotContain("20200101-000002-1", "20200101-000003-1");
      } finally {
        otherJvm.getOutputStream().close();
        assertThat(otherJvm.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
    }

    @Test
    void usesTheDefaultForAnInvalidNumberOfRunsToKeep() {
      assertThat(OldRuns.keepRuns(null)).isEqualTo(20);
      assertThat(OldRuns.keepRuns("5")).isEqualTo(5);
      assertThat(OldRuns.keepRuns("0")).isEqualTo(0);
      assertThat(OldRuns.keepRuns("-1")).isEqualTo(20);
      assertThat(OldRuns.keepRuns("many")).isEqualTo(20);
    }

    private void createFinishedRun(String name) throws IOException {
      String startedAt =
          name.substring(0, 4)
              + "-"
              + name.substring(4, 6)
              + "-"
              + name.substring(6, 8)
              + "T"
              + name.substring(9, 11)
              + ":"
              + name.substring(11, 13)
              + ":"
              + name.substring(13, 15)
              + ".000+00:00";
      createFinishedRun(name, startedAt);
    }

    private void createFinishedRun(String name, String startedAt) throws IOException {
      Path run = Files.createDirectories(outputDirectory.resolve("runs").resolve(name));
      Files.writeString(
          run.resolve("run.json"),
          "{\n  \"formatVersion\": 1,\n  \"startedAt\": \""
              + startedAt
              + "\",\n  \"pid\": "
              + name.substring(name.lastIndexOf('-') + 1)
              + "\n}\n");
      Files.createFile(run.resolve("run.lock"));
    }

    private List<String> runNames() throws IOException {
      return RecordedRun.runDirectories(outputDirectory).stream()
          .map(run -> run.getFileName().toString())
          .collect(Collectors.toList());
    }

    private Process startLockHolder(Path lockFile) throws IOException {
      Process process = startJvm(LockHolder.class, lockFile.toString());
      assertThat(outputOf(process).readLine()).isEqualTo("locked");
      return process;
    }

    private Process startJvm(Class<?> mainClass, String... args) throws IOException {
      List<String> command = new ArrayList<>();
      command.add(ProcessHandle.current().info().command().orElse("java"));
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(mainClass.getName());
      command.addAll(Arrays.asList(args));
      return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private BufferedReader outputOf(Process process) {
      return new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }
  }

  @Nested
  class DynamicTests {

    private final String factory =
        "[engine:junit-jupiter]/[class:" + DynamicTestsSample.class.getName() + "]";

    @Test
    void eachDynamicTestIsATestWithItsOwnMailsAndResult() throws IOException {
      RecordedRun run = runSamples(DynamicTestsSample.class);

      RecordedMail passes = run.mail("dynamic passes");
      assertThat(passes.phase()).isEqualTo("test");
      assertThat(passes.testClass()).isEqualTo(DynamicTestsSample.class.getName());
      assertThat(passes.testMethod()).isEqualTo("signup");
      assertThat(passes.displayName()).isEqualTo("passes");
      assertThat(passes.uniqueId())
          .isEqualTo(factory + "/[test-factory:signup()]/[dynamic-test:#1]");
      assertThat(run.mail("dynamic fails").displayName()).isEqualTo("fails");
      RecordedMail inGroup = run.mail("dynamic in a group");
      assertThat(inGroup.displayName()).isEqualTo("group › in a group");
      assertThat(inGroup.uniqueId())
          .isEqualTo(factory + "/[test-factory:signup()]/[dynamic-container:#4]/[dynamic-test:#1]");
      RecordedMail made = run.mail("made by the factory");
      assertThat(made.displayName()).isEqualTo("signup()");
      assertThat(made.uniqueId()).isEqualTo(factory + "/[test-factory:signup()]");

      assertThat(resultsOf(run, "signup"))
          .containsOnly(
              entry("passes", "passed"),
              entry("fails", "failed"),
              entry("is aborted", "aborted"),
              entry("group › in a group", "passed"));
      assertThat(errorOf(run, "fails"))
          .isEqualTo("java.lang.AssertionError: the dynamic test fails");
      assertThat(errorOf(run, "is aborted")).contains("not this time");
    }

    @Test
    void aFactoryMethodThatFailsItselfIsRecordedAsFailed() throws IOException {
      RecordedRun run = runSamples(DynamicTestsSample.class);

      JsonNode broken = run.test("brokenFactory");
      assertThat(broken.path("result").asText()).isEqualTo("failed");
      assertThat(broken.path("error").asText())
          .isEqualTo("java.lang.IllegalStateException: cannot make the dynamic tests");
      assertThat(run.mail("made by the broken factory").testMethod()).isEqualTo("brokenFactory");
      assertThat(resultsOf(run, "signup")).doesNotContainKey("signup()");
    }

    @Test
    void aFailedDynamicTestLogsTheLinkToItsMailsInTheViewer() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(Collections.emptyMap(), false, DynamicTestsSample.class);

        String uniqueId = factory + "/[test-factory:signup()]/[dynamic-test:#2]";
        assertThat(logs.infos())
            .filteredOn(message -> message.contains("GreenMail Test Viewer"))
            .anySatisfy(message ->
                assertThat(message)
                    .startsWith("fails failed.")
                    .endsWith("#test=" + URLEncoder.encode(uniqueId, StandardCharsets.UTF_8)));
      }
    }

    private Map<String, String> resultsOf(RecordedRun run, String testMethod) {
      return run.tests().stream()
          .filter(test -> testMethod.equals(test.path("testMethod").asText()))
          .collect(
              Collectors.toMap(
                  test -> test.path("displayName").asText(), test -> test.path("result").asText()));
    }

    private String errorOf(RecordedRun run, String displayName) {
      return run.tests().stream()
          .filter(test -> displayName.equals(test.path("displayName").asText()))
          .map(test -> test.path("error").asText())
          .findFirst()
          .orElseThrow();
    }
  }

  @Nested
  class ViewerLinks {

    private static final String LINK = "GreenMail Test Viewer";

    @Test
    void aFailedTestLogsTheLinkToItsMailsInTheViewer() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(Collections.emptyMap(), false, LifecycleFailureSamples.BeforeEachFails.class);

        String uniqueId =
            "[engine:junit-jupiter]/[class:"
                + LifecycleFailureSamples.BeforeEachFails.class.getName()
                + "]/[method:neverRuns()]";
        assertThat(logs.infos())
            .filteredOn(message -> message.contains(LINK))
            .singleElement()
            .asString()
            .startsWith("neverRuns() failed.")
            .endsWith(
                "http://127.0.0.1:3080/#test="
                    + URLEncoder.encode(uniqueId, StandardCharsets.UTF_8));
      }
    }

    @Test
    void theLinkPointsToTheConfiguredViewerOrIsLeftOut() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(
            Map.of(ViewerLink.CONFIGURATION_PARAMETER, "http://viewer.localhost:9000/"),
            false,
            LifecycleFailureSamples.BeforeEachFails.class);
        run(
            Map.of(ViewerLink.CONFIGURATION_PARAMETER, "none"),
            false,
            LifecycleFailureSamples.BeforeEachFails.class);

        assertThat(logs.infos())
            .filteredOn(message -> message.contains(LINK))
            .singleElement()
            .asString()
            .contains("http://viewer.localhost:9000/#test=%5Bengine%3Ajunit-jupiter%5D");
      }
    }

    @Test
    void aTestThatPassedLogsNoLink() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(Collections.emptyMap(), true, PerMethodExtensionSample.class);

        assertThat(logs.infos()).noneMatch(message -> message.contains(LINK));
      }
    }
  }

  private RecordedRun runSamples(Class<?>... testClasses) throws IOException {
    run(Collections.emptyMap(), false, testClasses);
    return RecordedRun.read(outputDirectory);
  }

  private void run(Map<String, String> parameters, boolean mustPass, Class<?>... testClasses) {
    DiscoverySelector[] selectors = new DiscoverySelector[testClasses.length];
    for (int i = 0; i < testClasses.length; i++) {
      selectors[i] = selectClass(testClasses[i]);
    }
    EngineTestKit.Builder builder =
        EngineTestKit.engine("junit-jupiter")
            .configurationParameter(
                OutputDirectory.CONFIGURATION_PARAMETER, outputDirectory.toString())
            .selectors(selectors);
    parameters.forEach(builder::configurationParameter);
    if (mustPass) {
      SampleResults.assertPassed(builder.execute(), false);
    } else {
      builder.execute();
    }
  }
}
