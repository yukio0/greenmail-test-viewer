package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LockChecker {

  private LockChecker() {}

  public static void main(String[] args) throws IOException {
    try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
        FileLock lock = channel.tryLock()) {
      System.out.println(lock == null ? "held" : "free");
    }
  }
}
