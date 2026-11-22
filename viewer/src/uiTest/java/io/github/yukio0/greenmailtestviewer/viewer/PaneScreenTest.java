package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class PaneScreenTest extends ScreenTestBase {

  @Test
  void widthsChosenOnAWideScreenLeaveRoomOnNarrowerOnes() {
    writeRun();
    open(1920, 1000);

    page.locator("[data-resize=tree]").focus();
    page.keyboard().press("End");
    page.locator("[data-resize=list]").focus();
    page.keyboard().press("End");
    Assertions.assertThat(width(".tree-pane")).isCloseTo(640, Assertions.within(1.0));
    Assertions.assertThat(width(".message-list-pane")).isCloseTo(720, Assertions.within(1.0));

    page.reload();
    assertThat(cards()).hasCount(5);
    Assertions.assertThat(width(".tree-pane")).isCloseTo(640, Assertions.within(1.0));

    page.setViewportSize(1280, 900);
    Assertions.assertThat(width(".message-detail-pane")).isGreaterThanOrEqualTo(320);

    page.setViewportSize(1000, 800);
    Assertions.assertThat(width(".message-list-pane")).isGreaterThanOrEqualTo(220);
  }

  @Test
  void aNarrowScreenShowsTheDetailBelowOnlyWhenSomethingIsSelected() {
    writeRun();
    open(900, 800);
    assertThat(page.locator(".message-detail-pane")).isHidden();

    card("Invoice 42").click();
    assertThat(page.locator(".message-detail-pane")).isVisible();
    assertThat(detailHeading()).hasText("Invoice 42");
    double listBottom = bottom(".message-list-pane");
    Assertions.assertThat(top(".message-detail-pane")).isGreaterThanOrEqualTo(listBottom - 1);
  }

  @Test
  void onAPhoneEachItemOfTheRunInformationIsOnOneLine() {
    writeRun();
    open(390, 800);

    page.locator("#run-info-button").click();
    var item = page.locator("#run-info-panel dl > div").first();
    var name = item.locator("dt").boundingBox();
    var value = item.locator("dd").boundingBox();
    Assertions.assertThat(value.y).isCloseTo(name.y, Assertions.within(2.0));
    Assertions.assertThat(value.x).isGreaterThan(name.x + name.width);
  }

  private double width(String selector) {
    return page.locator(selector).boundingBox().width;
  }

  private double top(String selector) {
    return page.locator(selector).boundingBox().y;
  }

  private double bottom(String selector) {
    var box = page.locator(selector).boundingBox();
    return box.y + box.height;
  }
}
