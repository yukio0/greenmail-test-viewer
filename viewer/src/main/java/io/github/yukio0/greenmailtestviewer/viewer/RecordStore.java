package io.github.yukio0.greenmailtestviewer.viewer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the records of all output directories up to date by looking for output directories every
 * few tens of seconds and for changed files every few seconds, rather than with a WatchService,
 * which does not work on all file systems. Requests read an immutable {@link Snapshot}.
 */
final class RecordStore implements AutoCloseable {

  static final Duration SCAN_INTERVAL = Duration.ofSeconds(2);
  static final Duration DISCOVERY_INTERVAL = Duration.ofSeconds(30);

  private static final int READ_AHEAD_THREADS = 8;

  private static final Logger LOGGER = System.getLogger(RecordStore.class.getName());

  static final String PERMISSION_DENIED =
      "Cannot read {0}: permission denied, so its records are not shown. Start the viewer as a user"
          + " who can read it.";

  static final String LISTING_DENIED =
      "Cannot list {0}: permission denied. The records already read from it are kept, but nothing"
          + " new is read from it. Start the viewer as a user who can read it.";

  private static final String SEARCH_DENIED =
      "Cannot look for output directories in {0}: permission denied. Directories found earlier are"
          + " kept, but no new ones are found. Start the viewer as a user who can read it.";

  private static final String OUTPUT_DIRECTORY_NOT_LISTED =
      "Cannot look for other output directories below {0}: permission denied.";

  private static final String BROKEN_MARKER =
      "Skipping {0}: its marker file {1} is broken ({2}). Delete the file; the extension will write"
          + " it again the next time the tests run.";

  private final List<Path> roots;
  private final SourceFinder finder;
  private final ObjectMapper mapper =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final MailReader mailReader;
  private final Warnings warnings = new Warnings();
  private final ExecutorService readAhead = readAheadPool();
  private final Map<Path, SourceReader> readers = new LinkedHashMap<>();
  // The contents of the marker files when last checked, so that a changed one is checked again.
  private final Map<Path, byte[]> markers = new HashMap<>();
  // Deleted output directories, such as by "gradle clean", are checked at every scan, so that the
  // results of "gradle clean test" appear within seconds.
  private final Set<Path> deleted = new LinkedHashSet<>();
  private long revision;
  // A scan that failed midway may have read changes that it did not publish.
  private boolean unpublished;
  private volatile Snapshot snapshot = new Snapshot(0, List.of());
  private List<String> discoveryProblems = List.of();
  private volatile List<String> problems = List.of();
  private volatile ScheduledExecutorService scheduler;
  private volatile boolean closed;

  RecordStore(List<Path> roots) {
    this(roots, new SourceFinder());
  }

  RecordStore(List<Path> roots, SourceFinder finder) {
    this(roots, finder, new MailReader());
  }

  RecordStore(List<Path> roots, SourceFinder finder, MailReader mailReader) {
    this.roots = List.copyOf(roots);
    this.finder = finder;
    this.mailReader = mailReader;
  }

  Snapshot snapshot() {
    return snapshot;
  }

  List<String> problems() {
    return problems;
  }

  synchronized boolean refresh(boolean discover) {
    boolean changed = unpublished;
    unpublished = true;
    if (discover) {
      changed |= discoverSources();
    }
    changed |= readWrittenAgain();
    for (Iterator<SourceReader> it = readers.values().iterator(); it.hasNext(); ) {
      SourceReader reader = it.next();
      SourceFinder.Found again = finder.foundAgain(reader.directory(), roots);
      if (again == null) {
        it.remove();
        forgetDeleted(reader);
        changed = true;
        continue;
      }
      changed |= reader.foundAt(again);
      changed |= reader.scan();
    }
    List<String> current = new ArrayList<>(discoveryProblems);
    for (SourceReader reader : readers.values()) {
      current.addAll(reader.problems());
    }
    problems = List.copyOf(current);
    if (changed) {
      revision++;
      List<Source> sources = new ArrayList<>();
      for (SourceReader reader : readers.values()) {
        sources.add(reader.toSource());
      }
      sources.sort(Comparator.comparing(Source::name).thenComparing(Source::path));
      snapshot = new Snapshot(revision, sources);
    }
    unpublished = false;
    return changed;
  }

