package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

/**
 * One JUnit execution, stored in its root store: its number in this JVM, its start time and one run
 * per output directory. The runs are finished when JUnit closes the store.
 */
@SuppressWarnings("deprecation")
final class JUnitExecution implements Store.CloseableResource, AutoCloseable {

  private static final Logger LOGGER =
      System.getLogger(GreenMailTestViewerExtension.class.getName());

  private static final AtomicLong EXECUTIONS = new AtomicLong();

  private final long number = EXECUTIONS.incrementAndGet();
  private final OffsetDateTime startedAt = RunWriter.now();
  private final Map<Path, RunWriter> writers = new LinkedHashMap<>();

  long number() {
    return number;
  }

  OffsetDateTime startedAt() {
    return startedAt;
  }

  /** The writer of an output directory, which creates the run directory on first use. */
  synchronized RunWriter writer(Path outputDirectory, int keepRuns) throws IOException {
    Path key = outputDirectory.toAbsolutePath().normalize();
    RunWriter writer = writers.get(key);
    if (writer == null) {
      writer = RunWriter.create(key, keepRuns, startedAt);
      writers.put(key, writer);
    }
    return writer;
  }

  // JUnit 5.13 and later close AutoCloseable values, older versions CloseableResource.
  @Override
  public void close() {
    List<RunWriter> toFinish;
    synchronized (this) {
      toFinish = new ArrayList<>(writers.values());
      writers.clear();
    }
    for (RunWriter writer : toFinish) {
      try {
        writer.finish();
      } catch (IOException | RuntimeException e) {
        LOGGER.log(Level.WARNING, "Could not finish the run in " + writer.runDirectory(), e);
      }
    }
  }
}
