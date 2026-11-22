package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeyboardScreenTest extends ScreenTestBase {

  @BeforeEach
  void openScreen() {
    writeRun();
    open();
  }

  @Test
  void theArrowKeysMoveThroughTheTreeAndEnterSelects() {
    treeItem("All messages").focus();

    page.keyboard().press("ArrowDown");
    assertThat(treeItem("InvoiceTest")).isFocused();
    page.keyboard().press("Enter");
    assertThat(listTitle()).hasText("InvoiceTest");

    page.keyboard().press("ArrowLeft");
    assertThat(treeItem("InvoiceTest")).hasAttribute("aria-expanded", "false");
    assertThat(treeItem("sendsInvoice()")).hasCount(0);
    page.keyboard().press("ArrowRight");
    assertThat(treeItem("InvoiceTest")).hasAttribute("aria-expanded", "true");

    page.keyboard().press("ArrowRight");
    assertThat(treeItem("sendsInvoice()")).isFocused();
    page.keyboard().press(" ");
    assertThat(listTitle()).hasText("sendsInvoice()");

    page.keyboard().press("ArrowLeft");
    assertThat(treeItem("InvoiceTest")).isFocused();
    page.keyboard().press("Home");
    assertThat(treeItem("All messages")).isFocused();
  }

  @Test
  void theArrowKeysSelectTheMailAboveOrBelow() {
    cards().first().focus();

    page.keyboard().press("ArrowDown");
    assertThat(detailHeading()).hasText("Refund issued");
    assertThat(card("Refund issued")).isFocused();

    page.keyboard().press("End");
    assertThat(detailHeading()).hasText("Welcome aboard");
    page.keyboard().press("ArrowUp");
    assertThat(detailHeading()).hasText("Friendly reminder");
    page.keyboard().press("Home");
    assertThat(detailHeading()).hasText("Nightly report");
  }

  @Test
  void slashMovesToTheSearchField() {
    page.keyboard().press("/");
    assertThat(page.locator("#search")).isFocused();

    page.keyboard().type("aboard");
    assertThat(cards()).hasCount(1);
    page.keyboard().type(" /");
    assertThat(page.locator("#search")).hasValue("aboard /");
  }

  @Test
  void theArrowKeysMoveBetweenTheTabs() {
    card("Invoice 42").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    tab("HTML").focus();

    page.keyboard().press("ArrowRight");
    assertThat(tab("HTML Source")).hasAttribute("aria-selected", "true");
    assertThat(tab("HTML Source")).isFocused();
    page.keyboard().press("End");
    assertThat(tab("Links")).hasAttribute("aria-selected", "true");
    page.keyboard().press("ArrowRight");
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    page.keyboard().press("ArrowLeft");
    assertThat(tab("Links")).hasAttribute("aria-selected", "true");
    assertThat(tabPanel()).containsText("https://example.com/invoices/42");
  }

  @Test
  void escapeClosesThePanelsAboveTheTree() {
    page.locator("#run-info-button").focus();
    page.keyboard().press("Enter");
    assertThat(page.locator("#run-info-panel")).isVisible();

    page.keyboard().press("Escape");
    assertThat(page.locator("#run-info-panel")).isHidden();
    assertThat(page.locator("#run-info-button")).isFocused();

    page.locator("#run-info-button").click();
    page.locator("#search").focus();
    page.keyboard().press("Escape");
    assertThat(page.locator("#run-info-panel")).isVisible();
  }
}
