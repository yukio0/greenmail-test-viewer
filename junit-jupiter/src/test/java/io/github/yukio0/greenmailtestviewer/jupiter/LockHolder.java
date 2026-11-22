package io.github.yukio0.greenmailtestviewer.jupiter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LockHolder {

  private LockHolder() {}

  public static void main(String[] args) throws IOException {
    try (FileChannel channel =
            FileChannel.open(
                Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      System.out.println("locked");
      System.out.flush();
      while (System.in.read() != -1) {}
    }
  }
}
