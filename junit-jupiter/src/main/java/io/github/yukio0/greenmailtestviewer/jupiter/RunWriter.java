package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.opentest4j.TestAbortedException;

/**
 * Writes the records of one JUnit execution into one run directory of an output directory:
 *
 * <pre>
 * greenmail-test-viewer.json   marker that the viewer finds
 * runs/.lock                   held while creating a run or deleting old runs
 * runs/&lt;start&gt;-&lt;pid&gt;-&lt;random&gt;/
 *   run.json
 *   run.lock                   locked while the run is in progress
 *   tests/000001.json
 *   messages/000001.eml        raw mail
 *   messages/000001.json       metadata, written after the .eml
 * </pre>
 *
 * <p>Files are written under a temporary name and then renamed, so that the viewer never reads a
 * half-written file.
 */
final class RunWriter {

  static final int FORMAT_VERSION = 1;
  static final String MARKER_FILE_NAME = "greenmail-test-viewer.json";
  static final String LOCK_FILE_NAME = "run.lock";
  static final String RUNS_LOCK_FILE_NAME = ".lock";

  private static final Logger LOGGER = System.getLogger(RunWriter.class.getName());

  // UTC, so that the names sort the same way on hosts and containers in other time zones.
  private static final DateTimeFormatter RUN_DIRECTORY_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
  // Always three fractional digits; ISO_OFFSET_DATE_TIME would drop trailing zeros.
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

  // A second lock on the runs directory from the same JVM would fail rather than wait.
  private static final Object CREATING = new Object();

  // Real paths of the runs of this JVM, whose lock files the cleanup must not open (see OldRuns).
  private static final Set<Path> RUNS_IN_PROGRESS = ConcurrentHashMap.newKeySet();

  private final Path runDirectory;
  private final Path realRunDirectory;
  private final Map<String, Object> runJson;
  private final FileChannel lockChannel;
  private final AtomicInteger messageSequence = new AtomicInteger();
  private final AtomicInteger testSequence = new AtomicInteger();
  private boolean finished;

  private RunWriter(Path runDirectory, Map<String, Object> runJson, FileChannel lockChannel) {
    this.runDirectory = runDirectory;
    this.realRunDirectory = realPath(runDirectory);
    this.runJson = runJson;
    this.lockChannel = lockChannel;
  }

  static RunWriter create(Path outputDirectory, int keepRuns, OffsetDateTime startedAt)
      throws IOException {
    synchronized (CREATING) {
      return createRun(outputDirectory, keepRuns, startedAt);
    }
  }

  static RunWriter create(Path outputDirectory, int keepRuns) throws IOException {
    return create(outputDirectory, keepRuns, now());
  }

  static boolean isInProgressInThisJvm(Path runDirectory) {
    return RUNS_IN_PROGRESS.contains(realPath(runDirectory));
  }

  private static Path realPath(Path runDirectory) {
    try {
      return runDirectory.toRealPath();
    } catch (IOException e) {
      return runDirectory.toAbsolutePath().normalize();
    }
  }

  private static RunWriter createRun(Path outputDirectory, int keepRuns, OffsetDateTime startedAt)
      throws IOException {
    Path runsDirectory = Files.createDirectories(outputDirectory.resolve("runs"));
    // Runs are created and deleted under this lock, so that no run is seen before its own lock is
    // taken.
    try (RunsLock runsLock = RunsLock.acquire(runsDirectory)) {
      Map<String, Object> markerJson = new LinkedHashMap<>();
      markerJson.put("formatVersion", FORMAT_VERSION);
      writeIfAbsent(
          outputDirectory.resolve(MARKER_FILE_NAME),
          Json.write(markerJson).getBytes(StandardCharsets.UTF_8));

      long pid = ProcessHandle.current().pid();
      Path runDirectory = createRunDirectory(runsDirectory, startedAt, pid);
      RunWriter writer = setUp(runDirectory, startedAt, pid);

      LOGGER.log(Level.INFO, "Recording emails from GreenMail to {0}", runDirectory);
      if (writer.lockChannel != null && runsLock.isHeld()) {
        try {
          OldRuns.delete(runsDirectory, runDirectory, keepRuns);
        } catch (RuntimeException e) {
          LOGGER.log(Level.WARNING, "Could not delete the old runs in " + runsDirectory, e);
        }
      } else {
        LOGGER.log(
            Level.WARNING,
            "Old runs in {0} are not deleted because file locking is not supported there.",
            runsDirectory);
      }
      return writer;
    }
  }

