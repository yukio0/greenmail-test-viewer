package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;

class SpringBeanGreenMailVerificationTest {

  @TempDir Path outputDirectory;

  @Test
  void recordsMailsOfAGreenMailBeanInjectedIntoAField() throws IOException {
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(SpringBeanGreenMailExampleTest.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(1).failed(0));

    List<Path> mails = recordedMails();
    assertThat(mails).hasSize(1);
    String metadata = Files.readString(mails.get(0), StandardCharsets.UTF_8);
    assertThat(metadata)
        .contains("\"testClass\": \"" + SpringBeanGreenMailExampleTest.class.getName() + "\"")
        .contains("\"testMethod\": \"sendsWelcomeMail\"")
        .contains("\"phase\": \"test\"")
        .contains("\"deliveredTo\": [\"frank@example.com\"]");
  }

  private List<Path> recordedMails() throws IOException {
    try (Stream<Path> files = Files.walk(outputDirectory.resolve("runs"))) {
      return files
          .filter(file -> file.getParent().getFileName().toString().equals("messages"))
          .filter(file -> file.getFileName().toString().endsWith(".json"))
          .toList();
    }
  }
}
