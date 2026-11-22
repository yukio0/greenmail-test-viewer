package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BrowsingScreenTest extends ScreenTestBase {

  @Test
  void listsAllMailsAtFirst() {
    writeRun();
    open();

    assertThat(treeItem("All messages")).hasAttribute("aria-selected", "true");
    assertThat(listTitle()).hasText("All messages");
    assertThat(page.locator("#list-count")).hasText("5");
    assertThat(cards()).hasCount(5);
    assertThat(cards().first()).containsText("Nightly report");
    assertThat(cards().last()).containsText("Welcome aboard");
    assertThat(detail()).containsText("Select a message");
  }

  @Test
  void tellsWhatCouldNotBeReadWithoutSayingThatTheMailsAreNotShown() throws IOException {
    RunFiles run = writeRun();
    Path broken = run.directory().resolve("tests/000099.json");
    Files.writeString(broken, "{ not json");
    open();

    page.locator("#problems-button").click();

    Locator panel = page.locator("#problems-panel");
    assertThat(panel).containsText("Some records could not be read");
    assertThat(panel)
        .containsText(
            "The viewer reads them again when they change. Its log has the same messages.");
    assertThat(panel).not().containsText("Their mails are not shown");
    assertThat(panel.locator(".problems-list li")).hasCount(1);
    assertThat(panel.locator(".problems-list li")).containsText(broken.toString());
    assertThat(card("Welcome aboard")).isVisible();
  }

  @Test
  void selectingAClassOrATestListsItsMails() {
    writeRun();
    open();

    select("InvoiceTest");
    assertThat(treeItem("InvoiceTest")).hasAttribute("aria-selected", "true");
    assertThat(listTitle()).hasText("InvoiceTest");
    assertThat(cards()).hasCount(2);
    assertThat(detailHeading()).hasText("InvoiceTest");

    select("sendsInvoice()");
    assertThat(listTitle()).hasText("sendsInvoice()");
    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("Invoice 42");

    select("(Outside test classes)");
    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("Nightly report");
  }

  @Test
  void showsTheFailureOfAFailedTest() {
    writeRun();
    open();

    select("sendsRefund()");
    assertThat(detailHeading()).hasText("InvoiceTest › sendsRefund()");
    assertThat(page.locator("#detail .detail-failure")).containsText("AssertionError");
    assertThat(cards()).hasCount(1);
  }

  @Test
  void aClassWhoseSetupAnAssumptionAbortedIsNotAFailure() {
    RunFiles first = output.run("run1", "2026-11-01T09:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", "2026-11-01T09:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T09:00:02.000Z");
    first.finish("2026-11-01T09:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T10:00:00.000Z");
    second.classAborted(
        SIGNUP, RecordFiles.classId(SIGNUP), "beforeAll", "2026-11-01T10:00:01.000Z");
    second.finish("2026-11-01T10:00:02.000Z");
    open();

    assertThat(treeItem("SignupTest").locator(".marker").first()).hasText("Setup aborted");
    assertThat(page.locator("#failed-count")).hasText("0");
    assertThat(page.locator("#failed-only")).isDisabled();

    select("sendsWelcome()");
    assertThat(page.locator("#detail .detail-failure")).containsText("The test was aborted");
    select("SignupTest");
    assertThat(page.locator("#detail .detail-failure"))
        .containsText("The setup of the test class was aborted");
  }

  @Test
  void showsTheContentOfTheSelectedMail() {
    writeRun();
    open();

    card("Invoice 42").click();
    assertThat(card("Invoice 42")).hasAttribute("aria-current", "true");
    assertThat(detailHeading()).hasText("Invoice 42");
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    assertThat(page.frameLocator("#detail iframe.html-preview").locator("b")).hasText("42 EUR");

    tab("Text").click();
    assertThat(tab("Text")).hasAttribute("aria-selected", "true");
    assertThat(tabPanel()).containsText("Total: 42 EUR");

    tab("Headers").click();
    assertThat(tabPanel()).containsText("shop@example.com");

    tab("Links").click();
    assertThat(tabPanel()).containsText("https://example.com/invoices/42");
    Locator help =
        tabPanel()
            .locator(".link-item")
            .filter(new Locator.FilterOptions().setHasText("//example.com/help"));
    assertThat(help.getByRole(AriaRole.LINK)).hasAttribute("href", "//example.com/help");
  }

  @Test
  void theDetailOfAClassListsItsTestsThatDidNotPassToGoToThem() {
    writeRun();
    open();

    select("InvoiceTest");
    Locator notPassed = detail().locator(".node-tests");
    assertThat(notPassed).containsText("sendsRefund()");
    assertThat(notPassed).containsText("java.lang.AssertionError: sendsRefund failed");
    assertThat(notPassed).not().containsText("sendsInvoice()");

    notPassed.getByRole(AriaRole.BUTTON).click();
    assertThat(treeItem("sendsRefund()")).hasAttribute("aria-selected", "true");
    assertThat(cards()).hasCount(1);
    assertThat(card("Refund issued")).isVisible();
    assertThat(page).hasURL(Pattern.compile("node="));
  }

  @Test
  void theTabOfTheBrowserHasAnIconAndTellsTheFailures() {
    writeRun();
    open();

    assertThat(page.locator("link[rel=icon]")).hasAttribute("href", "favicon.svg");
    Object icon =
        page.evaluate(
            "async () => {"
                + " const response = await fetch(document.querySelector('link[rel=icon]').href);"
                + " return response.status + ' ' + response.headers.get('Content-Type');"
                + " }");
    org.assertj.core.api.Assertions.assertThat(icon).isEqualTo("200 image/svg+xml");
    assertThat(page).hasTitle("1 failed \u00b7 GreenMail Test Viewer");
  }

  @Test
  void theTabOfTheBrowserHasOnlyTheNameWithoutFailures() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");
    open();

    assertThat(cards()).hasCount(1);
    assertThat(page).hasTitle("GreenMail Test Viewer");
  }

  @Test
  void theDetailOfAClassUsesSingularAndPluralForItsMessages() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.testMail(INVOICE, "sendsInvoice", "Invoice 42", "2026-11-01T10:00:03.000Z");
    run.test(INVOICE, "sendsInvoice", "passed", "2026-11-01T10:00:04.000Z");
    run.testMail(INVOICE, "sendsRefund", "Refund issued", "2026-11-01T10:00:05.000Z");
    run.test(INVOICE, "sendsRefund", "passed", "2026-11-01T10:00:06.000Z");
    run.test("com.example.QuietTest", "sendsNothing", "passed", "2026-11-01T10:00:07.000Z");
    run.finish("2026-11-01T10:00:08.000Z");
    open();

    select("SignupTest");
    assertThat(detail())
        .containsText("This class sent 1 message. Choose one in the list to see it.");
    select("InvoiceTest");
    assertThat(detail())
        .containsText("This class sent 2 messages. Choose one in the list to see it.");
    select("QuietTest");
    assertThat(detail()).containsText("This class sent no messages.");
  }

  @Test
  void theNameOfTheViewerIsItsHeadingAndLeadsBackToTheStart() {
    writeRun();
    open();
    page.locator("#search").fill("Invoice");
    card("Invoice 42").click();
    assertThat(detailHeading()).hasText("Invoice 42");
    assertThat(page).hasURL(Pattern.compile("#.*message="));

    Locator name =
        page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("GreenMail Test Viewer"));
    assertThat(page.locator("h1")).hasText("GreenMail Test Viewer");
    assertThat(page.locator(".top-bar")).not().containsText("Emails sent by tests");
    name.click();

    assertThat(page).hasURL(Pattern.compile("^http://127\\.0\\.0\\.1:\\d+/$"));
    assertThat(page.locator("#search")).hasValue("");
    assertThat(cards()).hasCount(5);
    assertThat(page.locator("#list .message-card.selected")).hasCount(0);

    page.setViewportSize(1000, 800);
    assertThat(name).isVisible();
  }

  @Test
  void theListShowsAShortDateWithTheWholeOneInItsTooltip() {
    writeRun();
    open();

    Locator date = card("Invoice 42").locator(".message-meta > span").first();
    String whole = date.getAttribute("title");
    org.assertj.core.api.Assertions.assertThat(whole).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(date.textContent())
        .isNotBlank()
        .hasSizeLessThan(whole.length());
  }

  @Test
  void opensAMailWithoutHtmlOnItsText() {
    writeRun();
    open();

    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");
    assertThat(tab("Text")).hasAttribute("aria-selected", "true");
    assertThat(tabPanel()).containsText("Body of Welcome aboard");
  }

  @Test
  void searchingNarrowsTheListAndMarksTheTerms() {
    writeRun();
    open();
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");

    page.locator("#search").fill("aboard");
    assertThat(cards()).hasCount(1);
    assertThat(cards().locator("mark").first()).hasText("aboard");
    assertThat(detailHeading()).hasText("Welcome aboard");

    page.locator("#search").fill("nothing like this");
    assertThat(page.locator("#list")).containsText("No messages");
    assertThat(detail()).containsText("Select a message");
  }

  @Test
  void marksTheTermsAfterCharactersThatAreLongerInLowerCase() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(
        SIGNUP, "sendsOffer", "=?UTF-8?Q?=C4=B0stanbul?= unique offer", "2026-11-01T10:00:01.000Z");
    open();

    page.locator("#search").fill("unique");
    assertThat(cards()).hasCount(1);
    Locator subject = cards().locator(".message-subject");
    assertThat(subject).hasText("İstanbul unique offer");
    assertThat(subject.locator("mark")).hasText("unique");

    page.locator("#search").fill("offer i");
    assertThat(subject.locator("mark")).hasText(new String[] {"İ", "i", "offer"});
  }

  @Test
  void failedOnlyShowsOnlyTheFailedTests() {
    writeRun();
    open();
    assertThat(page.locator("#failed-count")).hasText("1");

    page.locator("#failed-only").click();
    assertThat(page.locator("#failed-only")).hasAttribute("aria-pressed", "true");
    assertThat(treeItem("sendsRefund()")).isVisible();
    assertThat(treeItem("sendsInvoice()")).hasCount(0);
    assertThat(treeItem("SignupTest")).hasCount(0);

    page.locator("#failed-only").click();
    assertThat(treeItem("SignupTest")).isVisible();
  }

  @Test
  void anEarlierRunShowsItsOwnMails() {
    RunFiles first = output.run("run1", "2026-11-01T09:00:00.000Z");
    first.testMail(SIGNUP, "sendsWelcome", "Welcome, first try", "2026-11-01T09:00:01.000Z");
    first.test(SIGNUP, "sendsWelcome", "failed", "2026-11-01T09:00:02.000Z");
    first.finish("2026-11-01T09:00:03.000Z");
    RunFiles second = output.run("run2", "2026-11-01T10:00:00.000Z");
    second.testMail(SIGNUP, "sendsWelcome", "Welcome, second try", "2026-11-01T10:00:01.000Z");
    second.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    second.finish("2026-11-01T10:00:03.000Z");
    open();
    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("second try");
    assertThat(page.locator("#failed-count")).hasText("0");

    page.locator("#run-select").selectOption(new SelectOption().setIndex(2));
    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("first try");
    assertThat(page.locator("#failed-count")).hasText("1");
  }
}