  static RunWriter setUp(Path runDirectory, OffsetDateTime startedAt, long pid) throws IOException {
    FileChannel lockChannel = null;
    try {
      lockChannel = lock(runDirectory.resolve(LOCK_FILE_NAME));
      if (lockChannel != null) {
        RUNS_IN_PROGRESS.add(realPath(runDirectory));
      }
      Files.createDirectories(runDirectory.resolve("tests"));
      Files.createDirectories(runDirectory.resolve("messages"));

      Map<String, Object> runJson = new LinkedHashMap<>();
      runJson.put("formatVersion", FORMAT_VERSION);
      runJson.put("startedAt", timestamp(startedAt));
      runJson.put("pid", pid);
      runJson.put("workingDirectory", Path.of("").toAbsolutePath().toString());
      runJson.put("javaVersion", System.getProperty("java.version"));
      runJson.put("greenMailVersion", greenMailVersion());
      writeAtomically(runDirectory.resolve("run.json"), Json.write(runJson));
      return new RunWriter(runDirectory, runJson, lockChannel);
    } catch (IOException | RuntimeException e) {
      abandon(runDirectory, lockChannel, e);
      throw e;
    }
  }

  private static void abandon(Path runDirectory, FileChannel lockChannel, Exception failure) {
    RUNS_IN_PROGRESS.remove(realPath(runDirectory));
    try {
      if (lockChannel != null) {
        // Closed first, because Windows cannot delete an open file.
        lockChannel.close();
      }
      OldRuns.deleteRecursively(runDirectory);
    } catch (IOException | RuntimeException e) {
      failure.addSuppressed(e);
    }
  }

  // The random suffix distinguishes runs that the same JVM starts within one second.
  private static Path createRunDirectory(Path runsDirectory, OffsetDateTime startedAt, long pid)
      throws IOException {
    String time = RUN_DIRECTORY_TIME.format(startedAt.withOffsetSameInstant(ZoneOffset.UTC));
    for (int attempt = 0; attempt < 10; attempt++) {
      String suffix = String.format("%08x", ThreadLocalRandom.current().nextInt());
      Path runDirectory = runsDirectory.resolve(time + "-" + pid + "-" + suffix);
      try {
        return Files.createDirectory(runDirectory);
      } catch (FileAlreadyExistsException e) {
      }
    }
    throw new IOException("Could not create a new run directory in " + runsDirectory);
  }

