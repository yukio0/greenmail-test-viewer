package io.github.yukio0.greenmailtestviewer.jupiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class OutputDirectory {

  static final String CONFIGURATION_PARAMETER = "greenmail.testviewer.output-dir";

  private static final String DIRECTORY_NAME = "greenmail-test-viewer";

  private OutputDirectory() {}

  /**
   * The configured directory, or {@code target/greenmail-test-viewer} for Maven and {@code
   * build/greenmail-test-viewer} otherwise, relative to the working directory of the module.
   */
  static Path resolve(Optional<String> configured, Path workingDirectory) {
    if (configured.isPresent() && !configured.get().isBlank()) {
      return workingDirectory.resolve(configured.get()).normalize();
    }
    String buildDirectory = Files.exists(workingDirectory.resolve("pom.xml")) ? "target" : "build";
    return workingDirectory.resolve(buildDirectory).resolve(DIRECTORY_NAME).normalize();
  }
}
