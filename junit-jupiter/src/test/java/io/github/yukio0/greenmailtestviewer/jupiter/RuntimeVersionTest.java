package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.icegreen.greenmail.util.GreenMail;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RuntimeVersionTest {

  @Test
  void runsOnTheJavaVersionTheModuleTargets() {
    String expected = System.getProperty("greenmail.testviewer.expectedJavaVersion");
    assumeTrue(expected != null, "expected Java version is not set");

    assertThat(Runtime.version().feature()).isEqualTo(Integer.parseInt(expected));
  }

  @Test
  void runsWithTheGreenMailVersionOfTheTask() throws IOException {
    String expected = System.getProperty("greenmail.testviewer.expectedGreenMailVersion");
    assumeTrue(expected != null, "expected GreenMail version is not set");

    assertThat(greenMailVersion()).isEqualTo(expected);
  }

  static String greenMailVersion() throws IOException {
    try (InputStream in =
        GreenMail.class.getResourceAsStream(
            "/META-INF/maven/com.icegreen/greenmail/pom.properties")) {
      Properties properties = new Properties();
      properties.load(in);
      return properties.getProperty("version");
    }
  }
}
