package io.github.yukio0.greenmailtestviewer.viewer;

import static io.github.yukio0.greenmailtestviewer.viewer.RecordFileReader.fileVersion;
import static io.github.yukio0.greenmailtestviewer.viewer.RecordFileReader.lastModified;
import static io.github.yukio0.greenmailtestviewer.viewer.RecordFileReader.result;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFileReader.FileContent;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFileReader.UnusableFileException;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailSummary;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRef;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Timestamp;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the records of one run directory and keeps what was read. Its directories are not listed
 * again while they do not change, so that finished runs cost almost nothing.
 */
final class RunReader {

  private static final Pattern RECORD_FILE = Pattern.compile("([0-9]+)\\.json");
  private static final Duration SETTLED = Duration.ofSeconds(5);

  private final RecordFileReader files;
  private final MailReader mailReader;
  private final String sourceId;
  private final String name;
  private final Path directory;
  // The size changes too when the finish time is added, even where times are stored in whole
  // seconds.
  private String infoVersion;
  private JsonNode info;
  private Timestamp startedAt;
  private final Map<String, TestRecord> tests = new TreeMap<>();
  private final Map<String, MailRecord> mails = new TreeMap<>();
  private final Map<Path, Skipped> skipped = new HashMap<>();
  private final ListedFolder testsFolder = new ListedFolder();
  private final ListedFolder messagesFolder = new ListedFolder();
  private Run run;

  RunReader(
      RecordFileReader files, MailReader mailReader, String sourceId, String name, Path directory) {
    this.files = files;
    this.mailReader = mailReader;
    this.sourceId = sourceId;
    this.name = name;
    this.directory = directory;
  }

  boolean scan() {
    boolean changed;
    try {
      changed = scanRun();
    } catch (RuntimeException | Error e) {
      run = null;
      throw e;
    }
    if (changed) {
      run = null;
    }
    return changed;
  }

  boolean hasInfo() {
    return info != null;
  }

  private boolean scanRun() {
    boolean changed = false;
    Path runJson = directory.resolve("run.json");
    String version;
    try {
      version = fileVersion(runJson);
    } catch (IOException e) {
      files.reportCannotRead(runJson, e);
      return false;
    }
    if (version == null) {
      changed = info != null;
      forget();
      return changed;
    }
    if (!version.equals(infoVersion)) {
      changed = readInfo(runJson, version);
    }
    if (info == null) {
      changed |= !tests.isEmpty() || !mails.isEmpty();
      forget();
      return changed;
    }
    changed |= scanTests();
    changed |= scanMails();
    return changed;
  }

  private boolean readInfo(Path runJson, String version) {
    JsonNode json;
    try {
      json = files.readJson(runJson);
    } catch (UnusableFileException e) {
      boolean changed = info != null;
      info = null;
      infoVersion = null;
      return changed;
    }
    if (json == null) {
      return false;
    }
    infoVersion = version;
    boolean changed = !json.equals(info);
    info = json;
    startedAt = startedAt(json);
    return changed;
  }

  private void forget() {
    info = null;
    infoVersion = null;
    run = null;
    tests.clear();
    mails.clear();
    testsFolder.forget();
    messagesFolder.forget();
  }

  private boolean skippedFilesUnchanged(Path folder) {
    List<String> problems = new ArrayList<>();
    for (Map.Entry<Path, Skipped> skip : skipped.entrySet()) {
      if (folder.equals(skip.getKey().getParent())) {
        if (!skip.getValue().isUnchanged(skip.getKey())) {
          return false;
        }
        problems.add(skip.getValue().problem);
      }
    }
    problems.forEach(files::keepProblem);
    return true;
  }

  private void forgetSkippedFilesThatAreGone(Path folder, Map<String, Path> recordFiles) {
    skipped
        .keySet()
        .removeIf(file -> folder.equals(file.getParent()) && !recordFiles.containsValue(file));
  }

  private Timestamp startedAt(JsonNode json) {
    Timestamp timestamp = timestamp(json, "startedAt");
    if (timestamp != null) {
      return timestamp;
    }
    files.warnOnce(
        "started-at:" + directory,
        "{0} has no valid startedAt. Using the modification time of the run directory instead.",
        directory.resolve("run.json"));
    FileTime time = lastModified(directory);
    Instant instant = time == null ? Instant.EPOCH : time.toInstant();
    return new Timestamp(instant.toString(), instant);
  }

  private boolean scanTests() {
    return scanFolder("tests", testsFolder, tests, this::testRecord);
  }

  private boolean scanMails() {
    return scanFolder("messages", messagesFolder, mails, new MailFolder());
  }

