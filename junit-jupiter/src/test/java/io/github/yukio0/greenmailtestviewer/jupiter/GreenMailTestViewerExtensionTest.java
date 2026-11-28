package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yukio0.greenmailtestviewer.jupiter.RecordedRun.RecordedMail;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.CustomDeliverySample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.DisabledFirstSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.InheritedAnnotationSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.InheritedNestedSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.LateFirstRecordSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.Mails;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.NestedAndTemplatesSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.NoGreenMailAnnotatedSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.NoGreenMailSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.NotAnnotatedSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.PausedDeliverySample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.PerClassExtensionSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.PerMethodExtensionSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.PurgeDuringTestSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.RecipientsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SeparateSendsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SharedFirstSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SharedGreenMail;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SharedSecondSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.SmtpsSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.StaticGreenMailSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.WatchedNotAnnotatedSample;
import io.github.yukio0.greenmailtestviewer.jupiter.samples.WatchedSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;

class GreenMailTestViewerExtensionTest {

  @TempDir Path outputDirectory;

  @Nested
  class GreenMailLifecycles {

    @Test
    void recordsMailsOfGreenMailExtensionThatStartsForEachTest() throws IOException {
      RecordedRun run = runSamples(PerMethodExtensionSample.class);

      assertThat(run.subjectsOf("sendsOneMail")).containsExactly("per-method one");
      assertThat(run.subjectsOf("sendsTwoMails"))
          .containsExactly("per-method two a", "per-method two b");
      RecordedMail mail = run.mail("per-method one");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testClass()).isEqualTo(PerMethodExtensionSample.class.getName());
      assertThat(mail.displayName()).isEqualTo("sendsOneMail()");
      assertThat(mail.uniqueId()).contains("[method:sendsOneMail()]");
    }

    @Test
    void recordsMailsOfGreenMailExtensionThatStartsOnceForTheClass() throws IOException {
      RecordedRun run = runSamples(PerClassExtensionSample.class);

      assertThat(run.subjectsOf("first")).containsExactly("per-class first");
      assertThat(run.subjectsOf("second")).containsExactly("per-class second");
    }

    @Test
    void recordsResultsReportedBeforeAGreenMailInAnInstanceFieldIsFound() throws IOException {
      RecordedRun run = runSamples(DisabledFirstSample.class);

      assertThat(run.test("disabledFirst").path("result").asText()).isEqualTo("disabled");
      assertThat(run.test("sendsAfterTheDisabledTest").path("result").asText()).isEqualTo("passed");
      assertThat(run.mail("after a disabled test").testMethod())
          .isEqualTo("sendsAfterTheDisabledTest");
    }