  private boolean discoverSources() {
    boolean changed = false;
    List<String> newProblems = new ArrayList<>();
    Map<Path, SourceFinder.Found> found = new LinkedHashMap<>();
    for (SourceFinder.Found source :
        finder.find(
            roots,
            (directory, outputDirectory) ->
                newProblems.add(
                    outputDirectory
                        ? warnings.warnOnce(
                            "denied-below:" + directory, OUTPUT_DIRECTORY_NOT_LISTED, directory)
                        : warnings.warnOnce("denied:" + directory, SEARCH_DENIED, directory)))) {
      found.put(source.directory(), source);
    }
    Set<Path> unsupported = new HashSet<>();
    for (Iterator<SourceReader> it = readers.values().iterator(); it.hasNext(); ) {
      SourceReader reader = it.next();
      Path directory = reader.directory();
      // An output directory that the search no longer finds but that still exists is kept, such as
      // when a parent directory cannot be listed.
      if (found.containsKey(directory) || finder.foundAgain(directory, roots) != null) {
        if (markerChanged(directory)) {
          if (isSupported(directory, newProblems)) {
            rememberMarker(directory);
          } else {
            LOGGER.log(
                Level.INFO,
                "Stopped reading the records in {0}: its marker file changed.",
                directory);
            it.remove();
            markers.remove(directory);
            unsupported.add(directory);
            changed = true;
          }
        }
        continue;
      }
      it.remove();
      changed = true;
      forgetDeleted(reader);
    }
    for (SourceFinder.Found source : found.values()) {
      if (readers.containsKey(source.directory())
          || unsupported.contains(source.directory())
          || !isSupported(source.directory(), newProblems)) {
        continue;
      }
      read(source);
      changed = true;
    }
    discoveryProblems = List.copyOf(newProblems);
    return changed;
  }

  private void forgetDeleted(SourceReader reader) {
    LOGGER.log(
        Level.INFO,
        "Stopped reading the records in {0}: the output directory can no longer be found.",
        reader.directory());
    markers.remove(reader.directory());
    deleted.add(reader.directory());
  }

  private boolean readWrittenAgain() {
    boolean changed = false;
    for (Iterator<Path> it = deleted.iterator(); it.hasNext(); ) {
      Path directory = it.next();
      if (readers.containsKey(directory)) {
        it.remove();
        continue;
      }
      SourceFinder.Found source = finder.foundAgain(directory, roots);
      if (source != null && isSupported(directory, new ArrayList<>())) {
        it.remove();
        read(source);
        changed = true;
      }
    }
    return changed;
  }

  // Compared by content, because a change within one second may keep the same time and size.
  private boolean markerChanged(Path directory) {
    try {
      return !Arrays.equals(
          markers.get(directory),
          Files.readAllBytes(directory.resolve(SourceFinder.MARKER_FILE_NAME)));
    } catch (IOException e) {
      return false;
    }
  }

  private void rememberMarker(Path directory) {
    try {
      markers.put(directory, Files.readAllBytes(directory.resolve(SourceFinder.MARKER_FILE_NAME)));
    } catch (IOException e) {
      markers.remove(directory);
    }
  }

  private void read(SourceFinder.Found source) {
    LOGGER.log(Level.INFO, "Reading records in {0}", source.directory());
    rememberMarker(source.directory());
    readers.put(
        source.directory(),
        new SourceReader(
            sourceId(source.directory()), source, mapper, mailReader, warnings, readAhead));
  }

  private boolean isSupported(Path directory, List<String> newProblems) {
    Path marker = directory.resolve(SourceFinder.MARKER_FILE_NAME);
    JsonNode json;
    try {
      json = mapper.readTree(Files.readAllBytes(marker));
    } catch (AccessDeniedException e) {
      newProblems.add(warnings.warnOnce("denied:" + marker, PERMISSION_DENIED, marker));
      return false;
    } catch (NoSuchFileException e) {
      return false;
    } catch (JsonProcessingException e) {
      newProblems.add(
          warnings.warnOnce(
              "broken:" + marker, BROKEN_MARKER, directory, marker, e.getOriginalMessage()));
      return false;
    } catch (IOException e) {
      newProblems.add(
          warnings.warnOnce(
              "unreadable:" + marker,
              "Cannot read {0} ({1}). The viewer will retry on the next scan.",
              marker,
              e.toString()));
      return false;
    }
    String broken =
        json == null || json.isMissingNode()
            ? "empty"
            : !json.isObject() ? "not a JSON object" : RecordFileReader.brokenFormatVersion(json);
    if (broken != null) {
      newProblems.add(
          warnings.warnOnce("broken:" + marker, BROKEN_MARKER, directory, marker, broken));
      return false;
    }
    String unsupported = RecordFileReader.unsupportedFormat(json, directory);
    if (unsupported != null) {
      newProblems.add(warnings.warnOnce("format:" + directory, unsupported));
      return false;
    }
    return true;
  }

