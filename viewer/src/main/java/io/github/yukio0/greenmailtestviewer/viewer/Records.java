package io.github.yukio0.greenmailtestviewer.viewer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The records read from the output directories, all immutable. */
final class Records {

  static final int FORMAT_VERSION = 1;

  private Records() {}

  static final class Source {
    private final String id;
    private final Path directory;
    private final String name;
    private final String path;
    private final List<Run> runs;

    Source(String id, Path directory, String name, String path, List<Run> runs) {
      this.id = id;
      this.directory = directory;
      this.name = name;
      this.path = path;
      this.runs = List.copyOf(runs);
    }

    String id() {
      return id;
    }

    Path directory() {
      return directory;
    }

    String name() {
      return name;
    }

    String path() {
      return path;
    }

    List<Run> runs() {
      return runs;
    }

    Run latestRun() {
      return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }
  }

  static final class Run {
    private final String id;
    private final String name;
    private final Timestamp startedAt;
    private final Timestamp finishedAt;
    private final Long pid;
    private final String workingDirectory;
    private final String javaVersion;
    private final String greenMailVersion;
    private final List<TestRecord> tests;
    private final List<MailRecord> mails;

    Run(
        String id,
        String name,
        Timestamp startedAt,
        Timestamp finishedAt,
        Long pid,
        String workingDirectory,
        String javaVersion,
        String greenMailVersion,
        List<TestRecord> tests,
        List<MailRecord> mails) {
      this.id = id;
      this.name = name;
      this.startedAt = startedAt;
      this.finishedAt = finishedAt;
      this.pid = pid;
      this.workingDirectory = workingDirectory;
      this.javaVersion = javaVersion;
      this.greenMailVersion = greenMailVersion;
      this.tests = List.copyOf(tests);
      this.mails = List.copyOf(mails);
    }

    String id() {
      return id;
    }

    String name() {
      return name;
    }

    Timestamp startedAt() {
      return startedAt;
    }

    Timestamp finishedAt() {
      return finishedAt;
    }

    Long pid() {
      return pid;
    }

    String workingDirectory() {
      return workingDirectory;
    }

    String javaVersion() {
      return javaVersion;
    }

    String greenMailVersion() {
      return greenMailVersion;
    }

    List<TestRecord> tests() {
      return tests;
    }

    List<MailRecord> mails() {
      return mails;
    }

    Instant end() {
      if (finishedAt != null) {
        return finishedAt.instant();
      }
      Instant end = startedAt.instant();
      for (TestRecord test : tests) {
        if (test.finishedAt() != null && test.finishedAt().instant().isAfter(end)) {
          end = test.finishedAt().instant();
        }
      }
      for (MailRecord mail : mails) {
        if (mail.capturedAt() != null && mail.capturedAt().instant().isAfter(end)) {
          end = mail.capturedAt().instant();
        }
      }
      return end;
    }
  }

  static final class TestRecord {
    private final String kind;
    private final String testClass;
    private final String classDisplayName;
    private final String testMethod;
    private final String displayName;
    private final String uniqueId;
    private final Timestamp finishedAt;
    private final String result;
    private final String stage;
    private final String error;

    TestRecord(
        String kind,
        String testClass,
        String classDisplayName,
        String testMethod,
        String displayName,
        String uniqueId,
        Timestamp finishedAt,
        String result,
        String stage,
        String error) {
      this.kind = kind;
      this.testClass = testClass;
      this.classDisplayName = classDisplayName;
      this.testMethod = testMethod;
      this.displayName = displayName;
      this.uniqueId = uniqueId;
      this.finishedAt = finishedAt;
      this.result = result;
      this.stage = stage;
      this.error = error;
    }

    boolean isMethod() {
      return "method".equals(kind);
    }

    boolean isClass() {
      return "class".equals(kind);
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

    String uniqueId() {
      return uniqueId;
    }

    Timestamp finishedAt() {
      return finishedAt;
    }

    String result() {
      return result;
    }

    boolean isAborted() {
      return "aborted".equals(result);
    }

    String stage() {
      return stage;
    }

    String error() {
      return error;
    }
  }

  static final class MailRecord {
    static final String PHASE_TEST = "test";
    static final String PHASE_OUTSIDE_TEST = "outsideTest";
    static final String PHASE_OUTSIDE_CLASS = "outsideClass";
    static final String PHASE_AMBIGUOUS = "ambiguous";

