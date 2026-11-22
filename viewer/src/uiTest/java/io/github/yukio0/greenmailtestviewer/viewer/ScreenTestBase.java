package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

abstract class ScreenTestBase {

  static final String SIGNUP = "com.example.SignupTest";
  static final String INVOICE = "com.example.InvoiceTest";

  private static final String BROWSER =
      System.getProperty("greenmail.testviewer.browser", "chromium");

  private static final double TIMEOUT_MILLIS = 10_000;

  private static final Path FAILURES = Path.of("build", "reports", "uiTest", BROWSER);

  private static boolean failuresCleaned;
  private static Playwright playwright;
  private static Browser browser;

  @TempDir Path project;

  RecordFiles output;
  Page page;

  boolean blockRemoteCssAndFonts;

  private RecordStore store;
  private ViewerServer server;
  private BrowserContext context;
  private final List<String> errors = new CopyOnWriteArrayList<>();

  @RegisterExtension
  final AfterTestExecutionCallback keepFailedPage =
      context -> {
        if (context.getExecutionException().isPresent() && page != null) {
          keepPage(
              context.getRequiredTestClass().getSimpleName()
                  + "."
                  + context.getRequiredTestMethod().getName());
        }
      };

  @BeforeAll
  static void launchBrowser() throws IOException {
    if (!failuresCleaned) {
      deleteRecursively(FAILURES);
      failuresCleaned = true;
    }
    PlaywrightAssertions.setDefaultAssertionTimeout(TIMEOUT_MILLIS);
    playwright = Playwright.create();
    browser = browserType().launch();
  }

  private static BrowserType browserType() {
    switch (BROWSER) {
      case "chromium":
        return playwright.chromium();
      case "firefox":
        return playwright.firefox();
      case "webkit":
        return playwright.webkit();
      default:
        throw new IllegalArgumentException("Unknown browser: " + BROWSER);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(path);
      }
    }
  }

  @AfterAll
  static void closeBrowser() {
    if (playwright != null) {
      playwright.close();
    }
  }

  @BeforeEach
  void createOutputDirectory() {
    output = RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
  }

  @AfterEach
  void stopViewer() {
    try {
      if (page != null) {
        assertThat(page.locator("#error-banner")).isHidden();
      }
      Assertions.assertThat(errors).as("errors in the page").isEmpty();
    } finally {
      if (context != null) {
        context.close();
      }
      if (server != null) {
        server.close();
      }
      if (store != null) {
        store.close();
      }
    }
  }

  private void keepPage(String name) throws IOException {
    Files.createDirectories(FAILURES);
    page.screenshot(new Page.ScreenshotOptions().setPath(FAILURES.resolve(name + ".png")));
    Files.write(FAILURES.resolve(name + ".html"), page.content().getBytes(StandardCharsets.UTF_8));
    List<String> lines = new ArrayList<>();
    lines.add("URL: " + page.url());
    lines.addAll(errors);
    Files.write(FAILURES.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
  }

  void open() {
    open(1440, 900);
  }

  void open(int width, int height) {
    open(width, height, "");
  }

  void open(int width, int height, String fragment) {
    store = new RecordStore(List.of(project));
    store.refresh(true);
    List<String> options = new ArrayList<>(List.of("--port", "0", "--dir", project.toString()));
    if (blockRemoteCssAndFonts) {
      options.add("--block-remote-css-and-fonts");
    }
    try {
      server = ViewerServer.start(ViewerOptions.parse(options.toArray(new String[0])), store);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    context = browser.newContext(new Browser.NewContextOptions().setViewportSize(width, height));
    page = context.newPage();
    page.onPageError(errors::add);
    page.onConsoleMessage(message -> {
      if ("error".equals(message.type())) {
        errors.add(message.text());
      }
    });
    page.navigate(server.url().toString() + fragment);
    assertThat(page.locator("#tree [role=treeitem], #tree .empty-tree").first()).isVisible();
  }

  void forgetErrors(String containing) {
    errors.removeIf(error -> error.contains(containing));
  }

  void forgetFailedRequests() {
    forgetErrors("Failed to load resource");
    forgetErrors("Fetch API cannot load");
    forgetErrors("NetworkError");
  }

  void readRecordsAgain() {
    store.refresh(false);
  }

  void findRecordsAgain() {
    store.refresh(true);
  }

  RunFiles writeRun() {
    RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome aboard", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.testMail(SIGNUP, "sendsReminder", "Friendly reminder", "2026-11-01T10:00:03.000Z");
    run.test(SIGNUP, "sendsReminder", "passed", "2026-11-01T10:00:04.000Z");
    run.testMail(INVOICE, "sendsInvoice", invoiceMail(), "2026-11-01T10:00:05.000Z");
    run.test(INVOICE, "sendsInvoice", "passed", "2026-11-01T10:00:06.000Z");
    run.testMail(INVOICE, "sendsRefund", "Refund issued", "2026-11-01T10:00:07.000Z");
    run.test(INVOICE, "sendsRefund", "failed", "2026-11-01T10:00:08.000Z");
    run.outsideClassMail("Nightly report", "2026-11-01T10:00:09.000Z");
    return run.finish("2026-11-01T10:00:10.000Z");
  }

  private static byte[] invoiceMail() {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Invoice 42",
            "MIME-Version: 1.0",
            "Content-Type: multipart/alternative; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "Total: 42 EUR",
            "--part",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html><body><p>Total: <b>42 EUR</b></p>"
                + "<p><a href=\"https://example.com/invoices/42\">Open the invoice</a></p>"
                + "<p><a href=\"//example.com/help\">Help</a></p>"
                + "</body></html>",
            "--part--",
            "");
    return mail.getBytes(StandardCharsets.UTF_8);
  }

  Locator treeItem(String name) {
    return page.getByRole(
        AriaRole.TREEITEM,
        new Page.GetByRoleOptions().setName(Pattern.compile("^" + literal(name) + ",")));
  }

  private static String literal(String text) {
    return text.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\$0");
  }

  void select(String name) {
    treeItem(name).locator(":scope > .tree-row .tree-node").click();
  }

  Locator listTitle() {
    return page.locator("#list-title");
  }

  Locator cards() {
    return page.locator("#list .message-card");
  }

  Locator card(String subject) {
    return cards()
        .filter(
            new Locator.FilterOptions()
                .setHas(
                    page.locator(
                        ".message-subject", new Page.LocatorOptions().setHasText(subject))));
  }

  Locator detail() {
    return page.locator("#detail");
  }

  Locator detailHeading() {
    return page.locator("#detail h2");
  }

  Locator tab(String name) {
    return page.getByRole(
        AriaRole.TAB,
        new Page.GetByRoleOptions().setName(Pattern.compile("^" + literal(name) + "\\s*\\d*$")));
  }

  Locator tabPanel() {
    return page.locator("#detail [role=tabpanel]");
  }
}