    @Test
    void recordsMailsOfStaticGreenMailStartedByTheTestClass() throws IOException {
      RecordedRun run = runSamples(StaticGreenMailSample.class);

      assertThat(run.subjectsOf("first"))
          .containsExactly("static before each first", "static first");
      assertThat(run.subjectsOf("second")).containsExactly("static before each second");
    }
  }

  @Nested
  class LifecycleMethods {

    @Test
    void mailsSentInBeforeAllAreOutsideOfTestsAndNotMixedIntoTheFirstTest() throws IOException {
      RecordedRun run = runSamples(PerClassExtensionSample.class, StaticGreenMailSample.class);

      RecordedMail perClass = run.mail("per-class before all");
      assertThat(perClass.phase()).isEqualTo("outsideTest");
      assertThat(perClass.testClass()).isEqualTo(PerClassExtensionSample.class.getName());
      assertThat(perClass.testMethod()).isNull();
      assertThat(perClass.uniqueId())
          .endsWith("[class:" + PerClassExtensionSample.class.getName() + "]");

      RecordedMail staticGreenMail = run.mail("static before all");
      assertThat(staticGreenMail.phase()).isEqualTo("outsideTest");
      assertThat(staticGreenMail.testClass()).isEqualTo(StaticGreenMailSample.class.getName());
    }

    @Test
    void mailsSentInBeforeEachBelongToTheTest() throws IOException {
      RecordedRun run = runSamples(StaticGreenMailSample.class);

      RecordedMail mail = run.mail("static before each first");
      assertThat(mail.phase()).isEqualTo("test");
      assertThat(mail.testMethod()).isEqualTo("first");
    }

    @Test
    void mailsDeletedInAfterEachAreStillRecorded() throws IOException {
      RecordedRun run = runSamples(StaticGreenMailSample.class);

      assertThat(run.mail("static first").testMethod()).isEqualTo("first");
    }

    @Test
    void mailsDeletedDuringATestAreStillRecorded() throws IOException {
      RecordedRun run = runSamples(PurgeDuringTestSample.class);

      assertThat(run.subjectsOf("purgesBetweenTwoMails"))
          .containsExactly("before the purge", "after the purge");
    }
  }

  @Nested
  class TestKinds {

    @Test
    void nestedParameterizedAndRepeatedTestsAreRecordedSeparately() throws IOException {
      RecordedRun run = runSamples(NestedAndTemplatesSample.class);

      RecordedMail first = run.mail("parameterized a");
      RecordedMail second = run.mail("parameterized b");
      assertThat(first.testMethod()).isEqualTo("parameterized");
      assertThat(first.displayName()).isIn("[1] a", "[1] \"a\"");
      assertThat(second.displayName()).isIn("[2] b", "[2] \"b\"");
      assertThat(first.uniqueId()).isNotEqualTo(second.uniqueId());

      assertThat(run.mail("repeated 1").uniqueId()).isNotEqualTo(run.mail("repeated 2").uniqueId());

      RecordedMail nested = run.mail("nested");
      assertThat(nested.testClass()).isEqualTo(NestedAndTemplatesSample.class.getName() + "$Inner");
      assertThat(nested.testMethod()).isEqualTo("nested");
      assertThat(nested.uniqueId()).contains("[nested-class:Inner]");

      List<String> testUniqueIds =
          run.tests().stream()
              .map(test -> test.path("uniqueId").asText())
              .collect(Collectors.toList());
      assertThat(testUniqueIds).hasSize(5).doesNotHaveDuplicates();
    }

    @Test
    void testsWithoutMailsHaveATestRecord() throws IOException {
      RecordedRun run = runSamples(PerMethodExtensionSample.class);

      assertThat(run.subjectsOf("sendsNoMail")).isEmpty();
      JsonNode test = run.test("sendsNoMail");
      assertThat(test.path("kind").asText()).isEqualTo("method");
      assertThat(test.path("result").asText()).isEqualTo("passed");
      assertThat(test.path("testClass").asText())
          .isEqualTo(PerMethodExtensionSample.class.getName());
      assertThat(test.has("phase")).isFalse();
      assertThat(run.tests()).hasSize(3);
    }
  }

  @Nested
  class Recipients {

    @Test
    void copiesForEachRecipientAreMergedIntoOneMail() throws IOException {
      RecordedRun run = runSamples(RecipientsSample.class);

      assertThat(run.mails()).hasSize(1);
      assertThat(run.mail("several recipients").deliveredTo())
          .containsExactly("bcc@example.com", "cc@example.com", "to@example.com");
    }

    @Test
    void aCustomDeliveryThatForwardsToAnotherAccountStillWorks() throws IOException {
      RecordedRun run = runSamples(CustomDeliverySample.class);

      for (String test : List.of("sendsOverSmtp", "sendsOverSmtps")) {
        assertThat(run.test(test).path("result").asText()).as(test).isEqualTo("passed");
      }
      assertThat(run.mail("relayed over smtp").testMethod()).isEqualTo("sendsOverSmtp");
      assertThat(run.mail("relayed over smtps").deliveredTo())
          .containsExactly("archive@example.com");
    }

    @Test
    void aMailThatTheServerDeliversOnlyAfterTheTestIsRecordedForIt() throws IOException {
      RecordedRun run = runSamples(PausedDeliverySample.class);

      RecordedMail mail = run.mail("paused delivery");
      assertThat(mail.testMethod()).isEqualTo("sendsWhileTheServerHangs");
      assertThat(mail.deliveredTo())
          .containsExactly("a@example.com", "b@example.com", "c@example.com");
    }

    @Test
    void theSameMailSentSeparatelyToEachRecipientStaysSeparate() throws IOException {
      RecordedRun run = runSamples(SeparateSendsSample.class);

      List<RecordedMail> separate = run.mails("separate sends");
      assertThat(separate).hasSize(2);
      assertThat(separate.get(0).deliveredTo()).containsExactly("a@example.com");
      assertThat(separate.get(1).deliveredTo()).containsExactly("b@example.com");

      assertThat(run.mail("one send").deliveredTo())
          .containsExactly("bcc@example.com", "cc@example.com", "to@example.com");
    }

    @Test
    void copiesDeliveredBeforeRecordingStartedAreMergedByContent() throws Exception {
      WatchedSample.Holder.GREEN_MAIL.start();
      GreenMailTestViewerExtension.watch(WatchedSample.Holder.GREEN_MAIL);
      try {
        Mails.sendToSeveral(
            WatchedSample.Holder.GREEN_MAIL,
            "before recording",
            "to@example.com",
            "cc@example.com",
            "bcc@example.com");

        RecordedRun run = runSamples(WatchedSample.class);

        RecordedMail mail = run.mail("before recording");
        assertThat(mail.phase()).isEqualTo("outsideClass");
        assertThat(mail.deliveredTo())
            .containsExactly("bcc@example.com", "cc@example.com", "to@example.com");
      } finally {
        GreenMailTestViewerExtension.unwatchAll();
        WatchedSample.Holder.GREEN_MAIL.stop();
      }
    }
  }

  @Nested
  class Smtps {

    @Test
    void copiesOfOneMailSentOverSmtpsAreMergedIntoOneMail() throws IOException {
      RecordedRun run = runSamples(SmtpsSample.class);

      RecordedMail mail = run.mail("smtps one send");
      assertThat(mail.testMethod()).isEqualTo("sendsOneMailToSeveralRecipientsOverSmtps");
      assertThat(mail.deliveredTo())
          .containsExactly("bcc@example.com", "cc@example.com", "to@example.com");
    }

    @Test
    void theSameMailSentSeparatelyOverSmtpsStaysSeparate() throws IOException {
      RecordedRun run = runSamples(SmtpsSample.class);

      List<RecordedMail> separate = run.mails("smtps separate sends");
      assertThat(separate).hasSize(2);
      assertThat(separate.get(0).deliveredTo()).containsExactly("a@example.com");
      assertThat(separate.get(1).deliveredTo()).containsExactly("b@example.com");
    }

    @Test
    void mailsOverSmtpAndSmtpsBelongToTheTestThatSentThem() throws IOException {
      RecordedRun run = runSamples(SmtpsSample.class);

      assertThat(run.subjectsOf("sendsOverSmtpAndSmtps"))
          .containsExactly("over smtp", "over smtps");
    }
  }

  @Nested
  class SharedGreenMails {

    @Test
    void greenMailSharedByTwoClassesRecordsEachMailOnce() throws IOException {
      SharedGreenMail.GREEN_MAIL.start();
      try {
        RecordedRun run = runSamples(SharedFirstSample.class, SharedSecondSample.class);

        assertThat(run.mails()).hasSize(2);
        assertThat(run.mail("shared first").testClass())
            .isEqualTo(SharedFirstSample.class.getName());
        assertThat(run.mail("shared second").testClass())
            .isEqualTo(SharedSecondSample.class.getName());
      } finally {
        SharedGreenMail.GREEN_MAIL.stop();
      }
    }

    @Test
    void watchedGreenMailIsRecordedAndEarlierMailsAreOutsideOfClasses() throws IOException {
      WatchedSample.Holder.GREEN_MAIL.start();
      GreenMailTestViewerExtension.watch(WatchedSample.Holder.GREEN_MAIL);
      try {
        Mails.send(WatchedSample.Holder.GREEN_MAIL, "watched before class");

        RecordedRun run = runSamples(WatchedSample.class);

        RecordedMail before = run.mail("watched before class");
        assertThat(before.phase()).isEqualTo("outsideClass");
        assertThat(before.testClass()).isNull();
        assertThat(before.uniqueId()).isNull();
        RecordedMail inTest = run.mail("watched in test");
        assertThat(inTest.phase()).isEqualTo("test");
        assertThat(inTest.testMethod()).isEqualTo("sends");
      } finally {
        GreenMailTestViewerExtension.unwatchAll();
        WatchedSample.Holder.GREEN_MAIL.stop();
      }
    }
  }

  @Nested
  class Registration {

    @Test
    void recordsClassesWithoutTheAnnotationWhenAutomaticRegistrationIsEnabled() throws IOException {
      EngineTestKit.engine("junit-jupiter")
          .configurationParameter(
              OutputDirectory.CONFIGURATION_PARAMETER, outputDirectory.toString())
          .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
          .selectors(selectClass(NotAnnotatedSample.class))
          .execute()
          .testEvents()
          .assertStatistics(stats -> stats.succeeded(1).failed(0));

      assertThat(RecordedRun.read(outputDirectory).mail("not annotated").testMethod())
          .isEqualTo("sends");
    }

    @Test
    void recordsTheResultsOfAClassWithoutTheAnnotationThatSendsToAWatchedGreenMail()
        throws IOException {
      WatchedSample.Holder.GREEN_MAIL.start();
      GreenMailTestViewerExtension.watch(WatchedSample.Holder.GREEN_MAIL);
      try {
        EngineTestKit.engine("junit-jupiter")
            .configurationParameter(
                OutputDirectory.CONFIGURATION_PARAMETER, outputDirectory.toString())
            .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
            .selectors(
                selectClass(WatchedNotAnnotatedSample.class), selectClass(NoGreenMailSample.class))
            .execute();

        RecordedRun run = RecordedRun.read(outputDirectory);
        assertThat(run.mail("watched without annotation").testMethod()).isEqualTo("sendsAndFails");
        assertThat(run.test("sendsAndFails").path("result").asText()).isEqualTo("failed");
        assertThat(run.tests()).hasSize(1);
      } finally {
        GreenMailTestViewerExtension.unwatchAll();
        WatchedSample.Holder.GREEN_MAIL.stop();
      }
    }

    @Test
    void recordsSubclassesOfAnAnnotatedBaseClass() throws IOException {
      RecordedRun run = runSamples(InheritedAnnotationSample.class);

      RecordedMail mail = run.mail("inherited annotation");
      assertThat(mail.testClass()).isEqualTo(InheritedAnnotationSample.class.getName());
      assertThat(mail.testMethod()).isEqualTo("sends");
    }

    @Test
    void recordsANestedClassInheritedFromABaseClassWithTheGreenMailOfTheSubclass()
        throws IOException {
      RecordedRun run = runSamples(InheritedNestedSample.class);

      RecordedMail mail = run.mail("inherited nested");
      assertThat(mail.testMethod()).isEqualTo("sends");
      assertThat(mail.uniqueId()).contains(InheritedNestedSample.class.getName());
      assertThat(run.tests()).hasSize(1);
    }

    @Test
    void doesNothingForClassesWithoutTheAnnotationByDefault() {
      run(outputDirectory, NotAnnotatedSample.class);

      assertThat(outputDirectory.resolve("runs")).doesNotExist();
    }

    @Test
    void warnsWhenAnAnnotatedClassHasNoGreenMail() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        run(outputDirectory, NoGreenMailAnnotatedSample.class);

        assertThat(logs.warnings())
            .singleElement()
            .asString()
            .contains("No GreenMail instance was found in the fields of")
            .contains(NoGreenMailAnnotatedSample.class.getName())
            .contains("GreenMailTestViewerExtension.watch(greenMail)");
      }
      assertThat(outputDirectory.resolve("runs")).doesNotExist();
    }

    @Test
    void staysQuietForClassesWithoutGreenMailWhenAutomaticRegistrationIsEnabled() {
      try (CapturedLogs logs = new CapturedLogs(GreenMailTestViewerExtension.class)) {
        EngineTestKit.engine("junit-jupiter")
            .configurationParameter(
                OutputDirectory.CONFIGURATION_PARAMETER, outputDirectory.toString())
            .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true")
            .selectors(selectClass(NoGreenMailSample.class))
            .execute()
            .testEvents()
            .assertStatistics(stats -> stats.succeeded(1).failed(0));

        assertThat(logs.warnings()).isEmpty();
      }
      assertThat(outputDirectory.resolve("runs")).doesNotExist();
    }
  }

  @Nested
  class OutputFiles {

    @Test
    void writesMarkerRunInformationAndCompleteMailFiles() throws IOException {
      RecordedRun run = runSamples(RecipientsSample.class);

      JsonNode marker = readJson(outputDirectory.resolve("greenmail-test-viewer.json"));
      assertThat(marker.path("formatVersion").asInt()).isEqualTo(1);

      JsonNode runJson = readJson(run.runDirectory().resolve("run.json"));
      assertThat(runJson.path("formatVersion").asInt()).isEqualTo(1);
      assertThat(runJson.path("pid").asLong()).isEqualTo(ProcessHandle.current().pid());
      assertThat(runJson.path("javaVersion").asText())
          .isEqualTo(System.getProperty("java.version"));
      assertThat(runJson.path("greenMailVersion").asText())
          .isEqualTo(RuntimeVersionTest.greenMailVersion());
      assertThat(runJson.path("startedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*");
      assertThat(run.runDirectory().getFileName().toString())
          .matches("\\d{8}T\\d{6}Z-" + ProcessHandle.current().pid() + "-[0-9a-f]{8}");

      try (var files = Files.list(run.runDirectory().resolve("messages"))) {
        assertThat(files.map(file -> file.getFileName().toString()))
            .containsExactlyInAnyOrder("000001.eml", "000001.json");
      }
      assertThat(run.mail("several recipients").json.path("capturedAt").asText())
          .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})");
      assertThat(RunWriter.timestamp(OffsetDateTime.parse("2026-11-01T15:09:40.420Z")))
          .isEqualTo("2026-11-01T15:09:40.420Z");
      assertThat(RunWriter.timestamp(OffsetDateTime.parse("2026-11-02T09:00:00+09:00")))
          .isEqualTo("2026-11-02T09:00:00.000+09:00");
    }

    @Test
    void theRunStartsWhenTheExecutionStartsRatherThanAtItsFirstRecord() throws IOException {
      RecordedRun run = runSamples(LateFirstRecordSample.class);

      OffsetDateTime startedAt =
          OffsetDateTime.parse(
              readJson(run.runDirectory().resolve("run.json")).path("startedAt").asText());
      OffsetDateTime firstRecord =
          OffsetDateTime.parse(run.test("a_takesAWhile").path("finishedAt").asText());
      assertThat(Duration.between(startedAt, firstRecord).toMillis())
          .isGreaterThanOrEqualTo(LateFirstRecordSample.WAIT_MILLIS);
    }

    @Test
    void writesToAnOutputDirectoryOutsideOfTheProject(@TempDir Path elsewhere) throws IOException {
      Path reports = elsewhere.resolve("reports").resolve("mail");

      run(reports, RecipientsSample.class);

      assertThat(reports.resolve("greenmail-test-viewer.json")).exists();
      assertThat(RecordedRun.read(reports).mails()).hasSize(1);
    }
  }

  private RecordedRun runSamples(Class<?>... testClasses) throws IOException {
    run(outputDirectory, testClasses);
    return RecordedRun.read(outputDirectory);
  }

  private static void run(Path outputDirectory, Class<?>... testClasses) {
    DiscoverySelector[] selectors = new DiscoverySelector[testClasses.length];
    for (int i = 0; i < testClasses.length; i++) {
      selectors[i] = selectClass(testClasses[i]);
    }
    EngineExecutionResults results =
        EngineTestKit.engine("junit-jupiter")
            .configurationParameter(
                OutputDirectory.CONFIGURATION_PARAMETER, outputDirectory.toString())
            .selectors(selectors)
            .execute();
    SampleResults.assertPassed(results, true);
  }

  private static JsonNode readJson(Path file) throws IOException {
    return new ObjectMapper().readTree(file.toFile());
  }
}
