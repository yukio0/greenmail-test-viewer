package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Route;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HistoryScreenTest extends ScreenTestBase {

  @BeforeEach
  void openScreen() {
    writeRun();
    open();
  }

  @Test
  void backAndForwardGoThroughTheSelections() {
    select("InvoiceTest");
    assertThat(listTitle()).hasText("InvoiceTest");
    card("Invoice 42").click();
    assertThat(detailHeading()).hasText("Invoice 42");

    page.goBack();
    assertThat(detailHeading()).hasText("InvoiceTest");
    assertThat(page.locator("#list .message-card[aria-current=true]")).hasCount(0);

    page.goBack();
    assertThat(listTitle()).hasText("All messages");
    assertThat(cards()).hasCount(5);

    page.goForward();
    assertThat(listTitle()).hasText("InvoiceTest");
    assertThat(cards()).hasCount(2);

    page.goForward();
    assertThat(detailHeading()).hasText("Invoice 42");
    assertThat(card("Invoice 42")).hasAttribute("aria-current", "true");
  }

  @Test
  void backDoesNotShowAMailThatTheSearchHides() {
    card("Friendly reminder").click();
    assertThat(detailHeading()).hasText("Friendly reminder");
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");
    page.locator("#search").fill("nothing like this");
    assertThat(page.locator("#list")).containsText("No messages");
    assertThat(detail()).containsText("Select a message");

    page.goBack();
    assertThat(page.locator("#list")).containsText("No messages");
    assertThat(detail()).containsText("Select a message");
    assertThat(page).not().hasURL(Pattern.compile("message="));
  }

  @Test
  void clearingTheSearchWhileBackLoadsAMailKeepsTheMail() {
    card("Friendly reminder").click();
    assertThat(detailHeading()).hasText("Friendly reminder");
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");
    page.locator("#search").fill("nothing like this");
    assertThat(page.locator("#list")).containsText("No messages");

    List<Route> details = new ArrayList<>();
    List<Route> lists = new ArrayList<>();
    boolean[] holding = {true};
    page.route(
        url -> url.contains("/api/messages"),
        route -> {
          String url = route.request().url();
          String path = URI.create(url).getPath();
          if (holding[0] && path.equals("/api/messages") && !url.contains("q=")) {
            lists.add(route);
          } else if (holding[0] && path.matches("/api/messages/[^/]+")) {
            details.add(route);
          } else {
            route.resume();
          }
        });

    page.goBack();
    page.waitForCondition(() -> !details.isEmpty());
    page.locator("#search").fill("");
    page.waitForCondition(() -> !lists.isEmpty());
    details.forEach(Route::resume);
    assertThat(detailHeading()).hasText("Friendly reminder");

    holding[0] = false;
    lists.forEach(Route::resume);
    assertThat(cards()).hasCount(5);
    assertThat(card("Friendly reminder")).hasAttribute("aria-current", "true");
    assertThat(detailHeading()).hasText("Friendly reminder");
    assertThat(page).hasURL(Pattern.compile("message="));
  }

  @Test
  void anAutomaticRefreshWhileBackLoadsAnotherListKeepsTheMail() {
    select("InvoiceTest");
    assertThat(cards()).hasCount(2);
    card("Invoice 42").click();
    assertThat(detailHeading()).hasText("Invoice 42");
    select("SignupTest");
    assertThat(cards()).hasCount(2);

    List<Route> lists = new ArrayList<>();
    List<Route> trees = new ArrayList<>();
    boolean[] holdingLists = {true};
    boolean[] holdingTrees = {false};
    page.route(
        url -> url.contains("/api/"),
        route -> {
          String path = URI.create(route.request().url()).getPath();
          if (holdingLists[0] && path.equals("/api/messages")) {
            lists.add(route);
          } else if (holdingTrees[0] && path.equals("/api/tree")) {
            trees.add(route);
          } else {
            route.resume();
          }
        });

    output
        .run("run2", "2026-11-01T11:00:00.000Z")
        .testMail(SIGNUP, "sendsWelcome", "Welcome back", "2026-11-01T11:00:01.000Z");
    readRecordsAgain();
    page.waitForCondition(() -> !lists.isEmpty());

    holdingTrees[0] = true;
    page.goBack();
    page.waitForCondition(() -> !trees.isEmpty());
    holdingLists[0] = false;
    lists.forEach(Route::resume);
    assertThat(detailHeading()).hasText("Invoice 42");

    holdingTrees[0] = false;
    trees.forEach(Route::resume);
    assertThat(listTitle()).hasText("InvoiceTest");
    assertThat(card("Invoice 42")).hasAttribute("aria-current", "true");
    assertThat(detailHeading()).hasText("Invoice 42");
    assertThat(page).hasURL(Pattern.compile("message="));
  }

  @Test
  void reloadingShowsTheSameSelection() {
    select("InvoiceTest");
    assertThat(cards()).hasCount(2);
    card("Invoice 42").click();
    assertThat(detailHeading()).hasText("Invoice 42");

    page.reload();
    assertThat(treeItem("InvoiceTest")).hasAttribute("aria-selected", "true");
    assertThat(cards()).hasCount(2);
    assertThat(detailHeading()).hasText("Invoice 42");
    assertThat(card("Invoice 42")).hasAttribute("aria-current", "true");
  }

  @Test
  void movingThroughTheListWithTheKeyboardDoesNotFillTheHistory() {
    select("SignupTest");
    assertThat(cards()).hasCount(2);
    card("Friendly reminder").click();
    assertThat(detailHeading()).hasText("Friendly reminder");

    page.keyboard().press("ArrowDown");
    assertThat(detailHeading()).hasText("Welcome aboard");

    page.goBack();
    assertThat(listTitle()).hasText("SignupTest");
    assertThat(detailHeading()).hasText("SignupTest");
  }
}
