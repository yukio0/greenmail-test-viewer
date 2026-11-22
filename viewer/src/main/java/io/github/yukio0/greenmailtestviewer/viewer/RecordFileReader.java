package io.github.yukio0.greenmailtestviewer.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Reads the record files: lists directories, reads JSON in the format of this viewer, and reads
 * ahead in parallel. What cannot be read is logged once and kept as a problem of the scan.
 */
final class RecordFileReader {

  private final ObjectMapper mapper;
  private final Warnings warnings;
  private final ExecutorService executor;
  private List<String> scanProblems = new ArrayList<>();

  RecordFileReader(ObjectMapper mapper, Warnings warnings, ExecutorService executor) {
    this.mapper = mapper;
    this.warnings = warnings;
    this.executor = executor;
  }

  void startScan() {
    scanProblems = new ArrayList<>();
  }

  List<String> scanProblems() {
    return List.copyOf(scanProblems);
  }

  String report(String key, String message, Object... parameters) {
    String text = warnings.warnOnce(key, message, parameters);
    scanProblems.add(text);
    return text;
  }

  void keepProblem(String problem) {
    scanProblems.add(problem);
  }

  void warnOnce(String key, String message, Object... parameters) {
    warnings.warnOnce(key, message, parameters);
  }

  List<Path> list(Path directory) {
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        entries.add(entry);
      }
    } catch (NoSuchFileException e) {
      return List.of();
    } catch (AccessDeniedException e) {
      report("denied:" + directory, RecordStore.LISTING_DENIED, directory);
      return null;
    } catch (IOException | DirectoryIteratorException e) {
      report(
          "unlistable:" + directory,
          "Cannot list {0} ({1}). The records already read from it are kept.",
          directory,
          e.toString());
      return null;
    }
    return entries;
  }

  void reportCannotRead(Path file, IOException e) {
    if (e instanceof AccessDeniedException) {
      report("denied:" + file, RecordStore.PERMISSION_DENIED, file);
    } else {
      report(
          "unreadable:" + file,
          "Cannot read {0} ({1}). The viewer will retry on the next scan.",
          file,
          e.toString());
    }
  }

  JsonNode readJson(Path file) throws UnusableFileException {
    return readJson(file, null);
  }

  JsonNode readJson(Path file, Future<FileContent> readAhead) throws UnusableFileException {
    FileContent content;
    try {
      content = readAhead == null ? readFile(file) : result(readAhead);
    } catch (NoSuchFileException e) {
      return null;
    } catch (IOException e) {
      reportCannotRead(file, e);
      return null;
    }
    JsonNode json;
    try {
      json = mapper.readTree(content.bytes);
    } catch (IOException e) {
      throw new UnusableFileException(
          report(
              "broken:" + file + ":" + content.modified,
              "Skipping {0}: it is not valid JSON ({1}).",
              file,
              e.getMessage()),
          content.modified);
    }
    if (json == null || !json.isObject()) {
      throw new UnusableFileException(
          report("not-object:" + file, "Skipping {0}: not a JSON object.", file), content.modified);
    }
    String unsupported = unsupportedFormat(json, file);
    if (unsupported != null) {
      throw new UnusableFileException(report("format:" + file, unsupported), content.modified);
    }
    return json;
  }

  Future<FileContent> readFileAhead(Path file) {
    return executor.submit(() -> readFile(file));
  }

  <T> Future<T> readAhead(Callable<T> task) {
    return executor.submit(task);
  }

  static String unsupportedFormat(JsonNode json, Path skipped) {
    String broken = brokenFormatVersion(json);
    if (broken != null) {
      return MessageFormat.format("Skipping {0}: invalid record format ({1}).", skipped, broken);
    }
    // Read as is: converting it would turn 1.5, true or 2^32 + 1 into 1.
    JsonNode version = json.path("formatVersion");
    if (version.canConvertToInt() && version.intValue() == Records.FORMAT_VERSION) {
      return null;
    }
    return MessageFormat.format(
        "Skipping {0}: format version {1} is not supported by this viewer, which reads version"
            + " {2}. Use a viewer of the same version as the extension.",
        skipped, version.asText(), String.valueOf(Records.FORMAT_VERSION));
  }

  static String brokenFormatVersion(JsonNode json) {
    JsonNode version = json == null ? null : json.get("formatVersion");
    if (version == null
        || !version.isValueNode()
        || version.isNull()
        || version.asText().isBlank()) {
      return "no formatVersion";
    }
    if (!version.isIntegralNumber()) {
      return "formatVersion " + version + " is not an integer";
    }
    return null;
  }

  static String fileVersion(Path file) throws IOException {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      return attributes.lastModifiedTime() + ":" + attributes.size();
    } catch (NoSuchFileException e) {
      return null;
    }
  }

  static FileTime lastModified(Path file) {
    try {
      return Files.getLastModifiedTime(file);
    } catch (IOException e) {
      return null;
    }
  }

  static <T> T result(Future<T> future) throws IOException {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted while reading");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new IOException(cause);
    }
  }

  private static FileContent readFile(Path file) throws IOException {
    FileTime modified = lastModified(file);
    return new FileContent(modified, Files.readAllBytes(file));
  }

  static final class UnusableFileException extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient FileTime modified;

    UnusableFileException(String problem, FileTime modified) {
      super(problem, null, false, false);
      this.modified = modified;
    }

    FileTime modified() {
      return modified;
    }
  }

  static final class FileContent {
    private final FileTime modified;
    private final byte[] bytes;

    private FileContent(FileTime modified, byte[] bytes) {
      this.modified = modified;
      this.bytes = bytes;
    }
  }
}
