package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class HtmlMailSanitizerTest {

  private final HtmlMailSanitizer sanitizer = new HtmlMailSanitizer();

  @Test
  void removesScriptTags() {
    String sanitized = sanitizer.sanitize("<p>Hello</p><script>alert('bad')</script>");

    assertThat(sanitized).contains("<p>Hello</p>");
    assertThat(sanitized).doesNotContainIgnoringCase("script").doesNotContain("alert('bad')");
  }

  @Test
  void removesEventHandlerAttributes() {
    String sanitized =
        sanitizer.sanitize(
            "<div onclick=\"alert('bad')\">\n"
                + "  <img src=\"cid:logo\" onerror=\"alert('bad')\" alt=\"logo\">\n"
                + "  <svg onload=\"alert('bad')\"></svg>\n"
                + "</div>");

    assertThat(sanitized).contains("<img src=\"cid:logo\" alt=\"logo\">", "<svg></svg>");
    assertThat(sanitized)
        .doesNotContainIgnoringCase("onclick")
        .doesNotContainIgnoringCase("onerror")
        .doesNotContainIgnoringCase("onload");
  }

  @Test
  void removesDangerousLinkProtocols() {
    String sanitized =
        sanitizer.sanitize(
            "<a href=\"javascript:alert('bad')\">javascript</a>\n"
                + "<a href=\"data:text/html,<script>alert('bad')</script>\">data</a>\n"
                + "<a href=\"file:///etc/passwd\">file</a>");

    assertThat(sanitized).contains("<a>javascript</a>", "<a>data</a>", "<a>file</a>");
    assertThat(sanitized)
        .doesNotContainIgnoringCase("javascript:")
        .doesNotContainIgnoringCase("data:text/html")
        .doesNotContainIgnoringCase("file:");
  }

  @Test
  void keepsSafeLinkProtocols() {
    String sanitized =
        sanitizer.sanitize(
            "<a href=\"https://example.com/path\">https</a>\n"
                + "<a href=\"http://example.com/path\">http</a>\n"
                + "<a href=\"//example.com/path\">no scheme</a>\n"
                + "<a href=\"mailto:dev@example.com\">mail</a>");

    assertThat(sanitized)
        .contains(
            "href=\"https://example.com/path\"",
            "href=\"http://example.com/path\"",
            "href=\"//example.com/path\"",
            "href=\"mailto:dev@example.com\"");
  }

  @Test
  void opensLinksInANewTabAndPlacesInTheMailInTheFrame() {
    String sanitized =
        sanitizer.sanitize(
            "<a href=\"https://example.com/confirm\" target=\"_self\" rel=\"opener\">Confirm</a>"
                + "<a href=\"#top\">Top</a><a href=\" #notes\" target=\"_blank\">Notes</a>");

    assertThat(sanitized)
        .contains(
            "<a href=\"https://example.com/confirm\" target=\"_blank\""
                + " rel=\"noopener noreferrer\">Confirm</a>",
            "<a href=\"about:srcdoc#top\">Top</a>",
            "<a href=\"about:srcdoc#notes\">Notes</a>");
  }

  @Test
  void opensTheAreasOfAnImageMapInANewTab() {
    String sanitized =
        sanitizer.sanitize(
            "<img src=\"https://example.com/map.png\" usemap=\"#m\"><map name=\"m\">"
                + "<area shape=\"rect\" coords=\"0,0,10,10\" href=\"https://example.com/a\""
                + " alt=\"A\" target=\"_self\">"
                + "<area shape=\"rect\" coords=\"10,0,20,10\" href=\"#top\" alt=\"Top\">"
                + "<area shape=\"rect\" coords=\"20,0,30,10\" href=\"javascript:alert(1)\""
                + " alt=\"Bad\"></map>");

    assertThat(sanitized)
        .contains(
            "<area shape=\"rect\" coords=\"0,0,10,10\" href=\"https://example.com/a\" alt=\"A\""
                + " target=\"_blank\" rel=\"noopener noreferrer\">",
            "<area shape=\"rect\" coords=\"10,0,20,10\" href=\"about:srcdoc#top\" alt=\"Top\">",
            "<area shape=\"rect\" coords=\"20,0,30,10\" alt=\"Bad\">");
    assertThat(sanitized).contains("usemap=\"#m\"");
  }

  @Test
  void keepsTheBodyWithItsColorsBackgroundAndStyle() {
    assertThat(
            sanitizer.sanitize(
                "<html><head><style>body { color: red }</style>"
                    + "<link rel=\"stylesheet\" href=\"https://example.com/a.css\">"
                    + "<link rel=\"icon\" href=\"https://example.com/icon.png\"></head>"
                    + "<body bgcolor=\"#eeeeee\" background=\"cid:bg\" style=\"color: blue\">"
                    + "<style>p { margin: 0 }</style><p>Text</p></body></html>"))
        .isEqualTo(
            "<head><style>body { color: red }</style>"
                + "<link rel=\"stylesheet\" href=\"https://example.com/a.css\"></head>"
                + "<body bgcolor=\"#eeeeee\" background=\"cid:bg\" style=\"color: blue\">"
                + "<style>p { margin: 0 }</style><p>Text</p></body>");
    assertThat(sanitizer.sanitize("<html><body background=\"file:///bg.png\"></body></html>"))
        .isEmpty();
  }

  @Test
  void keepsTheClassDirectionAndLanguageOfTheBody() {
    String sanitized =
        sanitizer.sanitize(
            "<html dir=\"rtl\" lang=\"ar\"><head><style>.alert { color: red }</style></head>"
                + "<body class=\"alert\" id=\"top\" onload=\"alert(1)\"><p>Text</p></body></html>");

    Document shown = Jsoup.parse(sanitized);
    assertThat(shown.body().attributes().asList())
        .extracting(Attribute::getKey, Attribute::getValue)
        .containsExactlyInAnyOrder(
            tuple("class", "alert"), tuple("id", "top"), tuple("dir", "rtl"), tuple("lang", "ar"));
    assertThat(
            Jsoup.parse(
                    sanitizer.sanitize(
                        "<html dir=\"rtl\"><body dir=\"ltr\"><p>Text</p></body></html>"))
                .body()
                .attr("dir"))
        .isEqualTo("ltr");
  }

  @Test
  void keepsImagesOnTheWeb() {
    String sanitized =
        sanitizer.sanitize(
            "<img src=\"https://example.com/logo.png\" alt=\"https image\">"
                + "<img src=\"http://example.com/logo.png\" alt=\"http image\">"
                + "<img src=\"//example.com/logo.png\" alt=\"no scheme\">"
                + "<table background=\"https://example.com/bg.png\"><tr><td>x</td></tr></table>");

    assertThat(sanitized)
        .contains(
            "<img src=\"https://example.com/logo.png\" alt=\"https image\">",
            "<img src=\"http://example.com/logo.png\" alt=\"http image\">",
            "<img src=\"//example.com/logo.png\" alt=\"no scheme\">",
            "<table background=\"https://example.com/bg.png\">")
        .doesNotContain("<span");
  }

  @Test
  void removesTheReferrerPoliciesOfTheMail() {
    String sanitized =
        sanitizer.sanitize(
            "<link rel=\"stylesheet\" href=\"https://example.com/mail.css\""
                + " referrerpolicy=\"unsafe-url\">"
                + "<img src=\"https://example.com/logo.png\" ReferrerPolicy=\"unsafe-url\" alt=\"\">"
                + "<a href=\"https://example.com/\" referrerpolicy=\"origin\">Shop</a>");

    assertThat(sanitized)
        .contains(
            "<link rel=\"stylesheet\" href=\"https://example.com/mail.css\">",
            "<img src=\"https://example.com/logo.png\" alt=\"\">")
        .doesNotContainIgnoringCase("referrerpolicy");
  }

  @Test
  void showsAnImageThatIsNotLoadedAsAPlaceholderOfItsSize() {
    String sanitized =
        sanitizer.sanitize(
            "<img src=\"file:///home/dev/banner.png\" width=\"600\" height=\"200\">"
                + "<img src=\"ftp://example.com/logo.png\" width=\"50%\" alt=\"<b>Logo</b>\">");

    assertThat(sanitized)
        .contains("width:600px;height:200px;\">Image</span>", "&lt;b&gt;Logo&lt;/b&gt;</span>")
        .doesNotContain("50%")
        .doesNotContain("<img")
        .doesNotContain("file:")
        .doesNotContain("ftp:");
  }

  @Test
  void thePlaceholderHasTheGraysOfTheScreen() throws IOException {
    String styles;
    try (InputStream in = HtmlMailSanitizer.class.getResourceAsStream("/static/styles.css")) {
      styles = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sanitizer.sanitize("<img src=\"file:///home/dev/banner.png\">"))
        .contains(
            "border:1px dashed " + color(styles, "text-faint"),
            "color:" + color(styles, "text-muted"));
  }

  private static String color(String styles, String name) {
    Matcher matcher = Pattern.compile("--" + name + ": (#[0-9a-f]{6});").matcher(styles);
    assertThat(matcher.find()).as("--%s in styles.css", name).isTrue();
    return matcher.group(1);
  }

  @Test
  void removesScriptImageSources() {
    String sanitized =
        sanitizer.sanitize("<img src=\"javascript:alert('bad')\" alt=\"bad image\">");

    assertThat(sanitized).contains(">bad image</span>").doesNotContain("<img");
    assertThat(sanitized).doesNotContainIgnoringCase("javascript:");
  }

  @Test
  void keepsCidAndDataImageSources() {
    String sanitized =
        sanitizer.sanitize(
            "<img src=\"cid:logo-image\" width=\"120\" height=\"40\" alt=\"logo\" title=\"Logo\">\n"
                + "<img src=\"data:image/png;base64,QUJD\" alt=\"inline image\">");

    assertThat(sanitized)
        .contains(
            "src=\"cid:logo-image\"",
            "width=\"120\"",
            "height=\"40\"",
            "alt=\"logo\"",
            "title=\"Logo\"",
            "src=\"data:image/png;base64,QUJD\"");
  }

  @Test
  void keepsTheImagesOfTheMailAndOnTheWebInASrcset() {
    String sanitized =
        sanitizer.sanitize(
            "<img src=\"cid:base\" srcset=\"cid:hi 2x, file:///3x.png 3x,"
                + " data:image/png;base64,QSxC 4x, https://example.com/5x.png 5x\">");

    assertThat(sanitized)
        .contains(
            "srcset=\"cid:hi 2x, data:image/png;base64,QSxC 4x, https://example.com/5x.png 5x\"")
        .doesNotContain("file:");
  }

  @Test
  void keepsAnImageWhoseSrcsetHasAnImageAfterItsSrcIsRemoved() {
    String sanitized =
        sanitizer.sanitize("<img src=\"file:///logo.png\" srcset=\"cid:logo 2x\" alt=\"Logo\">");

    assertThat(sanitized).contains("<img").contains("srcset=\"cid:logo 2x\"");
    assertThat(sanitized).doesNotContain("file:").doesNotContain("<span");
  }

  @Test
  void keepsAPictureWithImagesOnTheWeb() {
    String picture =
        "<picture><source srcset=\"https://example.com/logo.webp\" type=\"image/webp\">"
            + "<img src=\"https://example.com/logo.png\" alt=\"Logo\"></picture>";

    assertThat(sanitizer.sanitize(picture)).isEqualTo(picture);
  }

  @Test
  void keepsTheImageOfAPictureWhoseSourceHasAnImage() {
    String sanitized =
        sanitizer.sanitize(
            "<picture><source srcset=\"cid:logo\" type=\"image/png\">"
                + "<img src=\"file:///logo.png\" alt=\"Logo\"></picture>");

    assertThat(sanitized)
        .isEqualTo(
            "<picture><source srcset=\"cid:logo\" type=\"image/png\"><img alt=\"Logo\"></picture>");
  }

  @Test
  void showsAPlaceholderForAPictureWhoseImagesCannotBeLoaded() {
    String sanitized =
        sanitizer.sanitize(
            "<picture><source srcset=\"file:///logo.webp\">"
                + "<img src=\"file:///logo.png\" alt=\"Logo\"></picture>");

    assertThat(sanitized).doesNotContain("file:").doesNotContain("<img").contains("Logo</span>");
  }

  @Test
  void removesIframeObjectEmbedAndFormControls() {
    String sanitized =
        sanitizer.sanitize(
            "<iframe src=\"https://example.com\"></iframe>\n"
                + "<object data=\"https://example.com/movie.swf\"></object>\n"
                + "<embed src=\"https://example.com/movie.swf\">\n"
                + "<form action=\"https://example.com/post\"><input name=\"secret\">"
                + "<button>Send</button></form>\n"
                + "<textarea>secret</textarea><select><option>A</option></select>\n"
                + "<p>safe text</p>");

    assertThat(sanitized).contains("<p>safe text</p>");
    for (String tag :
        new String[] {
          "<iframe", "<object", "<embed", "<form", "<input", "<button", "<textarea", "<select"
        }) {
      assertThat(sanitized).doesNotContainIgnoringCase(tag);
    }
  }

  @Test
  void keepsStylesAndTheAttributesOfMailLayouts() {
    String sanitized =
        sanitizer.sanitize(
            "<html><head><title>Newsletter</title>"
                + "<style type=\"text/css\">p { color: #333; } @media (max-width: 600px) {"
                + " .wide { width: 100%; } }</style></head>"
                + "<body bgcolor=\"#eeeeee\"><table bgcolor=\"#ffffff\" cellpadding=\"8\""
                + " cellspacing=\"0\" border=\"0\" align=\"center\"><tr>"
                + "<td valign=\"top\" class=\"wide\" style=\"color: blue; padding: 4px\">"
                + "<font color=\"red\" face=\"Arial\">Sale</font></td></tr></table></body></html>");

    assertThat(sanitized)
        .contains(
            "<style type=\"text/css\">p { color: #333; } @media (max-width: 600px) {"
                + " .wide { width: 100%; } }</style>",
            "<body bgcolor=\"#eeeeee\">",
            "class=\"wide\"",
            "bgcolor=\"#ffffff\"",
            "cellpadding=\"8\"",
            "cellspacing=\"0\"",
            "align=\"center\"",
            "valign=\"top\"",
            "style=\"color: blue; padding: 4px\"",
            "<font color=\"red\" face=\"Arial\">Sale</font>");
    assertThat(sanitized).doesNotContain("Newsletter");
  }

  @Test
  void keepsStylesAsTheyAreAsThePolicyOfTheScreenDecidesWhatTheyLoad() {
    String style =
        "@import url(https://example.com/a.css); body { background: url(https://example.com/1.png);"
            + " color: red; }";

    String sanitized =
        sanitizer.sanitize(
            "<style>"
                + style
                + "</style><div style=\"background: url(//example.com/2.png); color: green\">x</div>");

    assertThat(sanitized)
        .contains(
            "<style>" + style + "</style>",
            "style=\"background: url(//example.com/2.png); color: green\"");
  }

  @Test
  void movesTheStyleSheetsAndStylesOfTheHeadIntoTheBodyInTheirOrder() {
    String sanitized =
        sanitizer.sanitize(
            "<html><head><link rel=\"stylesheet\" href=\"https://example.com/a.css\">"
                + "<style>p { color: red; }</style>"
                + "<link rel=\"Alternate StyleSheet\" href=\"//example.com/b.css\" title=\"b\">"
                + "</head><body><p>Text</p></body></html>");

    assertThat(sanitized)
        .isEqualTo(
            "<link rel=\"stylesheet\" href=\"https://example.com/a.css\">"
                + "<style>p { color: red; }</style>"
                + "<link rel=\"Alternate StyleSheet\" href=\"//example.com/b.css\" title=\"b\">"
                + "<p>Text</p>");
  }

  @Test
  void keepsTagsAndAttributesThatRunNothing() {
    String sanitized =
        sanitizer.sanitize(
            "<center><div role=\"presentation\" data-id=\"7\" aria-hidden=\"true\" width=\"600\">"
                + "<picture><source srcset=\"cid:logo\"><img src=\"cid:logo\" alt=\"logo\">"
                + "</picture><svg width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>"
                + "<noscript>No scripts</noscript></div></center>");

    assertThat(sanitized)
        .contains(
            "<center>",
            "role=\"presentation\"",
            "data-id=\"7\"",
            "aria-hidden=\"true\"",
            "width=\"600\"",
            "<picture><source srcset=\"cid:logo\">",
            "<svg width=\"10\" height=\"10\">",
            "No scripts");
  }

  @Test
  void removesMetaRefreshBaseAndLinksOtherThanStyleSheetsOnTheWeb() {
    String sanitized =
        sanitizer.sanitize(
            "<p>Text</p><meta http-equiv=\"refresh\" content=\"0; url=https://example.com/\">"
                + "<base href=\"https://example.com/\">"
                + "<link rel=\"stylesheet\" href=\"https://example.com/a.css\">"
                + "<link rel=\"stylesheet\" href=\"file:///home/dev/a.css\">"
                + "<link rel=\"stylesheet\" href=\"javascript:alert(1)\">"
                + "<link rel=\"dns-prefetch\" href=\"//example.com\">"
                + "<link rel=\"preload\" href=\"https://example.com/a.js\" as=\"script\">"
                + "<a href=\"https://example.com/page\" ping=\"https://example.com/ping\">Page</a>");

    assertThat(sanitized)
        .isEqualTo(
            "<p>Text</p><link rel=\"stylesheet\" href=\"https://example.com/a.css\">"
                + "<a href=\"https://example.com/page\" target=\"_blank\""
                + " rel=\"noopener noreferrer\">Page</a>")
        .doesNotContainIgnoringCase("<meta")
        .doesNotContainIgnoringCase("<base");
  }

  @Test
  void findsScriptUrlsThatBrowsersWouldStillRun() {
    String sanitized =
        sanitizer.sanitize(
            "<a href=\" JaVa&#x09;Script:alert(1)\">a</a>"
                + "<svg><a xlink:href=\"javascript:alert(1)\"><text>b</text></a></svg>");

    assertThat(sanitized).doesNotContainIgnoringCase("script:");
  }

  @Test
  void theAttributesOfTheBodyCannotEndIt() {
    String sanitized =
        sanitizer.sanitize(
            "<body bgcolor=\"red} </style><script>alert(1)</script>\""
                + " style=\"color: blue} </style><img src=x onerror=alert(1)>\">"
                + "<p>Text</p></body>");

    Document shown = Jsoup.parse(sanitized);
    assertThat(shown.select("script, img")).isEmpty();
    assertThat(shown.body().attr("bgcolor")).isEqualTo("red} </style><script>alert(1)</script>");
    assertThat(shown.body().attr("style"))
        .isEqualTo("color: blue} </style><img src=x onerror=alert(1)>");
    assertThat(shown.body().select("p").text()).isEqualTo("Text");
  }

  @Test
  void keepsImagesOfTheMailInStyles() {
    String sanitized =
        sanitizer.sanitize(
            "<div style=\"background-image: url(data:image/png;base64,QUJD)\">Data</div>");

    assertThat(sanitized).contains("style=\"background-image: url(data:image/png;base64,QUJD)\"");
  }

  @Test
  void keepsBasicMailFormattingStructures() {
    String sanitized =
        sanitizer.sanitize(
            "<h1>Title</h1>\n"
                + "<p>Hello <strong>world</strong></p>\n"
                + "<ul><li>one</li><li>two</li></ul>\n"
                + "<table><tr><td>cell</td></tr></table>");

    assertThat(sanitized)
        .contains(
            "<h1>Title</h1>", "<strong>world</strong>", "<li>one</li>", "<table>", "<td>cell</td>");
  }
}