  private <T> boolean scanFolder(
      String folderName,
      ListedFolder listedFolder,
      Map<String, T> records,
      RecordFolder<T> recordFolder) {
    Path folder = directory.resolve(folderName);
    FileTime modified = lastModified(folder);
    if (listedFolder.isUnchanged(modified) && skippedFilesUnchanged(folder)) {
      return false;
    }
    List<Path> entries = files.list(folder);
    if (entries == null) {
      listedFolder.forget();
      return false;
    }
    Map<String, Path> recordFiles = recordFiles(entries);
    recordFolder.leaveOutIncomplete(recordFiles, entries);
    forgetSkippedFilesThatAreGone(folder, recordFiles);
    boolean changed = records.keySet().retainAll(recordFiles.keySet());
    Map<String, Future<FileContent>> filesAhead = new HashMap<>();
    for (Map.Entry<String, Path> file : recordFiles.entrySet()) {
      if (isNew(file.getValue(), records.containsKey(file.getKey()))) {
        Path path = file.getValue();
        filesAhead.put(file.getKey(), files.readFileAhead(path));
        recordFolder.readAhead(file.getKey(), path);
      }
    }
    boolean complete = true;
    for (Map.Entry<String, Path> file : recordFiles.entrySet()) {
      String number = file.getKey();
      if (records.containsKey(number) || isSkipped(file.getValue())) {
        continue;
      }
      T record;
      try {
        JsonNode json = files.readJson(file.getValue(), filesAhead.get(number));
        record = json == null ? null : recordFolder.record(number, file.getValue(), json);
      } catch (UnusableFileException e) {
        skip(
            file.getValue(),
            e.getMessage(),
            e.modified(),
            recordFolder.alsoReadFrom(number, file.getValue()),
            recordFolder.alsoModifiedBeforeRead(number));
        continue;
      }
      if (record == null) {
        complete = false;
        continue;
      }
      records.put(number, record);
      changed = true;
    }
    listedFolder.listed(modified, complete);
    return changed;
  }

  private TestRecord testRecord(String number, Path file, JsonNode json) {
    return new TestRecord(
        text(json, "kind"),
        text(json, "testClass"),
        text(json, "classDisplayName"),
        text(json, "testMethod"),
        text(json, "displayName"),
        text(json, "uniqueId"),
        timestamp(json, "finishedAt"),
        text(json, "result"),
        text(json, "stage"),
        text(json, "error"));
  }

  private final class MailFolder implements RecordFolder<MailRecord> {
    private final Map<String, Future<MailSummary>> summariesAhead = new HashMap<>();
    private final Map<String, FileTime> emlModified = new HashMap<>();

    @Override
    public void leaveOutIncomplete(Map<String, Path> recordFiles, List<Path> entries) {
      // A mail needs both files. The .eml file is written first.
      Set<String> names = new HashSet<>();
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
      recordFiles.keySet().removeIf(number -> !names.contains(number + ".eml"));
    }

    @Override
    public void readAhead(String number, Path file) {
      Path eml = emlOf(number, file);
      emlModified.put(number, lastModified(eml));
      summariesAhead.put(number, files.readAhead(() -> mailReader.summarize(mailReader.read(eml))));
    }

    @Override
    public MailRecord record(String number, Path file, JsonNode json) throws UnusableFileException {
      Path eml = emlOf(number, file);
      MailSummary summary;
      try {
        summary = summary(number, eml);
      } catch (NoSuchFileException e) {
        return null;
      } catch (IOException e) {
        if (e.getCause() instanceof MessagingException) {
          throw new UnusableFileException(unreadableMail(eml, e), lastModified(file));
        }
        files.reportCannotRead(eml, e);
        return null;
      } catch (RuntimeException | StackOverflowError e) {
        throw new UnusableFileException(unreadableMail(eml, e), lastModified(file));
      } catch (OutOfMemoryError e) {
        throw new UnusableFileException(tooLargeMail(eml, e), lastModified(file));
      }
      List<String> deliveredTo = new ArrayList<>();
      json.path("deliveredTo").forEach(address -> deliveredTo.add(address.asText()));
      List<TestRef> candidates = new ArrayList<>();
      json.path("candidates").forEach(candidate -> candidates.add(testRef(candidate)));
      return new MailRecord(
          sourceId + "." + name + "." + number,
          eml,
          text(json, "phase"),
          testRef(json),
          timestamp(json, "capturedAt"),
          deliveredTo,
          candidates,
          summary);
    }

    private MailSummary summary(String number, Path eml) throws IOException {
      Future<MailSummary> summaryAhead = summariesAhead.get(number);
      if (summaryAhead != null) {
        try {
          return result(summaryAhead);
        } catch (OutOfMemoryError e) {
          // The threads that read ahead can run out of memory because of a large mail read at the
          // same time. This mail is then read again alone, below.
        }
      }
      emlModified.put(number, lastModified(eml));
      return mailReader.summarize(mailReader.read(eml));
    }

