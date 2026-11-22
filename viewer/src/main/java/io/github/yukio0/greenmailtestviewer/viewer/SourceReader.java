package io.github.yukio0.greenmailtestviewer.viewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Reads the records of one output directory, with a {@link RunReader} for each run. A file that
 * cannot be read now is read again at the next scan; one that is not a record is skipped until it
 * changes.
 */
final class SourceReader {

  // Names that IDs and URLs can use.
  private static final Pattern RUN_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  private final String id;
  private SourceFinder.Found found;
  private final MailReader mailReader;
  private final RecordFileReader files;
  private final Map<String, RunReader> runs = new TreeMap<>();
  private List<String> problems = List.of();

  SourceReader(
      String id,
      SourceFinder.Found found,
      ObjectMapper mapper,
      MailReader mailReader,
      Warnings warnings,
      ExecutorService readAhead) {
    this.id = id;
    this.found = found;
    this.mailReader = mailReader;
    this.files = new RecordFileReader(mapper, warnings, readAhead);
  }

  String id() {
    return id;
  }

  Path directory() {
    return found.directory();
  }

  SourceFinder.Found found() {
    return found;
  }

  boolean foundAt(SourceFinder.Found again) {
    if (again.root().equals(found.root())) {
      return false;
    }
    found = again;
    return true;
  }

  List<String> problems() {
    return problems;
  }

  boolean scan() {
    files.startScan();
    try {
      return scanRuns();
    } finally {
      problems = files.scanProblems();
    }
  }

  private boolean scanRuns() {
    List<Path> entries = files.list(found.directory().resolve("runs"));
    if (entries == null) {
      return false;
    }
    Set<String> names = new HashSet<>();
    for (Path directory : entries) {
      String name = directory.getFileName().toString();
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (!RUN_NAME.matcher(name).matches()) {
        files.report(
            "run-name:" + directory,
            "Skipping {0}: run directory names may contain only ASCII letters, digits, hyphens and"
                + " underscores.",
            directory);
        continue;
      }
      names.add(name);
    }
    boolean changed = runs.keySet().retainAll(names);
    for (String name : names) {
      RunReader run =
          runs.computeIfAbsent(
              name,
              key ->
                  new RunReader(
                      files, mailReader, id, key, found.directory().resolve("runs").resolve(key)));
      changed |= run.scan();
    }
    return changed;
  }

  Source toSource() {
    List<Run> list = new ArrayList<>();
    for (RunReader run : runs.values()) {
      if (run.hasInfo()) {
        list.add(run.toRun());
      }
    }
    list.sort((a, b) -> {
      int byStart = a.startedAt().instant().compareTo(b.startedAt().instant());
      return byStart != 0 ? byStart : a.name().compareTo(b.name());
    });
    return new Source(id, found.directory(), found.moduleName(), found.relativePath(), list);
  }
}
