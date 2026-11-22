package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RecordedRun {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path runDirectory;
  private final List<RecordedMail> mails;
  private final List<JsonNode> tests;

  private RecordedRun(Path runDirectory, List<RecordedMail> mails, List<JsonNode> tests) {
    this.runDirectory = runDirectory;
    this.mails = mails;
    this.tests = tests;
  }

  static RecordedRun read(Path outputDirectory) throws IOException {
    List<Path> runs = runDirectories(outputDirectory);
    assertThat(runs).as("run directories").hasSize(1);
    return readRunDirectory(runs.get(0));
  }

  static RecordedRun readRunDirectory(Path runDirectory) throws IOException {
    List<RecordedMail> mails = new ArrayList<>();
    for (Path json : list(runDirectory.resolve("messages"))) {
      String fileName = json.getFileName().toString();
      if (fileName.endsWith(".json")) {
        Path eml = json.resolveSibling(fileName.replace(".json", ".eml"));
        mails.add(new RecordedMail(MAPPER.readTree(json.toFile()), subjectOf(eml)));
      }
    }
    List<JsonNode> tests = new ArrayList<>();
    for (Path json : list(runDirectory.resolve("tests"))) {
      tests.add(MAPPER.readTree(json.toFile()));
    }
    return new RecordedRun(runDirectory, mails, tests);
  }

  Path runDirectory() {
    return runDirectory;
  }

  List<RecordedMail> mails() {
    return mails;
  }

  List<JsonNode> tests() {
    return tests;
  }

  RecordedMail mail(String subject) {
    List<RecordedMail> matching = mails(subject);
    assertThat(matching).as("mails with subject '%s'", subject).hasSize(1);
    return matching.get(0);
  }

  List<RecordedMail> mails(String subject) {
    return mails.stream().filter(mail -> subject.equals(mail.subject)).collect(Collectors.toList());
  }

  List<String> subjectsOf(String testMethod) {
    return mails.stream()
        .filter(mail -> testMethod.equals(mail.json.path("testMethod").asText(null)))
        .map(mail -> mail.subject)
        .collect(Collectors.toList());
  }

  JsonNode test(String testMethod) {
    List<JsonNode> matching =
        tests.stream()
            .filter(test -> "method".equals(test.path("kind").asText()))
            .filter(test -> testMethod.equals(test.path("testMethod").asText()))
            .collect(Collectors.toList());
    assertThat(matching).as("test records of '%s'", testMethod).hasSize(1);
    return matching.get(0);
  }

  List<JsonNode> classFailures() {
    return tests.stream()
        .filter(test -> "class".equals(test.path("kind").asText()))
        .collect(Collectors.toList());
  }

  JsonNode classFailure(Class<?> testClass) {
    List<JsonNode> matching =
        classFailures().stream()
            .filter(test -> testClass.getName().equals(test.path("testClass").asText()))
            .collect(Collectors.toList());
    assertThat(matching).as("class failure records of %s", testClass.getName()).hasSize(1);
    return matching.get(0);
  }

  JsonNode classFailure(Class<?> testClass, String stage) {
    List<JsonNode> matching =
        classFailures().stream()
            .filter(test -> testClass.getName().equals(test.path("testClass").asText()))
            .filter(test -> stage.equals(test.path("stage").asText()))
            .collect(Collectors.toList());
    assertThat(matching)
        .as("class failure records of %s at %s", testClass.getName(), stage)
        .hasSize(1);
    return matching.get(0);
  }

  static List<Path> runDirectories(Path outputDirectory) throws IOException {
    List<Path> runs = new ArrayList<>();
    for (Path entry : list(outputDirectory.resolve("runs"))) {
      if (Files.isDirectory(entry)) {
        runs.add(entry);
      }
    }
    return runs;
  }

  private static List<Path> list(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.sorted().collect(Collectors.toList());
    }
  }

  private static String subjectOf(Path eml) {
    try (InputStream in = Files.newInputStream(eml)) {
      return new MimeMessage((Session) null, in).getSubject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (MessagingException e) {
      throw new IllegalStateException(e);
    }
  }

  static final class RecordedMail {
    final JsonNode json;
    final String subject;

    RecordedMail(JsonNode json, String subject) {
      this.json = json;
      this.subject = subject;
    }

    String phase() {
      return json.path("phase").asText();
    }

    String testClass() {
      return json.path("testClass").asText(null);
    }

    String testMethod() {
      return json.path("testMethod").asText(null);
    }

    String displayName() {
      return json.path("displayName").asText(null);
    }

    String uniqueId() {
      return json.path("uniqueId").asText(null);
    }

    List<String> deliveredTo() {
      List<String> addresses = new ArrayList<>();
      json.path("deliveredTo").forEach(address -> addresses.add(address.asText()));
      return addresses;
    }
  }
}
