package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Route;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteContentScreenTest extends ScreenTestBase {

  private static final byte[] PIXEL =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

  private final List<String> requests = new ArrayList<>();

  @BeforeEach
  void writeMail() {
    RecordFiles.RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsNewsletter", newsletter(), "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsNewsletter", "passed", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");
  }

  @Test
  void showsImagesAndStyleSheetsFromOtherServers() {
    openNewsletter();

    FrameLocator preview = page.frameLocator("#detail iframe.html-preview");
    assertThat(preview.locator("#logo")).hasJSProperty("naturalWidth", 1);
    assertThat(preview.locator("p.styled")).hasCSS("color", "rgb(1, 2, 3)");
    Assertions.assertThat(requests)
        .anyMatch(request -> request.startsWith("https://images.example.test/logo.png "))
        .anyMatch(request -> request.startsWith("https://images.example.test/banner.png "))
        .anyMatch(request -> request.startsWith("https://styles.example.test/mail.css "))
        .allMatch(request -> request.endsWith(" referer=null"));
  }

  @Test
  void showsOnlyImagesFromOtherServersWhenStyleSheetsAndFontsAreBlocked() {
    blockRemoteCssAndFonts = true;
    openNewsletter();

    FrameLocator preview = page.frameLocator("#detail iframe.html-preview");
    assertThat(preview.locator("#logo")).hasJSProperty("naturalWidth", 1);
    assertThat(preview.locator("p.styled")).not().hasCSS("color", "rgb(1, 2, 3)");
    Assertions.assertThat(requests)
        .anyMatch(request -> request.startsWith("https://images.example.test/logo.png "))
        .anyMatch(request -> request.startsWith("https://images.example.test/banner.png "))
        .noneMatch(request -> request.contains("styles.example.test"))
        .allMatch(request -> request.endsWith(" referer=null"));
    forgetErrors("styles.example.test");
  }

  private void openNewsletter() {
    open();
    page.route("https://*.example.test/**", this::answer);
    card("Newsletter").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
  }

  private void answer(Route route) {
    String url = route.request().url();
    requests.add(url + " referer=" + route.request().allHeaders().get("referer"));
    if (url.endsWith(".png")) {
      route.fulfill(new Route.FulfillOptions().setContentType("image/png").setBodyBytes(PIXEL));
    } else {
      route.fulfill(
          new Route.FulfillOptions()
              .setContentType("text/css")
              .setBody(".styled { color: rgb(1, 2, 3); }"));
    }
  }

  private static byte[] newsletter() {
    String mail =
        String.join(
            "\r\n",
            "From: news@example.com",
            "To: customer@example.com",
            "Subject: Newsletter",
            "MIME-Version: 1.0",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html><head><link rel=\"stylesheet\" href=\"https://styles.example.test/mail.css\""
                + " referrerpolicy=\"unsafe-url\"></head><body><p class=\"styled\">Styled text</p>"
                + "<img id=\"logo\" src=\"https://images.example.test/logo.png\" alt=\"Logo\">"
                + "<img id=\"banner\" src=\"https://images.example.test/banner.png\""
                + " referrerpolicy=\"unsafe-url\" alt=\"Banner\">"
                + "</body></html>",
            "");
    return mail.getBytes(StandardCharsets.UTF_8);
  }
}
