package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deletes old runs that the extension wrote, and only its own files in them. A run is finished when
 * nobody holds the lock on its {@code run.lock}, which the operating system releases even if its
 * JVM crashes.
 */
final class OldRuns {

  static final String KEEP_RUNS_PARAMETER = "greenmail.testviewer.keep-runs";

  static final int DEFAULT_KEEP_RUNS = 20;

  private static final Logger LOGGER = System.getLogger(OldRuns.class.getName());

  private static final Pattern STARTED_AT = Pattern.compile("\"startedAt\"\\s*:\\s*\"([^\"]+)\"");

  private static final Pattern FORMAT_VERSION =
      Pattern.compile("\"formatVersion\"\\s*:\\s*[0-9]+\\s*[,}]");

  private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*([0-9]+)\\s*[,}]");

  private static final long MAX_RUN_JSON_SIZE = 64 * 1024;

  private static final Pattern RUN_DIRECTORY_NAME =
      Pattern.compile("[0-9]{8}(T[0-9]{6}Z|-[0-9]{6})-([0-9]+)(-[0-9a-f]{8})?");

  private static final Pattern RUN_FILE =
      Pattern.compile(
          "run\\.json|"
              + RunWriter.LOCK_FILE_NAME.replace(".", "\\.")
              + "|\\.run\\.json\\..+\\.tmp");

  private static final Pattern TEST_FILE =
      Pattern.compile("[0-9]{6,}\\.json|\\.[0-9]{6,}\\.json\\..+\\.tmp");

  private static final Pattern MAIL_FILE =
      Pattern.compile("[0-9]{6,}\\.(eml|json)|\\.[0-9]{6,}\\.(eml|json)\\..+\\.tmp");

  private static final String TESTS = "tests";

  private static final String MESSAGES = "messages";

  private OldRuns() {}

  static int keepRuns(String configured) {
    if (configured == null || configured.isBlank()) {
      return DEFAULT_KEEP_RUNS;
    }
    try {
      int keepRuns = Integer.parseInt(configured.trim());
      if (keepRuns >= 0) {
        return keepRuns;
      }
    } catch (NumberFormatException e) {
    }
    LOGGER.log(
        Level.WARNING,
        "Ignoring {0}={1}. Expected a non-negative integer. Keeping {2} runs.",
        KEEP_RUNS_PARAMETER,
        configured,
        DEFAULT_KEEP_RUNS);
    return DEFAULT_KEEP_RUNS;
  }

  /**
   * Keeps the newest {@code keepRuns} finished runs and deletes the older ones. The caller holds
   * the lock on the runs directory, so no other JVM creates a run meanwhile.
   */
  static void delete(Path runsDirectory, Path currentRun, int keepRuns) {
    List<Path> finished;
    try {
      finished = finishedRuns(runsDirectory, currentRun);
    } catch (IOException | UncheckedIOException | UnsupportedOperationException e) {
      LOGGER.log(Level.WARNING, "Could not check the old runs in " + runsDirectory, e);
      return;
    }
    // Sorted by the start time in run.json, because older run names carry the local time of their
    // machine.
    Map<Path, Instant> startTimes = new HashMap<>();
    for (Path run : finished) {
      startTimes.put(run, startedAt(run));
    }
    finished.sort(
        Comparator.comparing((Path run) -> startTimes.get(run))
            .thenComparing(run -> run.getFileName().toString())
            .reversed());
    for (Path run : finished.subList(Math.min(keepRuns, finished.size()), finished.size())) {
      try {
        if (!deleteRun(run)) {
          LOGGER.log(
              Level.INFO,
              "Kept the directory of the old run {0} because it contains files that the"
                  + " extension did not write. Only the files that the extension wrote were"
                  + " deleted.",
              run);
        }
      } catch (IOException | UncheckedIOException e) {
        LOGGER.log(Level.WARNING, "Could not delete the old run " + run, e);
      }
    }
  }

  /**
   * Deletes the files of a finished run, and then its directories if they are empty. run.json is
   * deleted last, so that a run deleted halfway is still found next time.
   *
   * @return whether the run directory is gone
   */
  static boolean deleteRun(Path run) throws IOException {
    deleteFiles(run.resolve(MESSAGES), MAIL_FILE, null);
    deleteFiles(run.resolve(TESTS), TEST_FILE, null);
    deleteIfEmpty(run.resolve(MESSAGES));
    deleteIfEmpty(run.resolve(TESTS));
    deleteFiles(run, RUN_FILE, "run.json");
    Files.deleteIfExists(run.resolve("run.json"));
    return deleteIfEmpty(run);
  }

  private static void deleteFiles(Path folder, Pattern names, String last) throws IOException {
    if (!isPlainDirectory(folder)) {
      return;
    }
    List<Path> files;
    try (Stream<Path> children = Files.list(folder)) {
      files =
          children
              .filter(file -> names.matcher(file.getFileName().toString()).matches())
              .filter(file -> !file.getFileName().toString().equals(last))
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      BasicFileAttributes attributes;
      try {
        attributes =
            Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
        continue;
      }
      if (attributes.isRegularFile()) {
        Files.deleteIfExists(file);
      }
    }
  }

  private static boolean deleteIfEmpty(Path folder) throws IOException {
    if (!isPlainDirectory(folder)) {
      return Files.notExists(folder, LinkOption.NOFOLLOW_LINKS);
    }
    try {
      Files.delete(folder);
      return true;
    } catch (DirectoryNotEmptyException e) {
      return false;
    } catch (NoSuchFileException e) {
      return true;
    }
  }

  static Instant startedAt(Path run) {
    try {
      String runJson = Files.readString(run.resolve("run.json"), StandardCharsets.UTF_8);
      Matcher matcher = STARTED_AT.matcher(runJson);
      if (matcher.find()) {
        return OffsetDateTime.parse(matcher.group(1)).toInstant();
      }
    } catch (IOException | DateTimeParseException e) {
    }
    try {
      return Files.getLastModifiedTime(run).toInstant();
    } catch (IOException e) {
      return Instant.EPOCH;
    }
  }

  private static List<Path> finishedRuns(Path runsDirectory, Path currentRun) throws IOException {
    List<Path> runs;
    try (Stream<Path> children = Files.list(runsDirectory)) {
      runs = children.filter(OldRuns::isRunDirectory).collect(Collectors.toList());
    }
    List<Path> finished = new ArrayList<>();
    for (Path run : runs) {
      if (!run.equals(currentRun) && isFinished(run)) {
        finished.add(run);
      }
    }
    return finished;
  }

  /**
   * Whether a directory is a run of the extension: its name has the form of a run name, and it has
   * a run.json whose process ID matches the name, or only what a run has before its run.json is
   * written. Links are never runs.
   */
  private static boolean isRunDirectory(Path directory) {
    if (!isPlainDirectory(directory)) {
      return false;
    }
    Matcher name = RUN_DIRECTORY_NAME.matcher(directory.getFileName().toString());
    if (!name.matches()) {
      return false;
    }
    Path runJson = directory.resolve("run.json");
    if (Files.exists(runJson, LinkOption.NOFOLLOW_LINKS)) {
      return isRunJson(runJson, name.group(2));
    }
    return Files.exists(directory.resolve(RunWriter.LOCK_FILE_NAME))
        && hasOnlyTheFirstFilesOfARun(directory);
  }

  private static boolean isRunJson(Path runJson, String pid) {
    try {
      if (!Files.isRegularFile(runJson, LinkOption.NOFOLLOW_LINKS)
          || Files.size(runJson) > MAX_RUN_JSON_SIZE) {
        return false;
      }
      String content = Files.readString(runJson, StandardCharsets.UTF_8);
      Matcher startedAt = STARTED_AT.matcher(content);
      Matcher writtenPid = PID.matcher(content);
      if (!content.trim().startsWith("{")
          || !FORMAT_VERSION.matcher(content).find()
          || !startedAt.find()
          || !writtenPid.find()) {
        return false;
      }
      OffsetDateTime.parse(startedAt.group(1));
      return Long.parseLong(writtenPid.group(1)) == Long.parseLong(pid);
    } catch (IOException
        | UncheckedIOException
        | DateTimeParseException
        | NumberFormatException e) {
      return false;
    }
  }

  private static boolean hasOnlyTheFirstFilesOfARun(Path directory) {
    try (Stream<Path> children = Files.list(directory)) {
      return children.allMatch(child -> {
        String name = child.getFileName().toString();
        if (name.equals(TESTS) || name.equals(MESSAGES)) {
          return isPlainDirectory(child) && isEmpty(child);
        }
        return RUN_FILE.matcher(name).matches()
            && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS);
      });
    } catch (IOException | UncheckedIOException e) {
      return false;
    }
  }

  private static boolean isEmpty(Path folder) {
    try (Stream<Path> children = Files.list(folder)) {
      return children.findAny().isEmpty();
    } catch (IOException | UncheckedIOException e) {
      return false;
    }
  }

  private static boolean isPlainDirectory(Path path) {
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return attributes.isDirectory() && !attributes.isOther();
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isFinished(Path run) throws IOException {
    // Not checked through its lock file: on Linux, closing a channel to it would release this JVM's
    // lock.
    if (RunWriter.isInProgressInThisJvm(run)) {
      return false;
    }
    Path lockFile = run.resolve(RunWriter.LOCK_FILE_NAME);
    if (!Files.exists(lockFile)) {
      return true;
    }
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      FileLock lock = channel.tryLock();
      if (lock == null) {
        return false;
      }
      lock.release();
      return true;
    } catch (OverlappingFileLockException e) {
      // Locked by this JVM through another path.
      return false;
    }
  }

  /**
   * Deletes a run that RunWriter has just created and could not set up, without following links. A
   * Windows junction is a reparse point too ({@link BasicFileAttributes#isOther()}).
   */
  static void deleteRecursively(Path directory) throws IOException {
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
              throws IOException {
            if (attributes.isOther()) {
              Files.deleteIfExists(dir);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            if (e instanceof NoSuchFileException) {
              return FileVisitResult.CONTINUE;
            }
            throw e;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
              throw e;
            }
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
