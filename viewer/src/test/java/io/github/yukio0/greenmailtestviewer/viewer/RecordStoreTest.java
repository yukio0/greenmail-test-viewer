package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import io.github.yukio0.greenmailtestviewer.viewer.RecordStore.Snapshot;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordStoreTest {

  private static final String SIGNUP = "com.example.SignupTest";

  @TempDir Path project;

  private RecordFiles output;
  private RecordStore store;

  @BeforeEach
  void createStore() {
    output = RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    store = new RecordStore(List.of(project));
  }

  @Test
  void readsTheRecordsAndCountsUpTheRevisionOnlyWhenSomethingChanged() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");

    assertThat(store.refresh(true)).isTrue();
    long revision = store.snapshot().revision();
    assertThat(subjects()).containsExactly("Welcome");
    assertThat(store.refresh(true)).isFalse();
    assertThat(store.snapshot().revision()).isEqualTo(revision);

    run.testMail(SIGNUP, "sendsWelcome", "Second", "2026-11-01T10:00:02.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:03.000Z");
    assertThat(store.refresh(false)).isTrue();
    assertThat(store.snapshot().revision()).isGreaterThan(revision);
    assertThat(subjects()).containsExactly("Welcome", "Second");
    assertThat(onlyRun().tests()).hasSize(1);
  }

  @Test
  void readsTheFinishTimeWhenTheRunFinishes() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    store.refresh(true);
    assertThat(onlyRun().finishedAt()).isNull();

    run.finish("2026-11-01T10:00:05.000Z");

    assertThat(store.refresh(false)).isTrue();
    assertThat(onlyRun().finishedAt().text()).isEqualTo("2026-11-01T10:00:05.000Z");
  }

  @Test
  void forgetsADeletedRunDirectory() throws IOException {
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Old", "2026-11-01T10:00:01.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    second.testMail(SIGNUP, "sendsWelcome", "New", "2026-11-01T11:00:01.000Z");
    store.refresh(true);
    String oldMail = mailIds().get(0);
    long revision = store.snapshot().revision();

    deleteRecursively(first.directory());

    assertThat(store.refresh(false)).isTrue();
    assertThat(store.snapshot().revision()).isGreaterThan(revision);
    assertThat(subjects()).containsExactly("New");
    assertThat(store.snapshot().mail(oldMail)).isNull();
  }

  @Test
  void forgetsADeletedOutputDirectoryAndFindsItAgainWhenItIsWrittenAgain() throws IOException {
    output.run("run1", "2026-11-01T10:00:00.000Z");
    store.refresh(true);
    assertThat(store.snapshot().sources()).hasSize(1);

    deleteRecursively(project.resolve("app/build"));
    assertThat(store.refresh(false)).isTrue();
    assertThat(store.snapshot().sources()).isEmpty();

    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "After clean", "2026-11-01T11:00:01.000Z");
    assertThat(store.refresh(true)).isTrue();
    assertThat(subjects()).containsExactly("After clean");
  }

  @Test
  void readsADeletedOutputDirectoryAgainInTheNextScanWhenItIsWrittenAgain() throws IOException {
    output.run("run1", "2026-11-01T10:00:00.000Z");
    store.refresh(true);
    String module = store.snapshot().sources().get(0).id();

    deleteRecursively(project.resolve("app/build"));
    assertThat(store.refresh(false)).isTrue();
    assertThat(store.snapshot().sources()).isEmpty();
    writeAgainAfterClean();

    assertThat(store.refresh(false)).isTrue();
    assertThat(subjects()).containsExactly("After clean");
    assertThat(store.snapshot().sources().get(0).id()).isEqualTo(module);
  }

  @Test
  void readsAnOutputDirectoryAgainAlsoWhenTheDeletionWasNoticedWhileLookingForOutputDirectories()
      throws IOException {
    output.run("run1", "2026-11-01T10:00:00.000Z");
    store.refresh(true);

    deleteRecursively(project.resolve("app/build"));
    assertThat(store.refresh(true)).isTrue();
    assertThat(store.snapshot().sources()).isEmpty();
    writeAgainAfterClean();

    assertThat(store.refresh(false)).isTrue();
    assertThat(subjects()).containsExactly("After clean");
  }

  @Test
  void readsTheOutputDirectoriesBelowAnotherOneWhenStarted() {
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "In app", "2026-11-01T10:00:01.000Z");
    RecordFiles.create(project)
        .run("run1", "2026-11-01T11:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "In the root", "2026-11-01T11:00:01.000Z");

    store.refresh(true);

    assertThat(subjects()).containsExactlyInAnyOrder("In app", "In the root");
  }

  @Test
  void waitsForTheMarkerFileOfAnOutputDirectoryThatIsWrittenAgain() throws IOException {
    output.run("run1", "2026-11-01T10:00:00.000Z");
    store.refresh(true);
    deleteRecursively(project.resolve("app/build"));
    store.refresh(false);

    Path directory = project.resolve("app/build/greenmail-test-viewer");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(SourceFinder.MARKER_FILE_NAME), "{\"formatVer");
    assertThat(store.refresh(false)).isFalse();
    assertThat(store.snapshot().sources()).isEmpty();

    writeAgainAfterClean();
    assertThat(store.refresh(false)).isTrue();
    assertThat(subjects()).containsExactly("After clean");
  }

  private void writeAgainAfterClean() {
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "After clean", "2026-11-01T11:00:01.000Z");
  }

  @Test
  void readsAMailOnlyWhenItsJsonFileIsThere() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    Path messages = Files.createDirectories(run.directory().resolve("messages"));
    Files.writeString(messages.resolve("000001.eml"), RecordFiles.eml("Half written", "body"));
    Files.writeString(messages.resolve(".000001.json.tmp"), "{\"formatVersion\": 1");

    store.refresh(true);

    assertThat(subjects()).isEmpty();
  }

  @Test
  void skipsAMailWhoseFileDisappearedAndReadsItWhenItIsThere() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Path eml = run.directory().resolve("messages/000001.eml");
    String content = Files.readString(eml);
    Files.delete(eml);

    store.refresh(true);
    assertThat(subjects()).isEmpty();

    Files.writeString(eml, content);
    store.refresh(false);
    assertThat(subjects()).containsExactly("Welcome");
  }

  @Test
  void forgetsAMailWhoseEmlFileWasDeletedAfterItWasRead() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Kept", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Deleted", "2026-11-01T10:00:02.000Z");
    store.refresh(true);
    long revision = store.snapshot().revision();

    Files.delete(run.directory().resolve("messages/000002.eml"));

    assertThat(store.refresh(false)).isTrue();
    assertThat(store.snapshot().revision()).isGreaterThan(revision);
    assertThat(subjects()).containsExactly("Kept");
  }

  @Test
  void skipsFilesOfAnUnknownFormatVersion() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    Map<String, Object> futureMail = new LinkedHashMap<>();
    futureMail.put("formatVersion", 2);
    futureMail.put("phase", "test");
    run.mail(futureMail, RecordFiles.eml("Future", "body"));
    run.testMail(SIGNUP, "sendsWelcome", "Current", "2026-11-01T10:00:01.000Z");
    Map<String, Object> futureRun = new LinkedHashMap<>();
    futureRun.put("formatVersion", 2);
    futureRun.put("startedAt", "2026-11-01T11:00:00.000Z");
    RecordFiles.write(output.directory().resolve("runs/run2/run.json"), futureRun);
    Path futureOutput = project.resolve("future/build/greenmail-test-viewer");
    RecordFiles.write(
        futureOutput.resolve(SourceFinder.MARKER_FILE_NAME), Map.of("formatVersion", 2));

    store.refresh(true);

    assertThat(store.snapshot().sources()).hasSize(1);
    assertThat(store.snapshot().sources().get(0).runs())
        .extracting(Run::name)
        .containsExactly("run1");
    assertThat(subjects()).containsExactly("Current");
  }

  @Test
  void skipsBrokenFilesAndReadsTheOthers() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    Files.writeString(messages.resolve("000002.eml"), RecordFiles.eml("Broken", "body"));
    Files.writeString(messages.resolve("000002.json"), "{ not json");
    Files.writeString(
        Files.createDirectories(run.directory().resolve("tests")).resolve("000001.json"), "[]");
    Files.createDirectories(output.directory().resolve("runs/being-created"));

    store.refresh(true);

    assertThat(subjects()).containsExactly("Good");
    assertThat(onlyRun().tests()).isEmpty();
  }

  @Test
  void keepsWhatCouldNotBeReadAsProblemsWhileItLasts() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    Path brokenEml = messages.resolve("000002.eml");
    Path brokenJson = messages.resolve("000002.json");
    Files.writeString(brokenEml, RecordFiles.eml("Broken", "body"));
    Files.writeString(brokenJson, "{ not json");
    Path badRun = Files.createDirectories(output.directory().resolve("runs/bad name"));
    Path future = project.resolve("future/build/greenmail-test-viewer");
    RecordFiles.write(future.resolve(SourceFinder.MARKER_FILE_NAME), Map.of("formatVersion", 2));

    store.refresh(true);

    assertThat(store.problems())
        .hasSize(3)
        .anyMatch(problem -> problem.contains(brokenJson.toString()) && problem.contains("JSON"))
        .anyMatch(problem -> problem.contains(badRun.toString()))
        .anyMatch(problem -> problem.contains(future.toString()) && problem.contains("version 2"));
    store.refresh(false);
    assertThat(store.problems()).anyMatch(problem -> problem.contains(brokenJson.toString()));

    Files.delete(brokenJson);
    Files.delete(brokenEml);
    Files.delete(badRun);
    Files.delete(future.resolve(SourceFinder.MARKER_FILE_NAME));
    store.refresh(true);

    assertThat(store.problems()).isEmpty();
    assertThat(subjects()).containsExactly("Good");
  }

  @Test
  void stopsReadingAnOutputDirectoryWhoseMarkerFileChangedAsAViewerStartedNowWould()
      throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "First", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    Path marker = output.directory().resolve(SourceFinder.MARKER_FILE_NAME);

    for (String changed : new String[] {"{\"formatVersion\": 2}", "{ broken"}) {
      Files.writeString(marker, changed);
      run.testMail(SIGNUP, "sendsWelcome", "Second", "2026-11-01T10:00:02.000Z");

      store.refresh(true);

      assertThat(subjects()).as(changed).isEmpty();
      assertThat(store.problems())
          .as(changed)
          .singleElement()
          .asString()
          .contains(output.directory().toString())
          .containsAnyOf("format version 2 is not supported", "is broken");

      Files.delete(marker);
      RecordFiles.write(marker, Map.of("formatVersion", 1));
      store.refresh(true);

      assertThat(store.problems()).as(changed).isEmpty();
      assertThat(subjects()).as(changed).contains("First", "Second");
    }
  }

  @Test
  void tellsWhyAnOutputDirectoryWithABrokenMarkerFileIsNotShown() throws IOException {
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Path marker = output.directory().resolve(SourceFinder.MARKER_FILE_NAME);

    for (String broken :
        new String[] {
          "{\"formatVersion\":",
          "",
          "[1]",
          "{}",
          "{\"formatVersion\": null}",
          "{\"formatVersion\": true}",
          "{\"formatVersion\": 1.5}",
          "{\"formatVersion\": \"1\"}"
        }) {
      Files.writeString(marker, broken);
      store.refresh(true);

      assertThat(subjects()).as(broken).isEmpty();
      assertThat(store.problems())
          .as(broken)
          .singleElement()
          .asString()
          .contains(marker.toString(), "is broken", "Delete the file");
    }

    Files.delete(marker);
    RecordFiles.write(marker, Map.of("formatVersion", 1));
    store.refresh(true);

    assertThat(store.problems()).isEmpty();
    assertThat(subjects()).containsExactly("Welcome");
  }

  @Test
  void tellsWhetherTheFormatVersionOfARecordFileIsBrokenOrOfAnotherVersion() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    Path messages = run.directory().resolve("messages");
    Files.writeString(messages.resolve("000002.eml"), RecordFiles.eml("Without", "body"));
    Files.writeString(messages.resolve("000002.json"), "{\"phase\": \"test\"}");
    Path tests = run.directory().resolve("tests");
    Map<String, String> versions = new LinkedHashMap<>();
    versions.put("true", "(formatVersion true is not an integer)");
    versions.put("1.5", "(formatVersion 1.5 is not an integer)");
    versions.put("\"1\"", "(formatVersion \"1\" is not an integer)");
    versions.put("4294967297", "format version 4294967297 is not supported");
    Map<Path, String> expected = new LinkedHashMap<>();
    expected.put(messages.resolve("000002.json"), "(no formatVersion)");
    int number = 2;
    for (Map.Entry<String, String> version : versions.entrySet()) {
      Path file = tests.resolve(String.format("%06d.json", number++));
      Files.writeString(
          file,
          "{\"formatVersion\": "
              + version.getKey()
              + ", \"kind\": \"method\", \"testClass\": \"com.example.SignupTest\","
              + " \"testMethod\": \"other\", \"displayName\": \"other()\","
              + " \"uniqueId\": \"[engine:junit-jupiter]/[class:com.example.SignupTest]"
              + "/[method:other()]\", \"finishedAt\": \"2026-11-01T10:00:03.000Z\","
              + " \"result\": \"passed\"}");
      expected.put(file, version.getValue());
    }

    store.refresh(true);

    assertThat(subjects()).containsExactly("Good");
    assertThat(onlyRun().tests()).hasSize(1);
    assertThat(store.problems()).hasSize(expected.size());
    for (Map.Entry<Path, String> file : expected.entrySet()) {
      assertThat(store.problems())
          .as(file.getKey().getFileName().toString())
          .anyMatch(problem ->
              problem.contains(file.getKey().toString()) && problem.contains(file.getValue()));
    }
    assertThat(store.problems())
        .filteredOn(problem -> problem.contains("invalid record format"))
        .hasSize(4)
        .noneMatch(problem -> problem.contains("Use a viewer"));
  }

  @Test
  void aBrokenTestRecordDoesNotHideTheMailWithTheSameNumber() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    Path brokenTest =
        Files.createDirectories(run.directory().resolve("tests")).resolve("000001.json");
    Files.writeString(brokenTest, "[]");
    FileTime time = FileTime.fromMillis(1_790_000_000_000L);
    Files.setLastModifiedTime(brokenTest, time);
    Files.setLastModifiedTime(run.directory().resolve("messages/000001.json"), time);

    store.refresh(true);

    assertThat(subjects()).containsExactly("Good");
  }

  @Test
  void readsAgainTheFilesThatCouldNotBeReadForAWhile() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "First", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Second", "2026-11-01T10:00:02.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Third", "2026-11-01T10:00:03.000Z");
    Runnable restoreJson = TestFiles.denyReading(run.directory().resolve("messages/000002.json"));
    assumeTrue(restoreJson != null, "The permission to read a file cannot be taken away here");
    Runnable restoreEml = TestFiles.denyReading(run.directory().resolve("messages/000003.eml"));
    try {
      store.refresh(true);
      assertThat(subjects()).containsExactly("First");
      assertThat(store.problems())
          .hasSize(2)
          .allMatch(problem ->
              problem.contains("permission denied")
                  && problem.contains("Start the viewer as a user who can read it."));
    } finally {
      restoreJson.run();
      restoreEml.run();
    }

    store.refresh(false);

    assertThat(subjects()).containsExactly("First", "Second", "Third");
    assertThat(store.problems()).isEmpty();
  }

  @Test
  void leavesTheFoldersOfARunAloneWhileTheyDoNotChange() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "First", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    FileTime old = anHourAgo();
    Files.setLastModifiedTime(messages, old);
    store.refresh(true);
    assertThat(subjects()).containsExactly("First");

    run.testMail(SIGNUP, "sendsWelcome", "Second", "2026-11-01T10:00:02.000Z");
    Files.setLastModifiedTime(messages, old);
    store.refresh(false);
    assertThat(subjects()).containsExactly("First");

    Files.setLastModifiedTime(messages, FileTime.from(Instant.now()));
    store.refresh(false);
    assertThat(subjects()).containsExactly("First", "Second");
  }

  @Test
  void keepsTheProblemsOfAFolderThatIsLeftAlone() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    Path broken = messages.resolve("000002.json");
    Files.writeString(messages.resolve("000002.eml"), RecordFiles.eml("Broken", "body"));
    Files.writeString(broken, "{ not json");
    Files.setLastModifiedTime(messages, anHourAgo());

    store.refresh(true);
    store.refresh(false);

    assertThat(store.problems()).anyMatch(problem -> problem.contains(broken.toString()));
    assertThat(subjects()).containsExactly("Good");
  }

  @Test
  void readsAgainAFileThatCouldNotBeReadAlsoInAFolderThatDidNotChange() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "First", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Second", "2026-11-01T10:00:02.000Z");
    Path messages = run.directory().resolve("messages");
    Runnable restore = TestFiles.denyReading(messages.resolve("000002.json"));
    assumeTrue(restore != null, "The permission to read a file cannot be taken away here");
    FileTime old = anHourAgo();
    try {
      Files.setLastModifiedTime(messages, old);
      store.refresh(true);
      assertThat(subjects()).containsExactly("First");
    } finally {
      restore.run();
    }
    Files.setLastModifiedTime(messages, old);

    store.refresh(false);

    assertThat(subjects()).containsExactly("First", "Second");
  }

  @Test
  void readsTheFoldersAgainAfterTheRunWasForgotten() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "First", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    FileTime old = anHourAgo();
    Files.setLastModifiedTime(messages, old);
    store.refresh(true);
    Path runJson = run.directory().resolve("run.json");
    String content = Files.readString(runJson);

    Files.delete(runJson);
    store.refresh(false);
    assertThat(subjects()).isEmpty();

    Files.writeString(runJson, content);
    Files.setLastModifiedTime(messages, old);
    store.refresh(false);
    assertThat(subjects()).containsExactly("First");
  }

  @Test
  void forgetsTheProblemOfASkippedFileThatWasDeleted() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Good", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    Path broken = messages.resolve("000002.json");
    Files.writeString(messages.resolve("000002.eml"), RecordFiles.eml("Broken", "body"));
    Files.writeString(broken, "{ not json");
    Files.setLastModifiedTime(messages, anHourAgo());
    store.refresh(true);
    assertThat(store.problems()).anyMatch(problem -> problem.contains(broken.toString()));

    Files.delete(broken);
    Files.setLastModifiedTime(messages, FileTime.from(Instant.now().minus(Duration.ofMinutes(30))));
    store.refresh(false);
    store.refresh(false);

    assertThat(store.problems()).isEmpty();
    assertThat(subjects()).containsExactly("Good");
  }

  @Test
  void readsAgainASkippedFileThatWasFixedInPlace() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Fixed", "2026-11-01T10:00:01.000Z");
    Path messages = run.directory().resolve("messages");
    Path json = messages.resolve("000001.json");
    String content = Files.readString(json);
    Files.writeString(json, "{ not json");
    Files.setLastModifiedTime(json, anHourAgo());
    FileTime old = anHourAgo();
    Files.setLastModifiedTime(messages, old);
    store.refresh(true);
    store.refresh(false);
    assertThat(subjects()).isEmpty();

    Files.writeString(json, content);
    Files.setLastModifiedTime(messages, old);
    store.refresh(false);

    assertThat(subjects()).containsExactly("Fixed");
    assertThat(store.problems()).isEmpty();
  }

  @Test
  void keepsWhatWasReadWhileRunJsonCannotBeRead() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    long revision = store.snapshot().revision();

    run.finish("2026-11-01T10:00:05.000Z");
    Path runJson = run.directory().resolve("run.json");
    Runnable restore = TestFiles.denyReading(runJson);
    assumeTrue(restore != null, "The permission to read a file cannot be taken away here");
    try {
      store.refresh(false);
      assertThat(subjects()).containsExactly("Welcome");
      assertThat(store.snapshot().revision()).isEqualTo(revision);
      assertThat(store.problems()).anyMatch(problem -> problem.contains(runJson.toString()));
    } finally {
      restore.run();
    }

    store.refresh(false);

    assertThat(onlyRun().finishedAt().text()).isEqualTo("2026-11-01T10:00:05.000Z");
    assertThat(subjects()).containsExactly("Welcome");
  }

  @Test
  void keepsARunWhoseRunJsonCannotBeLookedAtForAWhile() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    Path runJson = run.directory().resolve("run.json");

    Runnable restore = TestFiles.denyAttributes(runJson);
    assumeTrue(restore != null, "The permission to look at a file cannot be taken away here");
    try {
      store.refresh(false);
      assertThat(subjects()).containsExactly("Welcome");
      assertThat(store.problems()).anyMatch(problem -> problem.contains(runJson.toString()));
    } finally {
      restore.run();
    }

    store.refresh(false);
    assertThat(subjects()).containsExactly("Welcome");
    assertThat(store.problems()).isEmpty();
  }

  @Test
  void readsAgainAFileThatWasFixedWhileItWasRead() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    Path record = run.directory().resolve("tests").resolve("000001.json");
    String content = Files.readString(record);
    Files.writeString(record, "{ not json");
    Files.setLastModifiedTime(record, anHourAgo());
    boolean[] fixed = {false};
    ExecutorService fixingAfterReading =
        new InlineExecutor(() -> {
          if (!fixed[0]) {
            fixed[0] = true;
            Files.writeString(record, content);
            Files.setLastModifiedTime(record, FileTime.from(Instant.now()));
          }
        });
    SourceReader reader =
        new SourceReader(
            "s1",
            new SourceFinder.Found(output.directory(), project),
            new ObjectMapper(),
            new MailReader(),
            new Warnings(),
            fixingAfterReading);

    reader.scan();
    assertThat(reader.toSource().runs().get(0).tests()).isEmpty();
    reader.scan();

    assertThat(reader.toSource().runs().get(0).tests()).hasSize(1);
    assertThat(reader.problems()).isEmpty();
  }

  @Test
  void skipsARecordWithSomethingAfterItsJson() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:01.000Z");
    Path record = run.directory().resolve("tests").resolve("000001.json");
    Files.writeString(record, Files.readString(record) + "garbage");

    store.refresh(true);

    assertThat(onlyRun().tests()).isEmpty();
    assertThat(store.problems()).anyMatch(problem -> problem.contains(record.toString()));
  }

  @Test
  void keepsTheRunsThatDidNotChangeInTheNextSnapshot() {
    RunFiles first = output.run("run1", "2026-11-01T10:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Old", "2026-11-01T10:00:01.000Z");
    first.finish("2026-11-01T10:00:02.000Z");
    RunFiles second = output.run("run2", "2026-11-01T11:00:00.000Z");
    store.refresh(true);
    List<Run> before = store.snapshot().sources().get(0).runs();

    second.testMail(SIGNUP, "sendsWelcome", "New", "2026-11-01T11:00:01.000Z");
    assertThat(store.refresh(false)).isTrue();

    List<Run> after = store.snapshot().sources().get(0).runs();
    assertThat(after.get(0)).isSameAs(before.get(0));
    assertThat(after.get(1)).isNotSameAs(before.get(1));
    assertThat(after.get(1).mails()).hasSize(1);
  }

  @Test
  void keepsAnOutputDirectoryWhileAFolderAboveItCannotBeListed() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    assertThat(subjects()).containsExactly("Welcome");

    Runnable restore = TestFiles.denyListing(output.directory().getParent());
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      store.refresh(true);
      assertThat(subjects()).containsExactly("Welcome");
    } finally {
      restore.run();
    }
  }

  @Test
  void doesNotReadThroughALinkThatReplacedAFolderAboveAnOutputDirectory(@TempDir Path elsewhere)
      throws Exception {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    assertThat(subjects()).containsExactly("Welcome");

    RecordFiles.create(elsewhere.resolve("build/greenmail-test-viewer"))
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Elsewhere", "2026-11-01T11:00:01.000Z");
    Path app = project.resolve("app");
    deleteRecursively(app);
    TestFiles.link(app, elsewhere);
    try {
      assertThat(store.refresh(false)).isTrue();
      assertThat(subjects()).isEmpty();
      assertThat(store.refresh(true)).isFalse();
      assertThat(store.refresh(false)).isFalse();
      assertThat(subjects()).isEmpty();
    } finally {
      Files.delete(app);
    }
  }

  @Test
  void showsAnOutputDirectoryFromAnotherDirToWhichItIsNoLongerFoundFromItsOwn(
      @TempDir Path elsewhere) throws Exception {
    Path app = project.resolve("app");
    store = new RecordStore(List.of(project, app));
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    assertThat(sourcePaths()).containsExactly("app/build/greenmail-test-viewer");

    Path moved = elsewhere.resolve("app");
    Files.move(app, moved);
    TestFiles.link(app, moved);
    try {
      for (boolean discover : List.of(false, true)) {
        store.refresh(discover);
        assertThat(subjects()).as("discover: %s", discover).containsExactly("Welcome");
        assertThat(sourcePaths())
            .as("discover: %s", discover)
            .containsExactly("build/greenmail-test-viewer");
      }
    } finally {
      Files.delete(app);
    }

    Files.move(moved, app);
    store.refresh(false);
    assertThat(subjects()).containsExactly("Welcome");
    assertThat(sourcePaths()).containsExactly("app/build/greenmail-test-viewer");
  }

  @Test
  void doesNotCountUpTheRevisionWhenTheSearchFindsAnOutputDirectoryFromAnotherDir()
      throws IOException {
    store = new RecordStore(List.of(project, project.resolve("app/build")));
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    long revision = store.snapshot().revision();

    Runnable restore = TestFiles.denyListing(project.resolve("app"));
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      for (int i = 0; i < 2; i++) {
        assertThat(store.refresh(true)).isFalse();
      }
    } finally {
      restore.run();
    }
    assertThat(store.snapshot().revision()).isEqualTo(revision);
    assertThat(sourcePaths()).containsExactly("app/build/greenmail-test-viewer");
  }

  private List<String> sourcePaths() {
    return store.snapshot().sources().stream().map(Source::path).collect(Collectors.toList());
  }

  @Test
  void readsAnOutputDirectoryThatCannotBeListedAndTellsWhatIsNotFound() throws IOException {
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Runnable restore = TestFiles.denyListing(output.directory());
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      store.refresh(true);

      assertThat(subjects()).containsExactly("Welcome");
      assertThat(store.problems())
          .singleElement()
          .asString()
          .contains(output.directory().toString(), "Cannot look for other output directories below")
          .doesNotContain("records are not shown");

      RecordStore overlapping = new RecordStore(List.of(project, output.directory().getParent()));
      try {
        overlapping.refresh(true);
        assertThat(overlapping.problems()).hasSize(1);
      } finally {
        overlapping.close();
      }
    } finally {
      restore.run();
    }
  }

  @Test
  void skipsAndReportsAnOutputDirectoryWithABrokenMarkerThatCannotBeListed() throws IOException {
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Files.writeString(output.directory().resolve(SourceFinder.MARKER_FILE_NAME), "{ broken");
    Runnable restore = TestFiles.denyListing(output.directory());
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      store.refresh(true);

      assertThat(subjects()).isEmpty();
      assertThat(store.problems())
          .hasSize(2)
          .anyMatch(problem -> problem.contains("Cannot look for other output directories below"))
          .anyMatch(problem -> problem.contains("is broken"))
          .noneMatch(problem -> problem.contains("read as usual"));
    } finally {
      restore.run();
    }
  }

  @Test
  void keepsWhatWasReadWhileADirectoryCannotBeListed() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    long revision = store.snapshot().revision();

    for (Path directory :
        List.of(output.directory().resolve("runs"), run.directory().resolve("messages"))) {
      Runnable restore = TestFiles.denyListing(directory);
      assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
      try {
        assertThat(store.refresh(false)).as(directory.toString()).isFalse();
        assertThat(store.problems())
            .as(directory.toString())
            .singleElement()
            .asString()
            .contains(directory.toString(), "The records already read from it are kept")
            .doesNotContain("records are not shown");
      } finally {
        restore.run();
      }
      assertThat(store.snapshot().revision()).isEqualTo(revision);
      assertThat(subjects()).containsExactly("Welcome");
    }
  }

  @Test
  void keepsTheOutputDirectoriesFoundBelowAFolderThatCannotBeSearchedAnymore() throws IOException {
    output
        .run("run1", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    store.refresh(true);
    Path app = project.resolve("app");
    Runnable restore = TestFiles.denyListing(app);
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      store.refresh(true);

      assertThat(subjects()).containsExactly("Welcome");
      assertThat(store.problems())
          .singleElement()
          .asString()
          .contains(app.toString(), "Directories found earlier are kept")
          .doesNotContain("records are not shown");
    } finally {
      restore.run();
    }
  }

  @Test
  void aFailedScanDoesNotStopTheNextOnes() {
    assertThatCode(() ->
            RecordStore.runScan(() -> {
              throw new OutOfMemoryError("thrown by the test");
            }))
        .doesNotThrowAnyException();
    assertThatCode(() ->
            RecordStore.runScan(() -> {
              throw new IllegalStateException("thrown by the test");
            }))
        .doesNotThrowAnyException();
  }

  @Test
  void showsWhatAFailedScanReadOnceAScanSucceeds() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Broken", "2026-11-01T10:00:02.000Z");
    Path broken = run.directory().resolve("messages").resolve("000002.eml");
    RecordStore failingStore =
        storeReading(eml -> {
          if (eml.equals(broken)) {
            throw new Error("thrown by the test");
          }
        });
    try {
      assertThatThrownBy(() -> failingStore.refresh(true)).isInstanceOf(Error.class);
      assertThat(failingStore.snapshot().sources()).isEmpty();

      Files.delete(broken);
      Files.delete(broken.resolveSibling("000002.json"));
      assertThat(failingStore.refresh(true)).isTrue();
      assertThat(subjectsOf(failingStore)).containsExactly("Welcome");
    } finally {
      failingStore.close();
    }
  }

  @Test
  void startsAlsoWhenTheFirstScanFails() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Broken", "2026-11-01T10:00:02.000Z");
    Path broken = run.directory().resolve("messages").resolve("000002.eml");
    RecordStore failingStore =
        storeReading(eml -> {
          if (eml.equals(broken)) {
            throw new Error("thrown by the test");
          }
        });
    try {
      assertThatCode(failingStore::start).doesNotThrowAnyException();
      assertThat(failingStore.isClosed()).isFalse();

      Files.delete(broken);
      Files.delete(broken.resolveSibling("000002.json"));
      failingStore.refresh(false);
      assertThat(subjectsOf(failingStore)).containsExactly("Welcome");
    } finally {
      failingStore.close();
    }
  }

  @Test
  void showsWhatAFailedScanReadInARunThatWasShown() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Path broken = run.directory().resolve("messages").resolve("000003.eml");
    RecordStore failingStore =
        storeReading(eml -> {
          if (eml.equals(broken)) {
            throw new Error("thrown by the test");
          }
        });
    try {
      failingStore.refresh(true);
      assertThat(subjectsOf(failingStore)).containsExactly("Welcome");

      run.testMail(SIGNUP, "sendsReminder", "Second", "2026-11-01T10:00:02.000Z");
      run.testMail(SIGNUP, "sendsReport", "Broken", "2026-11-01T10:00:03.000Z");
      assertThatThrownBy(() -> failingStore.refresh(false)).isInstanceOf(Error.class);

      Files.delete(broken);
      Files.delete(broken.resolveSibling("000003.json"));
      assertThat(failingStore.refresh(false)).isTrue();
      assertThat(subjectsOf(failingStore)).containsExactly("Welcome", "Second");
    } finally {
      failingStore.close();
    }
  }

  @Test
  void skipsAMailTooLargeToReadUntilItChanges() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Huge", "2026-11-01T10:00:02.000Z");
    run.testMail(SIGNUP, "sendsGoodbye", "Goodbye", "2026-11-01T10:00:03.000Z");
    Path huge = run.directory().resolve("messages").resolve("000002.eml");
    AtomicInteger reads = new AtomicInteger();
    AtomicBoolean enoughMemory = new AtomicBoolean(false);
    RecordStore storeWithLittleMemory =
        storeReading(eml -> {
          if (eml.equals(huge)) {
            reads.incrementAndGet();
            if (!enoughMemory.get()) {
              throw new OutOfMemoryError("Java heap space (thrown by the test)");
            }
          }
        });
    try {
      assertThatCode(() -> storeWithLittleMemory.refresh(true)).doesNotThrowAnyException();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome", "Goodbye");
      assertThat(storeWithLittleMemory.problems())
          .anyMatch(problem -> problem.contains(huge.toString()) && problem.contains("-Xmx"));
      assertThat(reads).hasValue(2);
      assertThat(storeWithLittleMemory.refresh(false)).isFalse();
      assertThat(reads).hasValue(2);
      assertThat(storeWithLittleMemory.problems())
          .anyMatch(problem -> problem.contains(huge.toString()));

      enoughMemory.set(true);
      Files.setLastModifiedTime(huge.resolveSibling("000002.json"), FileTime.from(Instant.now()));
      assertThat(storeWithLittleMemory.refresh(false)).isTrue();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome", "Huge", "Goodbye");
      assertThat(storeWithLittleMemory.problems()).isEmpty();
    } finally {
      storeWithLittleMemory.close();
    }
  }

  @Test
  void readsASkippedMailAgainWhenItsEmlFileChanges() throws IOException {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Huge", "2026-11-01T10:00:02.000Z");
    Path huge = run.directory().resolve("messages").resolve("000002.eml");
    AtomicBoolean replaced = new AtomicBoolean(false);
    RecordStore storeWithLittleMemory =
        storeReading(eml -> {
          if (eml.equals(huge) && !replaced.get()) {
            throw new OutOfMemoryError("Java heap space (thrown by the test)");
          }
        });
    try {
      assertThatCode(() -> storeWithLittleMemory.refresh(true)).doesNotThrowAnyException();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome");

      replaced.set(true);
      Files.setLastModifiedTime(huge, FileTime.from(Instant.now().plusSeconds(10)));
      assertThat(storeWithLittleMemory.refresh(false)).isTrue();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome", "Huge");
      assertThat(storeWithLittleMemory.problems()).isEmpty();
    } finally {
      storeWithLittleMemory.close();
    }
  }

  @Test
  void readsAgainAMailWhoseEmlFileWasFixedWhileItWasRead() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Huge", "2026-11-01T10:00:02.000Z");
    Path huge = run.directory().resolve("messages").resolve("000002.eml");
    AtomicInteger reads = new AtomicInteger();
    RecordStore storeWithLittleMemory =
        storeReading(eml -> {
          if (!eml.equals(huge)) {
            return;
          }
          int read = reads.incrementAndGet();
          if (read == 2) {
            try {
              Files.setLastModifiedTime(huge, FileTime.from(Instant.now().plusSeconds(10)));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
          if (read <= 2) {
            throw new OutOfMemoryError("Java heap space (thrown by the test)");
          }
        });
    try {
      assertThatCode(() -> storeWithLittleMemory.refresh(true)).doesNotThrowAnyException();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome");

      assertThat(storeWithLittleMemory.refresh(false)).isTrue();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome", "Huge");
    } finally {
      storeWithLittleMemory.close();
    }
  }

  @Test
  void readsAgainAMailThatRanOutOfMemoryWhileItWasReadAhead() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    Path welcome = run.directory().resolve("messages").resolve("000001.eml");
    AtomicInteger reads = new AtomicInteger();
    RecordStore storeWithLittleMemory =
        storeReading(eml -> {
          if (eml.equals(welcome) && reads.incrementAndGet() == 1) {
            throw new OutOfMemoryError("Java heap space (thrown by the test)");
          }
        });
    try {
      assertThatCode(() -> storeWithLittleMemory.refresh(true)).doesNotThrowAnyException();
      assertThat(subjectsOf(storeWithLittleMemory)).containsExactly("Welcome");
      assertThat(storeWithLittleMemory.problems()).isEmpty();
    } finally {
      storeWithLittleMemory.close();
    }
  }

  @Test
  void skipsAMailNestedTooDeeplyToRead() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.testMail(SIGNUP, "sendsReport", "Nested", "2026-11-01T10:00:02.000Z");
    Path nested = run.directory().resolve("messages").resolve("000002.eml");
    RecordStore storeOverflowing =
        storeReading(eml -> {
          if (eml.equals(nested)) {
            throw new StackOverflowError("thrown by the test");
          }
        });
    try {
      assertThatCode(() -> storeOverflowing.refresh(true)).doesNotThrowAnyException();
      assertThat(subjectsOf(storeOverflowing)).containsExactly("Welcome");
      assertThat(storeOverflowing.problems())
          .anyMatch(problem -> problem.contains(nested.toString()));
    } finally {
      storeOverflowing.close();
    }
  }

  private RecordStore storeReading(Consumer<Path> check) {
    MailReader reader =
        new MailReader() {
          @Override
          MimeMessage read(Path eml) throws IOException {
            check.accept(eml);
            return super.read(eml);
          }
        };
    return new RecordStore(List.of(project), new SourceFinder(), reader);
  }

  private static List<String> subjectsOf(RecordStore store) {
    return store.snapshot().sources().stream()
        .flatMap(source -> source.runs().stream())
        .flatMap(run -> run.mails().stream())
        .map(mail -> mail.summary().subject())
        .collect(Collectors.toList());
  }

  @Test
  void closesWithoutWaitingForAScanThatRuns() throws Exception {
    CountDownLatch scanning = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread scan =
        new Thread(() -> {
          synchronized (store) {
            scanning.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    scan.start();
    scanning.await();
    try {
      CompletableFuture.runAsync(store::close).get(10, TimeUnit.SECONDS);
      assertThat(store.isClosed()).isTrue();
    } finally {
      release.countDown();
      scan.join();
    }
  }

  @Test
  void aStoreThatWasClosedStartsQuietly() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");

    store.close();

    assertThatCode(store::start).doesNotThrowAnyException();
    assertThat(store.isClosed()).isTrue();
  }

  @Test
  void givesMailsIdsThatCanBeUsedInUrls() {
    output
        .run("20261101T100000Z-123-0a1b2c3d", "2026-11-01T10:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");

    store.refresh(true);

    assertThat(mailIds())
        .singleElement()
        .asString()
        .matches("s[0-9a-f]{10}\\.20261101T100000Z-123-0a1b2c3d\\.000001");
    assertThat(store.snapshot().sources().get(0).id())
        .isEqualTo(RecordStore.sourceId(output.directory().toAbsolutePath().normalize()));
  }

  private Run onlyRun() {
    List<Run> runs = store.snapshot().sources().get(0).runs();
    assertThat(runs).hasSize(1);
    return runs.get(0);
  }

  private List<MailRecord> mails() {
    Snapshot snapshot = store.snapshot();
    return snapshot.sources().stream()
        .flatMap(source -> source.runs().stream())
        .flatMap(run -> run.mails().stream())
        .collect(Collectors.toList());
  }

  private static final class InlineExecutor extends AbstractExecutorService {
    private final IoAction afterEachTask;
    private boolean shutdown;

    InlineExecutor(IoAction afterEachTask) {
      this.afterEachTask = afterEachTask;
    }

    @Override
    public void execute(Runnable task) {
      task.run();
      try {
        afterEachTask.run();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  @FunctionalInterface
  private interface IoAction {
    void run() throws IOException;
  }

  private static FileTime anHourAgo() {
    return FileTime.from(Instant.now().minus(Duration.ofHours(1)));
  }

  private List<String> subjects() {
    return mails().stream().map(mail -> mail.summary().subject()).collect(Collectors.toList());
  }

  private List<String> mailIds() {
    return mails().stream().map(MailRecord::id).collect(Collectors.toList());
  }

  static void deleteRecursively(Path directory) throws IOException {
    try (Stream<Path> files = Files.walk(directory)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.delete(file);
      }
    }
  }
}