  private static ExecutorService readAheadPool() {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            READ_AHEAD_THREADS,
            READ_AHEAD_THREADS,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            task -> {
              Thread thread = new Thread(task, "greenmail-test-viewer-reader");
              thread.setDaemon(true);
              return thread;
            });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  static String sourceId(Path directory) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(directory.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder id = new StringBuilder("s");
      for (int i = 0; i < 5; i++) {
        id.append(String.format("%02x", hash[i]));
      }
      return id.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  synchronized void start() {
    runScan(() -> refreshUnlessClosed(true));
    if (closed) {
      return;
    }
    long scanMillis = SCAN_INTERVAL.toMillis();
    long scansPerDiscovery = DISCOVERY_INTERVAL.toMillis() / scanMillis;
    ScheduledExecutorService started =
        Executors.newSingleThreadScheduledExecutor(task -> {
          Thread thread = new Thread(task, "greenmail-test-viewer-scanner");
          thread.setDaemon(true);
          return thread;
        });
    scheduler = started;
    long[] scans = {0};
    started.scheduleWithFixedDelay(
        () -> {
          scans[0]++;
          boolean discover = scans[0] % scansPerDiscovery == 0;
          runScan(() -> refreshUnlessClosed(discover));
        },
        scanMillis,
        scanMillis,
        TimeUnit.MILLISECONDS);
    if (closed) {
      started.shutdownNow();
    }
  }

  private void refreshUnlessClosed(boolean discover) {
    if (closed) {
      return;
    }
    try {
      refresh(discover);
    } catch (RuntimeException e) {
      if (!closed) {
        throw e;
      }
    }
  }

  boolean isClosed() {
    return closed;
  }

  // Catches errors too; otherwise they would stop the scheduled scans for good.
  static void runScan(Runnable scan) {
    try {
      scan.run();
    } catch (RuntimeException | Error e) {
      LOGGER.log(Level.WARNING, "Could not read the records.", e);
    }
  }

  @Override
  public void close() {
    closed = true;
    ScheduledExecutorService running = scheduler;
    if (running != null) {
      running.shutdownNow();
    }
    for (Runnable notStarted : readAhead.shutdownNow()) {
      if (notStarted instanceof Future) {
        ((Future<?>) notStarted).cancel(false);
      }
    }
  }

  static final class Snapshot {
    private final long revision;
    private final List<Source> sources;
    private final Map<String, Source> sourcesById = new HashMap<>();
    private final Map<String, Run> runsById = new HashMap<>();
    private final Map<String, Source> sourcesByRunId = new HashMap<>();
    private final Map<String, MailRecord> mailsById = new HashMap<>();
    private final Map<String, Run> runsByMailId = new HashMap<>();

    Snapshot(long revision, List<Source> sources) {
      this.revision = revision;
      this.sources = List.copyOf(sources);
      for (Source source : sources) {
        sourcesById.put(source.id(), source);
        for (Run run : source.runs()) {
          runsById.put(run.id(), run);
          sourcesByRunId.put(run.id(), source);
          for (MailRecord mail : run.mails()) {
            mailsById.put(mail.id(), mail);
            runsByMailId.put(mail.id(), run);
          }
        }
      }
    }

    long revision() {
      return revision;
    }

    List<Source> sources() {
      return sources;
    }

    Source source(String id) {
      return sourcesById.get(id);
    }

    Run run(String id) {
      return runsById.get(id);
    }

    Source sourceOfRun(String runId) {
      return sourcesByRunId.get(runId);
    }

    MailRecord mail(String id) {
      return mailsById.get(id);
    }

    Run runOfMail(String mailId) {
      return runsByMailId.get(mailId);
    }
  }
}