    private final String id;
    private final Path eml;
    private final String phase;
    private final TestRef test;
    private final Timestamp capturedAt;
    private final List<String> deliveredTo;
    private final List<TestRef> candidates;
    private final MailSummary summary;
    private final String searchText;

    MailRecord(
        String id,
        Path eml,
        String phase,
        TestRef test,
        Timestamp capturedAt,
        List<String> deliveredTo,
        List<TestRef> candidates,
        MailSummary summary) {
      this.id = id;
      this.eml = eml;
      this.phase = phase;
      this.test = test;
      this.capturedAt = capturedAt;
      this.deliveredTo = List.copyOf(deliveredTo);
      this.candidates = List.copyOf(candidates);
      this.summary = summary;
      this.searchText = searchText(summary, test, deliveredTo, candidates);
    }

    private static String searchText(
        MailSummary summary, TestRef test, List<String> deliveredTo, List<TestRef> candidates) {
      List<String> parts = new ArrayList<>();
      parts.add(summary.subject());
      parts.add(summary.from());
      parts.addAll(summary.to());
      parts.addAll(summary.cc());
      parts.addAll(deliveredTo);
      parts.add(summary.bodyText());
      parts.add(summary.otherHtmlText());
      for (TestRef ref : concat(test, candidates)) {
        parts.add(ref.testClass());
        parts.add(ref.classDisplayName());
        parts.add(ref.testMethod());
        parts.add(ref.displayName());
      }
      StringBuilder text = new StringBuilder();
      for (String part : parts) {
        if (part != null) {
          text.append(part.toLowerCase(Locale.ROOT)).append('\n');
        }
      }
      return text.toString();
    }

    private static List<TestRef> concat(TestRef first, List<TestRef> rest) {
      List<TestRef> all = new ArrayList<>();
      all.add(first);
      all.addAll(rest);
      return all;
    }

    boolean matches(List<String> lowerCaseTerms) {
      for (String term : lowerCaseTerms) {
        if (!searchText.contains(term)) {
          return false;
        }
      }
      return true;
    }

    String id() {
      return id;
    }

    Path eml() {
      return eml;
    }

    String phase() {
      return phase;
    }

    TestRef test() {
      return test;
    }

    Timestamp capturedAt() {
      return capturedAt;
    }

    List<String> deliveredTo() {
      return deliveredTo;
    }

    List<TestRef> candidates() {
      return candidates;
    }

    MailSummary summary() {
      return summary;
    }
  }

  static final class TestRef {
    private final String testClass;
    private final String classDisplayName;
    private final String testMethod;
    private final String displayName;
    private final String uniqueId;

    TestRef(
        String testClass,
        String classDisplayName,
        String testMethod,
        String displayName,
        String uniqueId) {
      this.testClass = testClass;
      this.classDisplayName = classDisplayName;
      this.testMethod = testMethod;
      this.displayName = displayName;
      this.uniqueId = uniqueId;
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

    String uniqueId() {
      return uniqueId;
    }
  }

  static final class MailSummary {
    private final String subject;
    private final String from;
    private final List<String> to;
    private final List<String> cc;
    private final String sentAt;
    private final String bodyText;
    private final String otherHtmlText;
    private final int attachmentCount;

    MailSummary(
        String subject,
        String from,
        List<String> to,
        List<String> cc,
        String sentAt,
        String bodyText,
        String otherHtmlText,
        int attachmentCount) {
      this.subject = subject;
      this.from = from;
      this.to = List.copyOf(to);
      this.cc = List.copyOf(cc);
      this.sentAt = sentAt;
      this.bodyText = bodyText;
      this.otherHtmlText = otherHtmlText;
      this.attachmentCount = attachmentCount;
    }

    String subject() {
      return subject;
    }

    String from() {
      return from;
    }

    List<String> to() {
      return to;
    }

    List<String> cc() {
      return cc;
    }

    String sentAt() {
      return sentAt;
    }

    String bodyText() {
      return bodyText;
    }

    String otherHtmlText() {
      return otherHtmlText;
    }

    int attachmentCount() {
      return attachmentCount;
    }
  }

  static final class Timestamp {
    private final String text;
    private final Instant instant;

    Timestamp(String text, Instant instant) {
      this.text = text;
      this.instant = instant;
    }

    String text() {
      return text;
    }

    Instant instant() {
      return instant;
    }
  }
}
