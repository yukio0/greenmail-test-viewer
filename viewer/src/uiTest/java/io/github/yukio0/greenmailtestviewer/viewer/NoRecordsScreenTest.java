package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoRecordsScreenTest extends ScreenTestBase {

  @BeforeEach
  void removeTheOutputDirectory() throws IOException {
    Files.delete(output.directory().resolve(SourceFinder.MARKER_FILE_NAME));
  }

  @Test
  void theDetailTellsWhatToDoAndWhereTheViewerLooks() {
    open();

    assertThat(detail()).containsText("No records found yet.");
    assertThat(detail())
        .containsText("Add @GreenMailTestViewer to the test classes that use GreenMail.");
    assertThat(detail().locator(".searched-directories"))
        .containsText(project.getFileName().toString());
    assertThat(page.locator("#list")).containsText("No records found yet.");

    output = RecordFiles.create(output.directory());
    writeRun();
    findRecordsAgain();
    assertThat(treeItem("All messages")).isVisible();
    assertThat(detail()).containsText("Select a message");
    assertThat(detail()).not().containsText("No records found yet.");
  }

  @Test
  void aNarrowScreenShowsTheDetailWithWhatToDo() {
    open(800, 600);

    assertThat(detail()).isVisible();
    assertThat(detail()).containsText("Run the tests. New records appear here automatically.");
  }
}
