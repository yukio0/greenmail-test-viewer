package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class CreatingRunHolder {

  private CreatingRunHolder() {}

  public static void main(String[] args) throws IOException {
    Path runsDirectory = Path.of(args[0]);
    Path runDirectory = Files.createDirectories(runsDirectory.resolve(args[1]));
    BufferedReader input =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    FileChannel runsLock =
        FileChannel.open(
            runsDirectory.resolve(RunWriter.RUNS_LOCK_FILE_NAME),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    runsLock.lock();
    Files.writeString(
        runDirectory.resolve("run.json"),
        "{\n  \"formatVersion\": 1,\n  \"startedAt\": \"2020-01-01T00:00:00.000Z\"\n}\n");
    System.out.println("creating");
    System.out.flush();

    input.readLine();
    try (FileChannel runLock =
        FileChannel.open(
            runDirectory.resolve(RunWriter.LOCK_FILE_NAME),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE)) {
      runLock.lock();
      runsLock.close();
      System.out.println("running");
      System.out.flush();
      while (input.readLine() != null) {}
    }
  }
}
