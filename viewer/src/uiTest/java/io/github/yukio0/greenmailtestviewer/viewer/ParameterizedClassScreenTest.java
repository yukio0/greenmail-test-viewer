package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParameterizedClassScreenTest extends ScreenTestBase {

  private static final String LOCALIZED = "com.example.LocalizedTest";

  @BeforeEach
  void writeRecords() {
    RecordFiles.RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.classInvocationTest(
        LOCALIZED, 1, "[1] language=en", "welcomes", "Welcome en", "2026-11-01T10:00:01.000Z");
    run.classInvocationTest(
        LOCALIZED, 2, "[2] language=ja", "welcomes", "Welcome ja", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");
  }

  @Test
  void tellsApartTheInvocationsOfAParameterizedClass() {
    open();

    assertThat(treeItem("LocalizedTest [1] language=en")).isVisible();
    assertThat(treeItem("LocalizedTest [2] language=ja")).isVisible();
    assertThat(card("Welcome ja")).containsText("LocalizedTest [2] language=ja › welcomes()");
    card("Welcome ja").click();
    assertThat(detail()).containsText("LocalizedTest [2] language=ja › welcomes()");
  }
}
