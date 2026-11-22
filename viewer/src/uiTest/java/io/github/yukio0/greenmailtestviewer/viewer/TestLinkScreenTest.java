package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestLinkScreenTest extends ScreenTestBase {

  @BeforeEach
  void openLinkToATestThatIsNotRecorded() {
    writeRun();
    String test = RecordFiles.methodId(SIGNUP, "sendsLater");
    open(1440, 900, "#test=" + URLEncoder.encode(test, StandardCharsets.UTF_8));
    assertThat(listTitle()).hasText("All messages");
    assertThat(cards()).hasCount(5);
  }

  @Test
  void aNodeChosenWhileTheLinkWaitsForItsTestIsKept() {
    select("InvoiceTest");
    assertThat(listTitle()).hasText("InvoiceTest");
    assertThat(page).hasURL(Pattern.compile("node="));
    assertThat(page).not().hasURL(Pattern.compile("test="));

    output
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testMail(INVOICE, "sendsInvoice", "Invoice 43", "2026-11-01T11:00:01.000Z");
    readRecordsAgain();
    assertThat(card("Invoice 43")).isVisible();
    assertThat(listTitle()).hasText("InvoiceTest");
    assertThat(treeItem("InvoiceTest")).hasAttribute("aria-selected", "true");
  }

  @Test
  void aMailThatTheSearchHidesIsUnselectedWhileTheLinkWaitsForItsTest() {
    card("Friendly reminder").click();
    assertThat(detailHeading()).hasText("Friendly reminder");

    page.locator("#search").fill("nothing like this");
    assertThat(page.locator("#list")).containsText("No messages");
    assertThat(detail()).containsText("Select a message");
    assertThat(page).not().hasURL(Pattern.compile("message="));
  }
}
