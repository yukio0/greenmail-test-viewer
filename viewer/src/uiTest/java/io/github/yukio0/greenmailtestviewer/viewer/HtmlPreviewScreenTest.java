package io.github.yukio0.greenmailtestviewer.viewer;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.BoundingBox;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HtmlPreviewScreenTest extends ScreenTestBase {

  private static final String PIXEL =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

  private static final Pattern EMBEDDED_IMAGE = Pattern.compile("^url\\(\"data:image/png;base64,");

  @BeforeEach
  void writeMail() {
    RecordFiles.RunFiles run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsNewsletter", newsletter(), "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsNewsletter", "passed", "2026-11-01T10:00:02.000Z");
    run.testMail(SIGNUP, "sendsLogos", logos(), "2026-11-01T10:00:02.100Z");
    run.test(SIGNUP, "sendsLogos", "passed", "2026-11-01T10:00:02.200Z");
    run.testMail(SIGNUP, "sendsGreeting", greeting(), "2026-11-01T10:00:02.300Z");
    run.test(SIGNUP, "sendsGreeting", "passed", "2026-11-01T10:00:02.400Z");
    run.testMail(SIGNUP, "sendsContents", contents(), "2026-11-01T10:00:02.500Z");
    run.test(SIGNUP, "sendsContents", "passed", "2026-11-01T10:00:02.600Z");
    run.finish("2026-11-01T10:00:03.000Z");
  }

  @Test
  void theAreasOfAnImageMapOpenInANewTab() {
    FrameLocator preview = openNewsletter();
    page.context()
        .route(
            "https://pages.example.test/**",
            route ->
                route.fulfill(
                    new Route.FulfillOptions().setContentType("text/html").setBody("<p>Sale</p>")));

    BoundingBox map = preview.locator("#map").boundingBox();
    Page sale =
        page.waitForPopup(() -> page.mouse().click(map.x + map.width / 2, map.y + map.height / 2));

    assertThat(sale).hasURL("https://pages.example.test/sale");
    assertThat(preview.locator("#map")).isVisible();
    sale.close();
    forgetErrors("Blocked script execution in 'about:srcdoc'");
  }

  @Test
  void showsTheBackgroundImagesOfTheMail() {
    FrameLocator preview = openNewsletter();

    assertThat(preview.locator("body")).hasCSS("background-image", EMBEDDED_IMAGE);
    assertThat(preview.locator("#attribute")).hasCSS("background-image", EMBEDDED_IMAGE);
    assertThat(preview.locator("#style")).hasCSS("background-image", EMBEDDED_IMAGE);
    assertThat(detail().locator(".cid-images-status")).hasText("Resolved inline images: 4");
  }

  @Test
  void showsTheBodyWithItsDirectionClassAndStyleAfterTheStylesOfTheHead() {
    open();
    card("Greeting").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    FrameLocator preview = page.frameLocator("#detail iframe.html-preview");

    assertThat(preview.locator("body")).hasCSS("direction", "rtl");
    assertThat(preview.locator("#greeting")).hasCSS("letter-spacing", "3px");
    assertThat(preview.locator("#greeting")).hasCSS("color", "rgb(0, 0, 255)");
    assertThat(preview.locator("#greeting")).hasCSS("font-style", "italic");
  }

  @Test
  void aLinkToAPlaceInTheMailMovesToItInThePreview() {
    open();
    card("Contents").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    FrameLocator preview = page.frameLocator("#detail iframe.html-preview");
    assertThat(preview.locator("#notes")).not().isInViewport();

    preview.locator("#to-notes").click();

    assertThat(preview.locator("#notes")).isInViewport();
    assertThat(preview.locator("#to-notes")).isAttached();
    forgetErrors("Blocked script execution in 'about:srcdoc");
  }

  @Test
  void tellsWhichImagesOfTheMailAreNotShown() {
    open();
    card("Logos").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    FrameLocator preview = page.frameLocator("#detail iframe.html-preview");

    assertThat(preview.locator("#logo"))
        .hasAttribute("src", Pattern.compile("^data:image/png;base64,"));
    assertThat(detail().locator(".cid-images-status")).hasClass(Pattern.compile("\\bwarning\\b"));
    assertThat(detail().locator(".cid-images-status > span"))
        .hasText(
            new String[] {
              "Resolved inline images: 1",
              "Inline images not shown: badge (image/svg+xml), spacer (empty)"
            });
    forgetErrors("cid:badge");
    forgetErrors("cid:spacer");
  }

  private FrameLocator openNewsletter() {
    open();
    card("Newsletter").click();
    assertThat(tab("HTML")).hasAttribute("aria-selected", "true");
    return page.frameLocator("#detail iframe.html-preview");
  }

  private static byte[] newsletter() {
    String mail =
        String.join(
            "\r\n",
            "From: news@example.com",
            "To: customer@example.com",
            "Subject: Newsletter",
            "MIME-Version: 1.0",
            "Content-Type: multipart/related; boundary=\"related\"",
            "",
            "--related",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html><body background=\"cid:bg\">"
                + "<img id=\"map\" usemap=\"#offers\" width=\"200\" height=\"100\""
                + " src=\"data:image/png;base64,"
                + PIXEL
                + "\" alt=\"Offers\"><map name=\"offers\">"
                + "<area shape=\"rect\" coords=\"0,0,200,100\""
                + " href=\"https://pages.example.test/sale\" alt=\"Sale\"></map>"
                + "<table id=\"attribute\" background=\"cid:bg\" width=\"200\">"
                + "<tr><td>Attribute</td></tr></table>"
                + "<table id=\"style\" background=\"cid:bg\""
                + " style=\"background-image: url('cid:bg')\" width=\"200\">"
                + "<tr><td>Style</td></tr></table>"
                + "</body></html>",
            "--related",
            "Content-Type: image/png",
            "Content-ID: <bg>",
            "Content-Disposition: inline; filename=\"bg.png\"",
            "Content-Transfer-Encoding: base64",
            "",
            PIXEL,
            "--related--",
            "");
    return mail.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] contents() {
    String mail =
        String.join(
            "\r\n",
            "From: news@example.com",
            "To: customer@example.com",
            "Subject: Contents",
            "MIME-Version: 1.0",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html><body><a id=\"to-notes\" href=\"#notes\">Notes</a>"
                + "<div style=\"height: 3000px\">Offers</div>"
                + "<p id=\"notes\">Notes</p></body></html>");
    return mail.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] greeting() {
    String mail =
        String.join(
            "\r\n",
            "From: news@example.com",
            "To: customer@example.com",
            "Subject: Greeting",
            "MIME-Version: 1.0",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<html dir=\"rtl\"><head><style>body { color: red }"
                + " .wide p { letter-spacing: 3px } p:first-child { font-style: italic }"
                + "</style></head>"
                + "<body class=\"wide\" style=\"color: blue\"><p id=\"greeting\">Hello</p>"
                + "</body></html>");
    return mail.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] logos() {
    String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>";
    String mail =
        String.join(
            "\r\n",
            "From: news@example.com",
            "To: customer@example.com",
            "Subject: Logos",
            "MIME-Version: 1.0",
            "Content-Type: multipart/related; boundary=\"related\"",
            "",
            "--related",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p><img id=\"logo\" src=\"cid:logo\" alt=\"Logo\">"
                + "<img id=\"badge\" src=\"cid:badge\" alt=\"Badge\">"
                + "<img id=\"spacer\" src=\"cid:spacer\" alt=\"\"></p>",
            "--related",
            "Content-Type: multipart/alternative; boundary=\"logo\"",
            "",
            "--logo",
            "Content-Type: image/svg+xml",
            "Content-ID: <logo>",
            "",
            svg,
            "--logo",
            "Content-Type: image/png",
            "Content-ID: <logo>",
            "Content-Transfer-Encoding: base64",
            "",
            PIXEL,
            "--logo--",
            "--related",
            "Content-Type: image/svg+xml",
            "Content-ID: <badge>",
            "",
            svg,
            "--related",
            "Content-Type: image/png",
            "Content-ID: <spacer>",
            "Content-Transfer-Encoding: base64",
            "",
            "",
            "--related--",
            "");
    return mail.getBytes(StandardCharsets.UTF_8);
  }
}
