package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFinderTest {

  @TempDir Path project;

  @Test
  void findsOutputDirectoriesByTheirMarkerFileAndNotByTheirName() throws IOException {
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("lib/build/mail-records"));
    Files.createDirectories(project.resolve("other/build/greenmail-test-viewer/runs"));

    assertThat(relativePaths(new SourceFinder().find(List.of(project))))
        .containsExactly("app/build/greenmail-test-viewer", "lib/build/mail-records");
  }

  @Test
  void stopsAtTheMaximumDepth() {
    RecordFiles.create(project.resolve("a/out"));
    RecordFiles.create(project.resolve("b/c/d/out"));

    assertThat(relativePaths(new SourceFinder(2).find(List.of(project)))).containsExactly("a/out");
  }

  @Test
  void doesNotSearchInSkippedDirectories() {
    RecordFiles.create(project.resolve("node_modules/package/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve(".git/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve(".gradle/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));

    assertThat(relativePaths(new SourceFinder().find(List.of(project))))
        .containsExactly("app/build/greenmail-test-viewer");
  }

  @Test
  void reportsDirectoriesThatItHasNoPermissionToSearch() throws IOException {
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("private/build/greenmail-test-viewer"));
    Path denied = project.resolve("private");
    Runnable restore = TestFiles.denyListing(denied);
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      List<String> reported = new ArrayList<>();

      assertThat(
              relativePaths(
                  new SourceFinder()
                      .find(
                          List.of(project),
                          (directory, outputDirectory) ->
                              reported.add(directory + " " + outputDirectory))))
          .containsExactly("app/build/greenmail-test-viewer");
      assertThat(reported).containsExactly(denied + " false");
    } finally {
      restore.run();
    }
  }

  @Test
  void findsAnOutputDirectoryThatItHasNoPermissionToSearch() throws IOException {
    Path output = project.resolve("app/build/greenmail-test-viewer");
    RecordFiles.create(output);
    Runnable restore = TestFiles.denyListing(output);
    assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
    try {
      List<String> reported = new ArrayList<>();

      assertThat(
              relativePaths(
                  new SourceFinder()
                      .find(
                          List.of(project),
                          (directory, outputDirectory) ->
                              reported.add(directory + " " + outputDirectory))))
          .containsExactly("app/build/greenmail-test-viewer");
      assertThat(reported).containsExactly(output + " true");
    } finally {
      restore.run();
    }
  }

  @Test
  void reportsADirectoryThatItHasNoPermissionToSearchOnceForOverlappingDirectories()
      throws IOException {
    Path output = project.resolve("app/build/greenmail-test-viewer");
    RecordFiles.create(output);
    RecordFiles.create(project.resolve("lib/private/build/greenmail-test-viewer"));
    for (Path denied : List.of(output, project.resolve("lib/private"))) {
      Runnable restore = TestFiles.denyListing(denied);
      assumeTrue(restore != null, "The permission to list a directory cannot be taken away here");
      try {
        List<Path> reported = new ArrayList<>();

        new SourceFinder()
            .find(
                List.of(project, denied.getParent()),
                (directory, outputDirectory) -> reported.add(directory));

        assertThat(reported).as(denied.toString()).containsExactly(denied);
      } finally {
        restore.run();
      }
    }
  }

  @Test
  void doesNotFollowLinksToOtherDirectories() throws Exception {
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    Path shortcut = project.resolve("shortcut");
    TestFiles.link(shortcut, project.resolve("app"));
    try {
      assertThat(relativePaths(new SourceFinder().find(List.of(project))))
          .containsExactly("app/build/greenmail-test-viewer");
    } finally {
      Files.delete(shortcut);
    }
  }

  @Test
  void searchesBelowAnOutputDirectoryButNotInItsRuns() {
    RecordFiles.create(project);
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer/runs/copy"));
    RecordFiles.create(project.resolve("runs/copy"));

    assertThat(relativePaths(new SourceFinder().find(List.of(project))))
        .containsExactly(".", "app/build/greenmail-test-viewer");
    SourceFinder finder = new SourceFinder();
    assertThat(
            finder.foundAgain(project.resolve("app/build/greenmail-test-viewer"), List.of(project)))
        .isNotNull();
    assertThat(finder.foundAgain(project.resolve("runs/copy"), List.of(project))).isNull();
    assertThat(
            finder.foundAgain(
                project.resolve("app/build/greenmail-test-viewer/runs/copy"), List.of(project)))
        .isNull();
  }

  @Test
  void readsADirectoryThatIsAnOutputDirectoryItself() {
    Path reports = project.resolve("reports/mail");
    RecordFiles.create(reports);

    List<SourceFinder.Found> found = new SourceFinder().find(List.of(reports));

    assertThat(found).hasSize(1);
    assertThat(found.get(0).relativePath()).isEqualTo(".");
    assertThat(found.get(0).moduleName()).isEqualTo("mail");
  }

  @Test
  void searchesEveryGivenDirectoryAndFindsEachOutputDirectoryOnce() {
    RecordFiles.create(project.resolve("first/app/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("second/build/greenmail-test-viewer"));

    List<SourceFinder.Found> found =
        new SourceFinder()
            .find(
                List.of(
                    project.resolve("first"),
                    project.resolve("second"),
                    project.resolve("first/app")));

    assertThat(found)
        .extracting(source -> source.directory())
        .containsExactly(
            project.resolve("first/app/build/greenmail-test-viewer"),
            project.resolve("second/build/greenmail-test-viewer"));
  }

  @Test
  void namesModulesAfterTheirDirectory() {
    RecordFiles.create(project.resolve("build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("examples/build/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("services/mail/target/greenmail-test-viewer"));
    RecordFiles.create(project.resolve("lib/build/mail-records"));

    List<String> names =
        new SourceFinder()
            .find(List.of(project)).stream()
                .map(SourceFinder.Found::moduleName)
                .collect(Collectors.toList());

    assertThat(names)
        .containsExactly(
            project.getFileName().toString(),
            "examples",
            "lib/build/mail-records",
            "services/mail");
  }

  private static List<String> relativePaths(List<SourceFinder.Found> found) {
    return found.stream().map(SourceFinder.Found::relativePath).collect(Collectors.toList());
  }
}
