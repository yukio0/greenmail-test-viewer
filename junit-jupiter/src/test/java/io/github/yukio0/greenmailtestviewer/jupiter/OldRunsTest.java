package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OldRunsTest {

  @TempDir Path directory;

  @Test
  void deletesTheFilesOfAnOldRunButNotWhatElseIsInIt() throws Exception {
    Path runs = Files.createDirectories(directory.resolve("runs"));
    Path current = run(runs, "20200101-000003-1");
    Path old = run(runs, "20200101-000001-1");
    Files.writeString(old.resolve("tests/000001.json"), "{}", StandardCharsets.UTF_8);
    Files.writeString(old.resolve("messages/000001.eml"), "mail", StandardCharsets.UTF_8);
    Files.writeString(old.resolve("messages/000001.json"), "{}", StandardCharsets.UTF_8);
    Files.writeString(old.resolve("messages/.000002.eml.123.tmp"), "", StandardCharsets.UTF_8);
    Files.writeString(old.resolve(".run.json.456.tmp"), "", StandardCharsets.UTF_8);
    Files.writeString(old.resolve("notes.txt"), "kept", StandardCharsets.UTF_8);
    Files.writeString(old.resolve("messages/notes.txt"), "kept", StandardCharsets.UTF_8);
    Path outside = outsideDirectory("outside");
    link(old.resolve("tests/000002.json"), outside);

    OldRuns.delete(runs, current, 0);

    assertThat(old.resolve("notes.txt")).hasContent("kept");
    assertThat(old.resolve("messages/notes.txt")).hasContent("kept");
    assertThat(old.resolve("run.json")).doesNotExist();
    assertThat(old.resolve("run.lock")).doesNotExist();
    assertThat(old.resolve(".run.json.456.tmp")).doesNotExist();
    assertThat(old.resolve("tests/000001.json")).doesNotExist();
    assertThat(old.resolve("messages/000001.eml")).doesNotExist();
    assertThat(old.resolve("messages/000001.json")).doesNotExist();
    assertThat(old.resolve("messages/.000002.eml.123.tmp")).doesNotExist();
    assertThat(old.resolve("tests/000002.json")).exists();
    assertThat(outside.resolve("keep.txt")).exists();
    assertThat(current).exists();
    Files.delete(old.resolve("tests/000002.json"));

    OldRuns.delete(runs, current, 0);
    assertThat(old.resolve("notes.txt")).hasContent("kept");
  }

  @Test
  void deletesAnOldRunWithItsFolders() throws Exception {
    Path runs = Files.createDirectories(directory.resolve("runs"));
    Path current = run(runs, "20200101-000003-1");
    Path old = run(runs, "20200101-000001-1");
    Files.writeString(old.resolve("messages/000001.eml"), "mail", StandardCharsets.UTF_8);
    Path stopped = Files.createDirectories(runs.resolve("20200101-000002-2"));
    Files.createDirectories(stopped.resolve("tests"));
    Files.createDirectories(stopped.resolve("messages"));
    Files.createFile(stopped.resolve(RunWriter.LOCK_FILE_NAME));

    OldRuns.delete(runs, current, 0);

    assertThat(old).doesNotExist();
    assertThat(stopped).doesNotExist();
  }

  @Test
  void leavesAFolderNamedLikeARunWithoutARunOfTheExtensionAlone() throws Exception {
    Path runs = Files.createDirectories(directory.resolve("runs"));
    Path current = run(runs, "20200101-000003-1");
    Path otherTool = Files.createDirectories(runs.resolve("20200101-120000-1234"));
    Files.writeString(
        otherTool.resolve("run.json"), "{\"experiment\": \"other tool\"}", StandardCharsets.UTF_8);
    Files.writeString(otherTool.resolve("notes.txt"), "kept", StandardCharsets.UTF_8);
    Path otherProcess = run(runs, "20200101-000001-1");
    Files.writeString(
        otherProcess.resolve("run.json"),
        "{\"formatVersion\": 1, \"startedAt\": \"2020-01-01T00:00:00.000Z\", \"pid\": 2}",
        StandardCharsets.UTF_8);
    Path withoutRunJson = Files.createDirectories(runs.resolve("20200101-000002-2"));
    Files.createFile(withoutRunJson.resolve(RunWriter.LOCK_FILE_NAME));
    Files.writeString(withoutRunJson.resolve("notes.txt"), "kept", StandardCharsets.UTF_8);

    OldRuns.delete(runs, current, 0);

    assertThat(otherTool.resolve("run.json")).hasContent("{\"experiment\": \"other tool\"}");
    assertThat(otherTool.resolve("notes.txt")).hasContent("kept");
    assertThat(otherProcess.resolve("run.json")).exists();
    assertThat(withoutRunJson.resolve(RunWriter.LOCK_FILE_NAME)).exists();
    assertThat(withoutRunJson.resolve("notes.txt")).hasContent("kept");
  }

  @Test
  void leavesALinkNamedLikeARunAlone() throws Exception {
    Path runs = Files.createDirectories(directory.resolve("runs"));
    Path current = run(runs, "20200101-000003-1");
    Path outsideRun = outsideDirectory("outside-run");
    Files.writeString(outsideRun.resolve("run.json"), "{}", StandardCharsets.UTF_8);
    Path linked = runs.resolve("20200101-000002-1");
    link(linked, outsideRun);

    OldRuns.delete(runs, current, 0);

    assertThat(linked).exists();
    assertThat(outsideRun.resolve("keep.txt")).exists();
    Files.delete(linked);
  }

  private static Path run(Path runs, String name) throws IOException {
    Path run = Files.createDirectories(runs.resolve(name));
    Files.createDirectories(run.resolve("tests"));
    Files.createDirectories(run.resolve("messages"));
    Files.writeString(
        run.resolve("run.json"),
        "{\"formatVersion\": 1, \"startedAt\": \"2020-01-01T00:00:00.000Z\", \"pid\": "
            + name.substring(name.lastIndexOf('-') + 1)
            + "}",
        StandardCharsets.UTF_8);
    Files.createFile(run.resolve(RunWriter.LOCK_FILE_NAME));
    return run;
  }

  private Path outsideDirectory(String name) throws IOException {
    Path outside = Files.createDirectories(directory.resolve(name));
    Files.writeString(
        outside.resolve("keep.txt"), "not part of the records", StandardCharsets.UTF_8);
    return outside;
  }

  static void link(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows")) {
      Process process =
          new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
              .redirectErrorStream(true)
              .start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor()).as(output).isZero();
    } else {
      Files.createSymbolicLink(link, target);
    }
  }
}
