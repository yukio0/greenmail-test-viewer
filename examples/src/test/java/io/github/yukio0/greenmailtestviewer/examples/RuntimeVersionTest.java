package io.github.yukio0.greenmailtestviewer.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class RuntimeVersionTest {

  @Test
  void runsOnTheJavaVersionTheModuleTargets() {
    String expected = System.getProperty("greenmail.testviewer.expectedJavaVersion");
    assumeTrue(expected != null, "expected Java version is not set");

    assertThat(Runtime.version().feature()).isEqualTo(Integer.parseInt(expected));
  }
}
