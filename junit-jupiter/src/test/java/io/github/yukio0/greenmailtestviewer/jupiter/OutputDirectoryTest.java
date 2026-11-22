package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputDirectoryTest {

  @TempDir Path workingDirectory;

  @Test
  void usesTheBuildDirectoryOfGradleByDefault() {
    assertThat(OutputDirectory.resolve(Optional.empty(), workingDirectory))
        .isEqualTo(workingDirectory.resolve("build").resolve("greenmail-test-viewer"));
  }

  @Test
  void usesTheTargetDirectoryWhenTheWorkingDirectoryIsAMavenProject() throws IOException {
    Files.createFile(workingDirectory.resolve("pom.xml"));

    assertThat(OutputDirectory.resolve(Optional.empty(), workingDirectory))
        .isEqualTo(workingDirectory.resolve("target").resolve("greenmail-test-viewer"));
  }

  @Test
  void resolvesAConfiguredRelativeDirectoryAgainstTheWorkingDirectory() {
    assertThat(OutputDirectory.resolve(Optional.of("build/reports/mail"), workingDirectory))
        .isEqualTo(workingDirectory.resolve("build").resolve("reports").resolve("mail"));
  }

  @Test
  void usesAConfiguredAbsoluteDirectoryAsIs(@TempDir Path elsewhere) {
    assertThat(OutputDirectory.resolve(Optional.of(elsewhere.toString()), workingDirectory))
        .isEqualTo(elsewhere);
  }

  @Test
  void ignoresABlankConfiguration() {
    assertThat(OutputDirectory.resolve(Optional.of(" "), workingDirectory))
        .isEqualTo(workingDirectory.resolve("build").resolve("greenmail-test-viewer"));
  }
}