    @Override
    public Path alsoReadFrom(String number, Path file) {
      return emlOf(number, file);
    }

    @Override
    public FileTime alsoModifiedBeforeRead(String number) {
      return emlModified.get(number);
    }

    private Path emlOf(String number, Path file) {
      return file.resolveSibling(number + ".eml");
    }
  }

  private static Map<String, Path> recordFiles(List<Path> entries) {
    Map<String, Path> recordFiles = new TreeMap<>();
    for (Path file : entries) {
      Matcher matcher = RECORD_FILE.matcher(file.getFileName().toString());
      if (matcher.matches()) {
        recordFiles.put(matcher.group(1), file);
      }
    }
    return recordFiles;
  }

  private String unreadableMail(Path eml, Throwable e) {
    return files.report(
        "eml:" + eml, "Skipping {0}: the email could not be read ({1}).", eml, e.toString());
  }

  private String tooLargeMail(Path eml, OutOfMemoryError e) {
    return files.report(
        "eml:" + eml,
        "Skipping {0}: the viewer does not have enough memory to read the email ({1}). To read it,"
            + " start the viewer with more memory, for example with java -Xmx1g -jar, or by"
            + " setting maxHeapSize on the Gradle task.",
        eml,
        e.toString());
  }

  private boolean isNew(Path file, boolean read) {
    return !read && !skipped.containsKey(file);
  }

  private boolean isSkipped(Path file) {
    Skipped skip = skipped.get(file);
    if (skip == null || !skip.isUnchanged(file)) {
      return false;
    }
    files.keepProblem(skip.problem);
    return true;
  }

  private void skip(
      Path file,
      String problem,
      FileTime modified,
      Path alsoReadFrom,
      FileTime alsoModifiedBeforeRead) {
    if (modified != null) {
      FileTime alsoModified =
          alsoReadFrom == null
              ? null
              : alsoModifiedBeforeRead != null
                  ? alsoModifiedBeforeRead
                  : lastModified(alsoReadFrom);
      skipped.put(file, new Skipped(modified, alsoReadFrom, alsoModified, problem));
    }
  }

  Run toRun() {
    if (run == null) {
      run =
          new Run(
              sourceId + "." + name,
              name,
              startedAt,
              timestamp(info, "finishedAt"),
              info.hasNonNull("pid") ? info.get("pid").asLong() : null,
              text(info, "workingDirectory"),
              text(info, "javaVersion"),
              text(info, "greenMailVersion"),
              new ArrayList<>(tests.values()),
              new ArrayList<>(mails.values()));
    }
    return run;
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Timestamp timestamp(JsonNode json, String field) {
    String text = text(json, field);
    if (text == null) {
      return null;
    }
    try {
      return new Timestamp(text, OffsetDateTime.parse(text).toInstant());
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static TestRef testRef(JsonNode json) {
    return new TestRef(
        text(json, "testClass"),
        text(json, "classDisplayName"),
        text(json, "testMethod"),
        text(json, "displayName"),
        text(json, "uniqueId"));
  }

  /**
   * A directory of a run that was listed in full. While its modification time stays the same, it is
   * not listed again, except shortly after a change, because some file systems store times in whole
   * seconds.
   */
  private static final class ListedFolder {
    private FileTime modified;

    boolean isUnchanged(FileTime now) {
      return modified != null && modified.equals(now);
    }

    void listed(FileTime before, boolean complete) {
      boolean settled = before != null && before.toInstant().isBefore(Instant.now().minus(SETTLED));
      modified = complete && settled ? before : null;
    }

    void forget() {
      modified = null;
    }
  }

  private interface RecordFolder<T> {
    default void leaveOutIncomplete(Map<String, Path> recordFiles, List<Path> entries) {}

    default void readAhead(String number, Path file) {}

    T record(String number, Path file, JsonNode json) throws UnusableFileException;

    default Path alsoReadFrom(String number, Path file) {
      return null;
    }

    default FileTime alsoModifiedBeforeRead(String number) {
      return null;
    }
  }

  private static final class Skipped {
    private final FileTime modified;
    private final Path also;
    private final FileTime alsoModified;
    private final String problem;

    Skipped(FileTime modified, Path also, FileTime alsoModified, String problem) {
      this.modified = modified;
      this.also = also;
      this.alsoModified = alsoModified;
      this.problem = problem;
    }

    boolean isUnchanged(Path file) {
      return modified.equals(lastModified(file))
          && (also == null || Objects.equals(alsoModified, lastModified(also)));
    }
  }
}