  private static FileChannel lock(Path lockFile) throws IOException {
    FileChannel channel =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      FileLock lock = channel.tryLock();
      if (lock != null) {
        return channel;
      }
    } catch (IOException | UnsupportedOperationException e) {
    }
    channel.close();
    return null;
  }

  private static final class RunsLock implements AutoCloseable {
    private final FileChannel channel;

    private RunsLock(FileChannel channel) {
      this.channel = channel;
    }

    static RunsLock acquire(Path runsDirectory) throws IOException {
      FileChannel channel =
          FileChannel.open(
              runsDirectory.resolve(RUNS_LOCK_FILE_NAME),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE);
      try {
        channel.lock();
        return new RunsLock(channel);
      } catch (IOException | UnsupportedOperationException e) {
        channel.close();
        return new RunsLock(null);
      }
    }

    boolean isHeld() {
      return channel != null;
    }

    @Override
    public void close() throws IOException {
      if (channel != null) {
        channel.close();
      }
    }
  }

  void writeMail(MailOwner owner, ReceivedMail mail) throws IOException {
    String name = fileName(messageSequence.incrementAndGet());
    Path messages = runDirectory.resolve("messages");
    writeAtomically(messages.resolve(name + ".eml"), mail.rawMime());

    Map<String, Object> json = new LinkedHashMap<>();
    json.put("formatVersion", FORMAT_VERSION);
    owner.putInto(json);
    json.put("capturedAt", timestamp(now()));
    json.put("deliveredTo", mail.deliveredTo());
    writeAtomically(messages.resolve(name + ".json"), Json.write(json));
  }

  void writeTestResult(MailOwner test, String result, Throwable error) throws IOException {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("formatVersion", FORMAT_VERSION);
    json.put("kind", "method");
    test.putTestInto(json);
    json.put("finishedAt", timestamp(now()));
    json.put("result", result);
    if (error != null) {
      json.put("error", describe(error));
    }
    writeTestRecord(json);
  }

  void writeClassFailure(MailOwner testClass, String stage, Throwable error) throws IOException {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("formatVersion", FORMAT_VERSION);
    json.put("kind", "class");
    testClass.putTestInto(json);
    json.put("finishedAt", timestamp(now()));
    json.put("result", error instanceof TestAbortedException ? "aborted" : "failed");
    json.put("stage", stage);
    json.put("error", describe(error));
    writeTestRecord(json);
  }

  private void writeTestRecord(Map<String, Object> json) throws IOException {
    String name = fileName(testSequence.incrementAndGet());
    writeAtomically(runDirectory.resolve("tests").resolve(name + ".json"), Json.write(json));
  }

  // ASCII digits regardless of the default locale.
  private static String fileName(int sequence) {
    return String.format(Locale.ROOT, "%06d", sequence);
  }

  void finish() throws IOException {
    synchronized (this) {
      if (finished) {
        return;
      }
      finished = true;
    }
    try {
      runJson.put("finishedAt", timestamp(now()));
      writeAtomically(runDirectory.resolve("run.json"), Json.write(runJson));
    } finally {
      RUNS_IN_PROGRESS.remove(realRunDirectory);
      if (lockChannel != null) {
        lockChannel.close();
      }
    }
  }

  Path runDirectory() {
    return runDirectory;
  }

  private static String describe(Throwable error) {
    String message = error.getMessage();
    return message == null
        ? error.getClass().getName()
        : error.getClass().getName() + ": " + message;
  }

  private static void writeAtomically(Path target, String content) throws IOException {
    writeAtomically(target, content.getBytes(StandardCharsets.UTF_8));
  }

  static void writeAtomically(Path target, byte[] content) throws IOException {
    Path temporary = temporaryFile(target);
    try {
      Files.write(temporary, content);
      try {
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
  }

  static void writeIfAbsent(Path target, byte[] content) throws IOException {
    if (Files.exists(target)) {
      return;
    }
    Path temporary = temporaryFile(target);
    try {
      Files.write(temporary, content);
      // Not ATOMIC_MOVE, which replaces the target on Windows.
      Files.move(temporary, target);
    } catch (FileAlreadyExistsException e) {
    } catch (IOException e) {
      if (!Files.exists(target)) {
        throw e;
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Path temporaryFile(Path target) throws IOException {
    return Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  static String timestamp(OffsetDateTime time) {
    return TIMESTAMP.format(time);
  }

  private static String greenMailVersion() {
    try (InputStream in =
        RunWriter.class
            .getClassLoader()
            .getResourceAsStream("META-INF/maven/com.icegreen/greenmail/pom.properties")) {
      if (in == null) {
        return null;
      }
      Properties properties = new Properties();
      properties.load(in);
      return properties.getProperty("version");
    } catch (IOException e) {
      return null;
    }
  }
}
