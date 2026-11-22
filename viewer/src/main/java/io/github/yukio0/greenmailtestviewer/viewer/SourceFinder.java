package io.github.yukio0.greenmailtestviewer.viewer;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds output directories by their marker file, not by their name, below the {@code --dir}
 * directories. Links are not followed, so that nothing is found twice.
 */
final class SourceFinder {

  static final String MARKER_FILE_NAME = "greenmail-test-viewer.json";

  static final int MAX_DEPTH = 8;

  static final Set<String> SKIPPED_DIRECTORIES =
      Set.of(".git", ".gradle", ".hg", ".idea", ".svn", ".vscode", "node_modules");

  private static final String RUNS_DIRECTORY_NAME = "runs";

  private final int maxDepth;

  SourceFinder() {
    this(MAX_DEPTH);
  }

  SourceFinder(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  List<Found> find(List<Path> roots) {
    return find(roots, (directory, outputDirectory) -> {});
  }

  List<Found> find(List<Path> roots, Denied denied) {
    Map<Path, Found> found = new LinkedHashMap<>();
    Set<Path> reported = new HashSet<>();
    Denied once =
        (directory, outputDirectory) -> {
          if (reported.add(directory)) {
            denied.denied(directory, outputDirectory);
          }
        };
    for (Path root : roots) {
      Path normalized = root.toAbsolutePath().normalize();
      List<Path> directories = new ArrayList<>();
      search(normalized, 0, directories, once);
      for (Path directory : directories) {
        found.putIfAbsent(directory, new Found(directory, normalized));
      }
    }
    return new ArrayList<>(found.values());
  }

  private void search(Path directory, int depth, List<Path> found, Denied denied) {
    boolean outputDirectory = isOutputDirectory(directory);
    if (outputDirectory) {
      found.add(directory);
    }
    if (depth >= maxDepth) {
      return;
    }
    List<Path> children = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (isPlainDirectory(entry)
            && !SKIPPED_DIRECTORIES.contains(name)
            && !(outputDirectory && name.equals(RUNS_DIRECTORY_NAME))) {
          children.add(entry);
        }
      }
    } catch (AccessDeniedException e) {
      denied.denied(directory, outputDirectory);
      return;
    } catch (IOException | DirectoryIteratorException | SecurityException e) {
      return;
    }
    children.sort(null);
    for (Path child : children) {
      search(child, depth + 1, found, denied);
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

  static boolean isOutputDirectory(Path directory) {
    return Files.isRegularFile(directory.resolve(MARKER_FILE_NAME));
  }

  /**
   * The output directory as the search would find it now, or {@code null}: the path down to it is
   * checked against the rules of the search, so that a restarted viewer finds the same directories.
   */
  Found foundAgain(Path directory, List<Path> roots) {
    if (!isOutputDirectory(directory)) {
      return null;
    }
    for (Path root : roots) {
      Path normalized = root.toAbsolutePath().normalize();
      if (directory.startsWith(normalized) && isSearchedDownTo(normalized, directory)) {
        return new Found(directory, normalized);
      }
    }
    return null;
  }

  private boolean isSearchedDownTo(Path root, Path directory) {
    if (directory.equals(root)) {
      return true;
    }
    Path relative = root.relativize(directory);
    if (relative.getNameCount() > maxDepth) {
      return false;
    }
    Path path = root;
    for (Path name : relative) {
      boolean inOutputDirectory = isOutputDirectory(path);
      path = path.resolve(name);
      if (SKIPPED_DIRECTORIES.contains(name.toString())
          || (inOutputDirectory && name.toString().equals(RUNS_DIRECTORY_NAME))
          || !isPlainDirectory(path)) {
        return false;
      }
    }
    return true;
  }

  @FunctionalInterface
  interface Denied {
    void denied(Path directory, boolean outputDirectory);
  }

  static final class Found {
    private final Path directory;
    private final Path root;

    Found(Path directory, Path root) {
      this.directory = directory;
      this.root = root;
    }

    Path directory() {
      return directory;
    }

    Path root() {
      return root;
    }

    String relativePath() {
      String relative = root.relativize(directory).toString().replace('\\', '/');
      return relative.isEmpty() ? "." : relative;
    }

    String moduleName() {
      String relative = relativePath();
      for (String suffix : List.of("build/greenmail-test-viewer", "target/greenmail-test-viewer")) {
        if (relative.equals(suffix)) {
          return directoryName(root);
        }
        if (relative.endsWith("/" + suffix)) {
          return relative.substring(0, relative.length() - suffix.length() - 1);
        }
      }
      return relative.equals(".") ? directoryName(directory) : relative;
    }

    private static String directoryName(Path path) {
      Path name = path.getFileName();
      return name == null ? path.toString() : name.toString();
    }
  }
}
