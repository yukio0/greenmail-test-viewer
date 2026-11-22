package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttachmentScreenTest extends ScreenTestBase {

  private static final String PIXEL =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

  @BeforeEach
  void writeMail() {
    RecordFiles.RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsInvoice", invoice(), "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsInvoice", "passed", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");
  }

  @Test
  void showsAndDownloadsAFileWithoutAName() throws Exception {
    open();
    card("Invoice").click();
    detail()
        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("3 attachments"))
        .click();

    Locator attachments = detail().locator(".attachment-item");
    assertThat(attachments.locator(".attachment-name"))
        .hasText(new String[] {"(unnamed attachment 1)", "terms.txt", "photo.png"});
    Download download =
        page.waitForDownload(() ->
            attachments
                .first()
                .getByRole(
                    AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Download").setExact(true))
                .click());
    org.assertj.core.api.Assertions.assertThat(download.suggestedFilename())
        .startsWith("attachment-1");
    org.assertj.core.api.Assertions.assertThat(
            Files.readString(download.path(), StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-1.4");
  }

  @Test
  void tellsToTryAgainWhenADownloadOrAPreviewCannotReachTheViewer() {
    open();
    card("Invoice").click();
    detail()
        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("3 attachments"))
        .click();
    Locator terms = detail().locator(".attachment-item").nth(1);
    assertThat(terms.locator(".attachment-name")).hasText("terms.txt");

    page.route("**/attachments/**", Route::abort);
    terms
        .getByRole(
            AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download").setExact(true))
        .click();
    terms.locator("summary").getByText("Preview").click();

    String tryAgain =
        "The viewer cannot be reached. It may have stopped. Try again once it is running.";
    assertThat(terms.locator(".action-status")).hasText(tryAgain);
    assertThat(terms.locator(".attachment-preview-note")).hasText(tryAgain);
    forgetFailedRequests();
  }

  @Test
  void showsSizesInTheUnitThatTheyRoundTo() {
    open();

    Object sizes =
        page.evaluate(
            "async () => {"
                + " const { formatBytes } = await import('/js/format.js');"
                + " return [1023, 1024, 1048524, 1048575, 1048576, 5 * 1024 * 1024 * 1024]"
                + ".map(formatBytes); }");

    Assertions.assertThat(sizes)
        .asList()
        .containsExactly("1023 B", "1.0 KB", "1023.9 KB", "1.0 MB", "1.0 MB", "5.0 GB");
  }

  @Test
  void loadsAnImageAgainThatCouldNotBeLoaded() {
    open();
    card("Invoice").click();
    detail()
        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("3 attachments"))
        .click();
    Locator photo = detail().locator(".attachment-item").nth(2);
    assertThat(photo.locator(".attachment-name")).hasText("photo.png");

    Pattern image = Pattern.compile("/attachments/3/inline$");
    boolean[] failed = {false};
    page.route(
        url -> image.matcher(url).find(),
        route -> {
          if (failed[0]) {
            route.resume();
          } else {
            failed[0] = true;
            route.abort();
          }
        });
    Locator preview = photo.locator("summary");
    preview.click();
    assertThat(photo.locator(".attachment-preview-note"))
        .hasText("The image could not be loaded. Close and open the preview to try again.");

    preview.click();
    preview.click();
    assertThat(photo.locator("img.attachment-preview-image")).hasJSProperty("naturalWidth", 1);
    forgetFailedRequests();
  }

  private static byte[] invoice() {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Invoice",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "See the invoice.",
            "--part",
            "Content-Type: application/pdf",
            "Content-Transfer-Encoding: base64",
            "",
            Base64.getEncoder().encodeToString("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)),
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Disposition: attachment; filename=\"terms.txt\"",
            "",
            "Terms",
            "--part",
            "Content-Type: image/png",
            "Content-Disposition: attachment; filename=\"photo.png\"",
            "Content-Transfer-Encoding: base64",
            "",
            PIXEL,
            "--part--",
            "");
    return mail.getBytes(StandardCharsets.UTF_8);
  }
}
