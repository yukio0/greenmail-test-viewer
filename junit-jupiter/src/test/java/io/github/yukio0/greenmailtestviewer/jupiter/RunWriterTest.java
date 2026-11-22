package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunWriterTest {

  @TempDir Path directory;

  @Test
  void leavesNothingBehindWhenARunCannotBeSetUp() throws IOException {
    Path run = Files.createDirectories(directory.resolve("runs/20260101T000000Z-1-0a1b2c3d"));
    Path realRun = run.toRealPath();
    Files.writeString(run.resolve("messages"), "in the way");

    assertThatThrownBy(() -> RunWriter.setUp(run, OffsetDateTime.now(), 1))
        .isInstanceOf(IOException.class);

    assertThat(run).doesNotExist();
    assertThat(RunWriter.isInProgressInThisJvm(realRun)).isFalse();
  }

  @Test
  void writesTheMarkerFileFromSeveralWritersAtOnce() throws Exception {
    Path target = directory.resolve(RunWriter.MARKER_FILE_NAME);
    byte[] content = "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8);
    int writers = 2;
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    CyclicBarrier together =
        new CyclicBarrier(
            writers,
            () -> {
              try {
                Files.deleteIfExists(target);
              } catch (IOException e) {
                failures.add(e);
              }
            });
    ExecutorService executor = Executors.newFixedThreadPool(writers);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < writers; i++) {
        results.add(
            executor.submit(() -> {
              for (int attempt = 0; attempt < 200; attempt++) {
                try {
                  together.await(10, TimeUnit.SECONDS);
                  RunWriter.writeIfAbsent(target, content);
                } catch (Exception e) {
                  failures.add(e);
                }
              }
            }));
      }
      for (Future<?> result : results) {
        result.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(failures).isEmpty();
    assertThat(target).hasBinaryContent(content);
    assertThat(fileNames()).containsExactly(RunWriter.MARKER_FILE_NAME);
  }

  @Test
  void createsTheRunsOfOverlappingJUnitExecutionsAtTheSameTime() throws Exception {
    int executions = 4;
    int rounds = 10;
    CyclicBarrier together = new CyclicBarrier(executions);
    ExecutorService executor = Executors.newFixedThreadPool(executions);
    List<RunWriter> writers = new ArrayList<>();
    try {
      for (int round = 0; round < rounds; round++) {
        List<Future<RunWriter>> created = new ArrayList<>();
        for (int i = 0; i < executions; i++) {
          created.add(
              executor.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                return RunWriter.create(directory, 1000);
              }));
        }
        for (Future<RunWriter> writer : created) {
          writers.add(writer.get(60, TimeUnit.SECONDS));
        }
      }
    } finally {
      executor.shutdownNow();
      for (RunWriter writer : writers) {
        writer.finish();
      }
    }

    try (Stream<Path> runs = Files.list(directory.resolve("runs"))) {
      assertThat(runs.filter(Files::isDirectory).count()).isEqualTo(executions * rounds);
    }
  }

  private List<String> fileNames() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(file -> file.getFileName().toString()).collect(Collectors.toList());
    }
  }
}
