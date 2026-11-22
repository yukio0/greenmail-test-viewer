package io.github.yukio0.greenmailtestviewer.viewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecordFiles {

  static final String ENGINE = "[engine:junit-jupiter]";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path directory;

  private RecordFiles(Path directory) {
    this.directory = directory;
  }

  static RecordFiles create(Path directory) {
    write(directory.resolve(SourceFinder.MARKER_FILE_NAME), Map.of("formatVersion", 1));
    return new RecordFiles(directory);
  }

  Path directory() {
    return directory;
  }

  RunFiles run(String name, String startedAt) {
    Path run = directory.resolve("runs").resolve(name);
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("formatVersion", 1);
    json.put("startedAt", startedAt);
    json.put("pid", 1234);
    write(run.resolve("run.json"), json);
    return new RunFiles(run, json);
  }

  static String classId(String testClass) {
    return ENGINE + "/[class:" + testClass + "]";
  }

  static String methodId(String testClass, String method) {
    return classId(testClass) + "/[method:" + method + "()]";
  }

  static void write(Path file, Object json) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, MAPPER.writeValueAsBytes(json));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String eml(String subject, String body) {
    return "From: sender@example.com\r\n"
        + "To: receiver@example.com\r\n"
        + "Subject: "
        + subject
        + "\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "\r\n"
        + body
        + "\r\n";
  }

  static final class RunFiles {
    private final Path directory;
    private final Map<String, Object> runJson;
    private int tests;
    private int mails;

    RunFiles(Path directory, Map<String, Object> runJson) {
      this.directory = directory;
      this.runJson = runJson;
    }

    Path directory() {
      return directory;
    }

    RunFiles finish(String finishedAt) {
      runJson.put("finishedAt", finishedAt);
      write(directory.resolve("run.json"), runJson);
      return this;
    }

    RunFiles test(String testClass, String method, String result, String finishedAt) {
      return testWithId(methodId(testClass, method), testClass, method, result, finishedAt);
    }

    String classInvocationTest(
        String testClass,
        int invocation,
        String classDisplayName,
        String method,
        String subject,
        String capturedAt) {
      return invocationTest(
          "[engine:junit-jupiter]/[class-template:"
              + testClass
              + "]/[class-template-invocation:#"
              + invocation
              + "]",
          testClass,
          classDisplayName,
          method,
          subject,
          capturedAt);
    }

    String invocationTest(
        String invocationId,
        String testClass,
        String classDisplayName,
        String method,
        String subject,
        String capturedAt) {
      String uniqueId = invocationId + "/[method:" + method + "()]";
      Map<String, Object> test = new LinkedHashMap<>();
      test.put("formatVersion", 1);
      test.put("kind", "method");
      test.put("testClass", testClass);
      test.put("classDisplayName", classDisplayName);
      test.put("testMethod", method);
      test.put("displayName", method + "()");
      test.put("uniqueId", uniqueId);
      test.put("finishedAt", capturedAt);
      test.put("result", "passed");
      write(directory.resolve("tests").resolve(String.format("%06d.json", ++tests)), test);
      Map<String, Object> json = mailJson("test", capturedAt);
      json.put("testClass", testClass);
      json.put("classDisplayName", classDisplayName);
      json.put("testMethod", method);
      json.put("displayName", method + "()");
      json.put("uniqueId", uniqueId);
      return mail(json, eml(subject, "Body of " + subject));
    }

    RunFiles testWithId(
        String uniqueId, String testClass, String method, String result, String finishedAt) {
      return testWithId(uniqueId, testClass, method, method + "()", result, finishedAt);
    }

    RunFiles testWithId(
        String uniqueId,
        String testClass,
        String method,
        String displayName,
        String result,
        String finishedAt) {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("formatVersion", 1);
      json.put("kind", "method");
      json.put("testClass", testClass);
      json.put("testMethod", method);
      json.put("displayName", displayName);
      json.put("uniqueId", uniqueId);
      json.put("finishedAt", finishedAt);
      json.put("result", result);
      if (!"passed".equals(result) && !"disabled".equals(result)) {
        json.put("error", "java.lang.AssertionError: " + method + " " + result);
      }
      write(directory.resolve("tests").resolve(String.format("%06d.json", ++tests)), json);
      return this;
    }

    RunFiles classFailure(String testClass, String uniqueId, String stage, String finishedAt) {
      return classFailure(testClass, null, uniqueId, stage, finishedAt);
    }

    RunFiles classFailure(
        String testClass,
        String classDisplayName,
        String uniqueId,
        String stage,
        String finishedAt) {
      return classRecord(
          testClass,
          classDisplayName,
          uniqueId,
          stage,
          "failed",
          "java.lang.IllegalStateException: " + stage + " failed",
          finishedAt);
    }

    RunFiles classAborted(String testClass, String uniqueId, String stage, String finishedAt) {
      return classRecord(
          testClass,
          null,
          uniqueId,
          stage,
          "aborted",
          "org.opentest4j.TestAbortedException: Assumption failed: " + stage + " aborted",
          finishedAt);
    }

    private RunFiles classRecord(
        String testClass,
        String classDisplayName,
        String uniqueId,
        String stage,
        String result,
        String error,
        String finishedAt) {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("formatVersion", 1);
      json.put("kind", "class");
      json.put("testClass", testClass);
      if (classDisplayName != null) {
        json.put("classDisplayName", classDisplayName);
      }
      json.put("uniqueId", uniqueId);
      json.put("finishedAt", finishedAt);
      json.put("result", result);
      json.put("stage", stage);
      json.put("error", error);
      write(directory.resolve("tests").resolve(String.format("%06d.json", ++tests)), json);
      return this;
    }

    String testMail(String testClass, String method, String subject, String capturedAt) {
      return testMail(
          testClass,
          method,
          eml(subject, "Body of " + subject).getBytes(StandardCharsets.UTF_8),
          capturedAt);
    }

    String testMail(String testClass, String method, byte[] eml, String capturedAt) {
      Map<String, Object> json = mailJson("test", capturedAt);
      json.put("testClass", testClass);
      json.put("testMethod", method);
      json.put("displayName", method + "()");
      json.put("uniqueId", methodId(testClass, method));
      return mail(json, eml);
    }

    String outsideTestMail(String testClass, String subject, String capturedAt) {
      return outsideTestMail(testClass, classId(testClass), subject, capturedAt);
    }

    String outsideTestMail(String testClass, String uniqueId, String subject, String capturedAt) {
      Map<String, Object> json = mailJson("outsideTest", capturedAt);
      json.put("testClass", testClass);
      json.put("uniqueId", uniqueId);
      return mail(json, eml(subject, "Body of " + subject));
    }

    String outsideClassMail(String subject, String capturedAt) {
      return mail(mailJson("outsideClass", capturedAt), eml(subject, "Body of " + subject));
    }

    String ambiguousMail(String subject, String capturedAt, String[]... candidates) {
      Map<String, Object> json = mailJson("ambiguous", capturedAt);
      List<Object> list = new ArrayList<>();
      for (String[] candidate : candidates) {
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("testClass", candidate[0]);
        test.put("testMethod", candidate[1]);
        test.put("displayName", candidate[1] + "()");
        test.put("uniqueId", methodId(candidate[0], candidate[1]));
        list.add(test);
      }
      json.put("candidates", list);
      return mail(json, eml(subject, "Body of " + subject));
    }

    String mail(Map<String, Object> json, String eml) {
      return mail(json, eml.getBytes(StandardCharsets.UTF_8));
    }

    String mail(Map<String, Object> json, byte[] eml) {
      String number = String.format("%06d", ++mails);
      Path messages = directory.resolve("messages");
      try {
        Files.createDirectories(messages);
        Files.write(messages.resolve(number + ".eml"), eml);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      write(messages.resolve(number + ".json"), json);
      return number;
    }

    private static Map<String, Object> mailJson(String phase, String capturedAt) {
      Map<String, Object> json = new LinkedHashMap<>();
      json.put("formatVersion", 1);
      json.put("testClass", null);
      json.put("testMethod", null);
      json.put("displayName", null);
      json.put("uniqueId", null);
      json.put("phase", phase);
      json.put("capturedAt", capturedAt);
      json.put("deliveredTo", List.of("receiver@example.com"));
      return json;
    }
  }
}
