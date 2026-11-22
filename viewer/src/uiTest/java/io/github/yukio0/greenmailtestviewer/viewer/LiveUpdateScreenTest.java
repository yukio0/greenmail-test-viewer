package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LiveUpdateScreenTest extends ScreenTestBase {

  private static final Instant START = Instant.parse("2026-11-01T10:00:00Z");

  @Test
  void aMailChosenWhileTheViewerCannotBeReachedIsShownOnceItCanBe() {
    writeRun();
    open();

    page.route("**/api/**", Route::abort);
    Locator liveUpdate = page.locator("#live-update");
    assertThat(liveUpdate).hasText("Offline, retrying");
    assertThat(liveUpdate).hasClass(Pattern.compile("(^| )offline( |$)"));
    card("Invoice 42").click();
    assertThat(detail()).containsText("The viewer cannot be reached.");

    page.unroute("**/api/**");
    assertThat(liveUpdate).hasText("Auto refresh");
    assertThat(detailHeading()).hasText("Invoice 42");
    forgetFailedRequests();
  }

  @Test
  void aMailThatCouldNotBeLoadedIsLoadedWhenTheScreenChecksNext() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    boolean[] failed = {false};
    page.route(
        url -> mail.matcher(url).find(),
        route -> {
          if (failed[0]) {
            route.resume();
          } else {
            failed[0] = true;
            route.abort();
          }
        });
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This page keeps retrying.");

    assertThat(detailHeading()).hasText("Welcome aboard");
    forgetFailedRequests();
  }

  @Test
  void aMailWhoseAnswerWasCutOffIsLoadedWhenTheScreenChecksNext() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    boolean[] cut = {false};
    page.route(
        url -> mail.matcher(url).find(),
        route -> {
          if (cut[0]) {
            route.resume();
          } else {
            cut[0] = true;
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(200)
                    .setContentType("application/json")
                    .setBody("{\"id\": \"run1-0"));
          }
        });
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This page keeps retrying.");

    assertThat(detailHeading()).hasText("Welcome aboard");
  }

  @Test
  void aMailThatTheViewerCouldNotReadForNowIsLoadedWhenTheScreenChecksNext() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    boolean[] held = {false};
    page.route(
        url -> mail.matcher(url).find(),
        route -> {
          if (held[0]) {
            route.resume();
          } else {
            held[0] = true;
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(503)
                    .setContentType("application/json")
                    .setBody("{\"error\": \"This message could not be read: it is in use.\"}"));
          }
        });
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This message could not be read: it is in use.");

    assertThat(detailHeading()).hasText("Welcome aboard");
    forgetErrors("503");
  }

  @Test
  void theCloseButtonOfTheReadProblemsKeepsTheFocusWhenTheyChange() throws IOException {
    RunFiles run = writeRun();
    Files.writeString(run.directory().resolve("tests/000098.json"), "{ not json");
    open();
    page.locator("#problems-button").click();
    page.locator("#problems-panel .panel-header button").focus();

    Files.writeString(run.directory().resolve("tests/000099.json"), "{ not json");
    readRecordsAgain();
    assertThat(page.locator("#problems-button")).hasText("2 read problems");

    assertThat(page.locator("#problems-panel .panel-header button")).isFocused();
    page.keyboard().press("Enter");
    assertThat(page.locator("#problems-panel")).isHidden();
    assertThat(page.locator("#problems-button")).isFocused();
  }

  @Test
  void theFocusGoesToTheRunInformationWhenTheLastReadProblemGoes() throws IOException {
    RunFiles run = writeRun();
    Path broken = run.directory().resolve("tests/000098.json");
    Files.writeString(broken, "{ not json");
    open();
    page.locator("#problems-button").click();

    for (String focused :
        new String[] {"#problems-panel .panel-header button", "#problems-button"}) {
      assertThat(page.locator("#problems-button")).isVisible();
      page.locator(focused).focus();

      Files.delete(broken);
      readRecordsAgain();
      assertThat(page.locator("#problems-button")).isHidden();

      assertThat(page.locator("#run-info-button")).isFocused();
      Files.writeString(broken, "{ not json");
      readRecordsAgain();
    }
  }

  @Test
  void theCloseButtonOfTheRunInformationKeepsTheFocusWhenANewRunIsShown() {
    writeRun();
    open();
    page.locator("#run-info-button").click();
    page.locator("#run-info-panel .panel-header button").focus();

    output
        .run("run2", START.plusSeconds(3600).toString())
        .testMail(SIGNUP, "sendsWelcome", "Welcome again", START.plusSeconds(3601).toString());
    readRecordsAgain();
    assertThat(page.locator("#run-info-panel")).containsText("runs/run2");

    assertThat(page.locator("#run-info-panel .panel-header button")).isFocused();
    page.keyboard().press("Enter");
    assertThat(page.locator("#run-info-panel")).isHidden();
    assertThat(page.locator("#run-info-button")).isFocused();
  }

  @Test
  void aFailedCheckOfTheRecordsIsNoLongerToldOnceACheckSucceeds() {
    writeRun();
    open();

    boolean[] checkFailed = {false};
    page.route(
        url -> url.endsWith("/api/status"),
        route -> {
          if (!checkFailed[0]) {
            checkFailed[0] = true;
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(503)
                    .setContentType("application/json")
                    .setBody("{\"error\": \"The records are being read again.\"}"));
          } else {
            route.resume();
          }
        });
    assertThat(page.locator("#error-banner")).containsText("The records are being read again.");

    assertThat(page.locator("#error-banner")).isHidden();
    assertThat(page.locator("#live-update")).not().hasClass(Pattern.compile("\\boffline\\b"));
    forgetErrors("503");
  }

  @Test
  void aMailThatCouldNotBeLoadedIsLoadedAlsoWhileTheListKeepsFailing() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    boolean[] mailFailed = {false};
    boolean[] listFails = {true};
    page.route(
        url -> url.contains("/api/messages"),
        route -> {
          String url = route.request().url();
          if (mail.matcher(url).find() && !mailFailed[0]) {
            mailFailed[0] = true;
            route.abort();
          } else if (url.contains("q=") && listFails[0]) {
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(500)
                    .setContentType("application/json")
                    .setBody("{\"error\": \"The list could not be made.\"}"));
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("reminder");
    assertThat(page.locator("#error-banner")).containsText("The list could not be made.");
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This page keeps retrying.");

    assertThat(detailHeading()).hasText("Welcome aboard");
    assertThat(page.locator("#error-banner")).containsText("The list could not be made.");
    listFails[0] = false;
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
    forgetErrors("500");
  }

  @Test
  void aRefreshWhoseListFailsStillLoadsTheMail() {
    RunFiles run = writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    boolean[] mailFailed = {false};
    boolean[] listFails = {true};
    page.route(
        url -> url.contains("/api/messages"),
        route -> {
          String url = route.request().url();
          if (mail.matcher(url).find() && !mailFailed[0]) {
            mailFailed[0] = true;
            route.abort();
          } else if (url.contains("q=") && listFails[0]) {
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(500)
                    .setContentType("application/json")
                    .setBody("{\"error\": \"The list could not be made.\"}"));
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("reminder");
    assertThat(page.locator("#error-banner")).containsText("The list could not be made.");
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This page keeps retrying.");
    run.test(SIGNUP, "sendsNothing", "passed", "2026-11-01T10:00:09.000Z");
    readRecordsAgain();

    assertThat(detailHeading()).hasText("Welcome aboard");
    listFails[0] = false;
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
    forgetErrors("500");
  }

  @Test
  void anErrorOfAListDoesNotComeBackAfterALaterListLoaded() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    List<Route> heldMails = new ArrayList<>();
    int[] mailRequests = {0};
    page.route(
        url -> url.contains("/api/messages"),
        route -> {
          String url = route.request().url();
          if (mail.matcher(url).find()) {
            mailRequests[0]++;
            if (mailRequests[0] == 1) {
              route.abort();
            } else if (mailRequests[0] == 2) {
              heldMails.add(route);
            } else {
              route.resume();
            }
          } else if (url.contains("q=nothing")) {
            route.fulfill(
                new Route.FulfillOptions()
                    .setStatus(500)
                    .setContentType("application/json")
                    .setBody("{\"error\": \"The list could not be made.\"}"));
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("nothing like this");
    assertThat(page.locator("#error-banner")).containsText("The list could not be made.");
    card("Welcome aboard").click();
    assertThat(detail()).containsText("This page keeps retrying.");
    page.waitForCondition(() -> !heldMails.isEmpty());

    page.locator("#search").fill("aboard");
    assertThat(page.locator("#list mark").first()).hasText("aboard");
    assertThat(page.locator("#error-banner")).isHidden();

    heldMails.get(0).resume();
    assertThat(detailHeading()).hasText("Welcome aboard");
    waitForNextCheck();
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
    forgetErrors("500");
  }

  @Test
  void aMailWhoseHtmlSourceChangedShowsItsNewSource() throws IOException {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(
        SIGNUP, "sendsWelcome", htmlMail("var version = 1;"), START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    open();
    card("Welcome aboard").click();
    tab("HTML Source").click();
    assertThat(tabPanel()).containsText("var version = 1;");

    Files.write(
        run.directory().resolve("messages").resolve("000001.eml"), htmlMail("var version = 2;"));
    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", START.plusSeconds(3).toString());
    readRecordsAgain();

    assertThat(tabPanel()).containsText("var version = 2;");
  }

  private static byte[] htmlMail(String script) {
    return ("From: shop@example.com\r\n"
            + "To: customer@example.com\r\n"
            + "Subject: Welcome aboard\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: text/html; charset=UTF-8\r\n"
            + "\r\n"
            + "<p>Welcome</p><script>"
            + script
            + "</script>\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aMailWhosePartIsEncodedOtherwiseShowsItsNewSource() throws IOException {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(
        SIGNUP, "sendsWelcome", multipartMail("hello=20world"), START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    open();
    card("Welcome aboard").click();
    tab("Raw").click();
    assertThat(tabPanel()).containsText("hello=20world");

    Files.write(
        run.directory().resolve("messages").resolve("000001.eml"), multipartMail("hello world"));
    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", START.plusSeconds(3).toString());
    readRecordsAgain();

    assertThat(tabPanel()).containsText("hello world");
    assertThat(tabPanel()).not().containsText("hello=20world");
  }

  private static byte[] multipartMail(String encodedText) {
    return ("From: shop@example.com\r\n"
            + "To: customer@example.com\r\n"
            + "Subject: Welcome aboard\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"part\"\r\n"
            + "\r\n"
            + "--part\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n"
            + "\r\n"
            + encodedText
            + "\r\n"
            + "--part--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aMailWhoseFileWasReplacedIsDrawnAgainAsAWhole() throws IOException {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    open();
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");
    assertThat(tabPanel()).containsText("Body of Welcome aboard");

    Files.writeString(
        run.directory().resolve("messages").resolve("000001.eml"),
        RecordFiles.eml("Welcome again", "Body of Welcome again"));
    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", START.plusSeconds(3).toString());
    readRecordsAgain();

    assertThat(detailHeading()).hasText("Welcome again");
    assertThat(tabPanel()).containsText("Body of Welcome again");
  }

  @Test
  void aMailThatTheViewerAnswersWithAnErrorIsNotAskedForAgain() {
    writeRun();
    open();

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    int[] requests = {0};
    page.route(
        url -> mail.matcher(url).find(),
        route -> {
          requests[0]++;
          route.fulfill(
              new Route.FulfillOptions()
                  .setStatus(500)
                  .setContentType("application/json")
                  .setBody("{\"error\": \"The viewer does not have enough memory.\"}"));
        });
    card("Welcome aboard").click();
    assertThat(detail()).containsText("The viewer does not have enough memory.");

    waitForNextCheck();
    waitForNextCheck();
    assertEquals(1, requests[0]);
    assertThat(detail()).containsText("The viewer does not have enough memory.");
    forgetErrors("500");
  }

  @Test
  void aRunThatCouldNotBeLoadedIsLoadedWhenTheScreenChecksNext() {
    writeTwoRuns();
    open();
    assertThat(cards()).containsText("second try");

    boolean[] treeFailed = {false};
    page.route(
        url -> url.contains("/api/tree?"),
        route -> {
          if (treeFailed[0]) {
            route.resume();
          } else {
            treeFailed[0] = true;
            route.abort();
          }
        });
    page.locator("#run-select").selectOption(new SelectOption().setIndex(2));
    page.waitForCondition(() -> treeFailed[0]);

    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("first try");
    assertThat(page.locator("#failed-count")).hasText("1");
    forgetFailedRequests();
  }

  @Test
  void theListOfAnAutomaticRefreshIsNotShownAfterChoosingAnotherRunFailed() {
    RunFiles second = writeTwoRuns();
    open();
    assertThat(cards()).containsText("second try");

    List<Route> lists = new ArrayList<>();
    boolean[] holdingLists = {true};
    boolean[] treeFailed = {false};
    page.route(
        url -> url.contains("/api/"),
        route -> {
          String url = route.request().url();
          String path = URI.create(url).getPath();
          if (holdingLists[0] && path.equals("/api/messages")) {
            lists.add(route);
          } else if (!treeFailed[0] && url.contains("/api/tree?")) {
            treeFailed[0] = true;
            route.abort();
          } else {
            route.resume();
          }
        });

    second.testMail(SIGNUP, "sendsReminder", "Reminder", START.plusSeconds(64).toString());
    second.test(SIGNUP, "sendsReminder", "passed", START.plusSeconds(65).toString());
    readRecordsAgain();
    page.waitForCondition(() -> !lists.isEmpty());

    page.locator("#run-select").selectOption(new SelectOption().setIndex(2));
    page.waitForCondition(() -> treeFailed[0]);
    holdingLists[0] = false;
    lists.forEach(Route::resume);

    assertThat(cards()).hasCount(1);
    assertThat(cards()).containsText("first try");
    assertThat(page.locator("#failed-count")).hasText("1");
    forgetFailedRequests();
  }

  @Test
  void aLateFailureOfARunNoLongerChosenIsNotShown() {
    writeTwoRuns();
    open();
    assertThat(cards()).containsText("second try");

    List<Route> held = new ArrayList<>();
    page.route(
        url -> url.contains("/api/tree?"),
        route -> {
          if (held.isEmpty()) {
            held.add(route);
          } else {
            route.resume();
          }
        });
    page.locator("#run-select").selectOption(new SelectOption().setIndex(1));
    page.waitForCondition(() -> !held.isEmpty());
    page.locator("#run-select").selectOption(new SelectOption().setIndex(2));
    assertThat(cards()).containsText("first try");
    assertThat(page.locator("#failed-count")).hasText("1");

    held.get(0).abort();
    waitForNextCheck();
    assertThat(page.locator("#error-banner")).isHidden();
    assertThat(cards()).containsText("first try");
    forgetFailedRequests();
  }

  @Test
  void aLateFailureOfAnEarlierSearchIsNotShown() {
    writeTwoRuns();
    open();

    List<Route> held = new ArrayList<>();
    page.route(
        url -> url.contains("/api/messages?") && url.contains("q="),
        route -> {
          if (held.isEmpty()) {
            held.add(route);
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("nothing like this");
    page.waitForCondition(() -> !held.isEmpty());
    page.locator("#search").fill("second");
    assertThat(page.locator("#list mark").first()).hasText("second");

    held.get(0).abort();
    waitForNextCheck();
    assertThat(page.locator("#error-banner")).isHidden();
    assertThat(cards()).hasCount(1);
    forgetFailedRequests();
  }

  @Test
  void aSearchThatCouldNotBeLoadedIsLoadedWhenTheScreenChecksNext() {
    writeTwoRuns();
    open();

    boolean[] listFailed = {false};
    page.route(
        url -> url.contains("/api/messages?") && url.contains("q="),
        route -> {
          if (listFailed[0]) {
            route.resume();
          } else {
            listFailed[0] = true;
            route.abort();
          }
        });
    page.locator("#search").fill("second");
    page.waitForCondition(() -> listFailed[0]);
    assertThat(page.locator("#error-banner")).isVisible();

    assertThat(page.locator("#list mark").first()).hasText("second");
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
  }

  @Test
  void aSearchThatFailsWhileAnAutomaticRefreshChecksTheMailIsLoadedAgain() {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", START.plusSeconds(3).toString());
    run.test(SIGNUP, "sendsReminder", "passed", START.plusSeconds(4).toString());
    open();
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");

    Pattern mail = Pattern.compile("/api/messages/[^/?]+$");
    List<Route> held = new ArrayList<>();
    boolean[] listFailed = {false};
    page.route(
        url -> url.contains("/api/messages"),
        route -> {
          String url = route.request().url();
          if (held.isEmpty() && mail.matcher(url).find()) {
            held.add(route);
          } else if (!listFailed[0] && url.contains("q=")) {
            listFailed[0] = true;
            route.abort();
          } else {
            route.resume();
          }
        });
    run.test(SIGNUP, "sendsNothing", "passed", START.plusSeconds(5).toString());
    readRecordsAgain();
    page.waitForCondition(() -> !held.isEmpty());
    page.locator("#search").fill("reminder");
    page.waitForCondition(() -> listFailed[0]);
    assertThat(page.locator("#error-banner")).isVisible();

    held.get(0).resume();
    assertThat(cards()).hasCount(1);
    assertThat(card("Friendly reminder")).isVisible();
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
  }

  @Test
  void morePagesThatCouldNotBeLoadedAreLoadedWhenTheScreenChecksNext() {
    RunFiles run = output.run("run1", START.toString());
    for (int i = 1; i <= 205; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    run.test(SIGNUP, "sendsMany", "passed", START.plusSeconds(300).toString());
    run.finish(START.plusSeconds(301).toString());
    open();
    assertThat(cards()).hasCount(200);

    boolean[] listFailed = {false};
    page.route(
        url -> url.contains("/api/messages?") && url.contains("limit=400"),
        route -> {
          if (listFailed[0]) {
            route.resume();
          } else {
            listFailed[0] = true;
            route.abort();
          }
        });
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show 5 more")).click();
    page.waitForCondition(() -> listFailed[0]);
    assertThat(page.locator("#error-banner")).isVisible();

    assertThat(cards()).hasCount(205);
    assertThat(cards().nth(200)).isFocused();
    assertThat(page.locator("#list-status")).hasText("Showing 205 of 205");
    assertThat(page.locator("#error-banner")).isHidden();
    forgetFailedRequests();
  }

  @Test
  void showMoreOfAListThatIsBeingReplacedDoesNothing() {
    RunFiles run = output.run("run1", START.toString());
    for (int i = 1; i <= 250; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    run.test(SIGNUP, "sendsMany", "passed", START.plusSeconds(300).toString());
    open();
    assertThat(cards()).hasCount(200);

    List<Route> held = new ArrayList<>();
    page.route(
        url -> url.contains("/api/messages?") && url.contains("q="),
        route -> {
          if (held.isEmpty()) {
            held.add(route);
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("Mail");
    page.waitForCondition(() -> !held.isEmpty());
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show 50 more")).click();
    held.get(0).resume();

    assertThat(page.locator("#list mark").first()).hasText("Mail");
    assertThat(cards()).hasCount(200);
  }

  @Test
  void morePagesAreShownWhenARefreshReplacesTheirRequest() {
    RunFiles run = output.run("run1", START.toString());
    for (int i = 1; i <= 250; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    run.test(SIGNUP, "sendsMany", "passed", START.plusSeconds(300).toString());
    open();
    assertThat(cards()).hasCount(200);

    List<Route> held = new ArrayList<>();
    page.route(
        url -> url.contains("/api/messages?") && url.contains("limit=400"),
        route -> {
          if (held.isEmpty()) {
            held.add(route);
          } else {
            route.resume();
          }
        });
    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show 50 more")).click();
    page.waitForCondition(() -> !held.isEmpty());
    run.test(SIGNUP, "sendsNothing", "passed", START.plusSeconds(301).toString());
    readRecordsAgain();

    assertThat(cards()).hasCount(250);
    assertThat(cards().nth(200)).isFocused();
    assertThat(page.locator("#list-status")).hasText("Showing 250 of 250");
    held.get(0).resume();
  }

  @Test
  void theErrorOfAListGoesWhenTheNextListIsLoaded() {
    writeTwoRuns();
    open();

    List<Route> checks = new ArrayList<>();
    boolean[] holdingChecks = {true};
    boolean[] listFailed = {false};
    page.route(
        url -> url.contains("/api/"),
        route -> {
          String url = route.request().url();
          if (holdingChecks[0] && url.endsWith("/api/status")) {
            checks.add(route);
          } else if (!listFailed[0] && url.contains("/api/messages?") && url.contains("q=")) {
            listFailed[0] = true;
            route.abort();
          } else {
            route.resume();
          }
        });
    page.locator("#search").fill("nothing like this");
    page.waitForCondition(() -> listFailed[0]);
    assertThat(page.locator("#error-banner")).isVisible();

    page.locator("#search").fill("second");
    assertThat(page.locator("#list mark").first()).hasText("second");
    assertThat(page.locator("#error-banner")).isHidden();
    holdingChecks[0] = false;
    checks.forEach(Route::resume);
    forgetFailedRequests();
  }

  private void waitForNextCheck() {
    page.waitForResponse(response -> response.url().endsWith("/api/status"), () -> {});
  }

  private RunFiles writeTwoRuns() {
    RunFiles first = output.run("run1", START.toString());
    first.testMail(SIGNUP, "sendsWelcome", "Welcome, first try", START.plusSeconds(1).toString());
    first.test(SIGNUP, "sendsWelcome", "failed", START.plusSeconds(2).toString());
    first.finish(START.plusSeconds(3).toString());
    RunFiles second = output.run("run2", START.plusSeconds(60).toString());
    second.testMail(
        SIGNUP, "sendsWelcome", "Welcome, second try", START.plusSeconds(61).toString());
    second.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(62).toString());
    second.finish(START.plusSeconds(63).toString());
    return second;
  }

  @Test
  void showsAMailThatArrivesAsNew() {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    open();
    assertThat(cards()).hasCount(1);

    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", START.plusSeconds(3).toString());
    run.test(SIGNUP, "sendsReminder", "passed", START.plusSeconds(4).toString());
    readRecordsAgain();

    Locator arrived = card("Friendly reminder");
    assertThat(arrived.locator(".new-marker")).hasText("New");
    assertThat(page.locator("#list-new")).hasText("1 new");
    assertThat(treeItem("sendsReminder()")).isVisible();
    assertThat(page.locator("#list .new-marker")).hasCount(1);

    arrived.click();
    assertThat(detailHeading()).hasText("Friendly reminder");
    assertThat(arrived.locator(".new-marker")).hasCount(0);
    assertThat(page.locator("#list-new")).isHidden();
  }

  @Test
  void theSelectedMailStaysWhenTheTestRunsAgain() {
    RunFiles first = output.run("run1", START.toString());
    first.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", START.plusSeconds(1).toString());
    first.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    first.finish(START.plusSeconds(3).toString());
    open();
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");

    RunFiles second = output.run("run2", START.plusSeconds(60).toString());
    second.testMail(SIGNUP, "sendsWelcome", "Welcome again", START.plusSeconds(61).toString());
    second.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(62).toString());
    second.finish(START.plusSeconds(63).toString());
    readRecordsAgain();

    assertThat(card("Welcome again")).isVisible();
    assertThat(card("Welcome aboard")).hasCount(0);
    assertThat(detailHeading()).hasText("Welcome aboard");
  }

  @Test
  void theListStaysAtOnePageWhenManyMailsArriveAfterTheSelectedOne() {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", START.plusSeconds(1).toString());
    run.test(SIGNUP, "sendsWelcome", "passed", START.plusSeconds(2).toString());
    open();
    card("Welcome aboard").click();
    assertThat(detailHeading()).hasText("Welcome aboard");

    for (int i = 1; i <= 300; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(10 + i).toString());
    }
    readRecordsAgain();

    assertThat(page.locator("#list-count")).hasText("301");
    assertThat(cards()).hasCount(200);
    assertThat(card("Welcome aboard")).hasCount(0);
    assertThat(detailHeading()).hasText("Welcome aboard");

    page.reload();
    assertThat(page.locator("#list-count")).hasText("301");
    assertThat(cards()).hasCount(200);
    assertThat(detailHeading()).hasText("Welcome aboard");
  }

  @Test
  void theOldestFirstListStaysAtOnePageWhileMailsKeepArriving() {
    RunFiles run = output.run("run1", START.toString());
    run.testMail(SIGNUP, "sendsMany", "Mail 1", START.plusSeconds(1).toString());
    open();
    page.locator("#list-order").click();
    assertThat(page.locator("#list-order")).hasText("Oldest first");
    assertThat(cards()).hasCount(1);

    for (int i = 2; i <= 150; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    readRecordsAgain();
    assertThat(cards()).hasCount(150);

    for (int i = 151; i <= 300; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    readRecordsAgain();

    assertThat(page.locator("#list-count")).hasText("300");
    assertThat(cards()).hasCount(200);
    assertThat(page.locator("#list .list-more")).containsText("Showing 200 of 300");
  }

  @Test
  void showingMoreMailsDoesNotMarkThemAsNew() {
    RunFiles run = output.run("run1", START.toString());
    for (int i = 1; i <= 205; i++) {
      run.testMail(SIGNUP, "sendsMany", "Mail " + i, START.plusSeconds(i).toString());
    }
    run.test(SIGNUP, "sendsMany", "passed", START.plusSeconds(300).toString());
    run.finish(START.plusSeconds(301).toString());
    open();
    assertThat(cards()).hasCount(200);

    page.locator("#list-order").click();
    assertThat(page.locator("#list-order")).hasText("Oldest first");
    assertThat(cards().first()).containsText("Mail 1");
    assertThat(page.locator("#list .list-more")).containsText("Showing 200 of 205");

    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Show 5 more")).click();
    assertThat(cards()).hasCount(205);
    assertThat(page.locator("#list .new-marker")).hasCount(0);
    assertThat(page.locator("#list-new")).isHidden();
    assertThat(card("Mail 201")).isFocused();
  }
}
