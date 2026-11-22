package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import jakarta.mail.Part;
import java.util.List;
import org.junit.jupiter.api.Test;

class CidImageSourceRewriterTest {

  private final CidImageSourceRewriter rewriter = new CidImageSourceRewriter();

  @Test
  void rewritesCidImageSourceToInlineAttachmentUrl() {
    String rewritten =
        rewriter.rewrite(
            "<p>Hello</p><img src=\"cid:logo\" alt=\"Logo\">",
            "message-1",
            List.of(image("1", "logo")));

    assertThat(rewritten)
        .contains("src=\"/api/messages/message-1/attachments/1/inline\"", "alt=\"Logo\"")
        .doesNotContain("cid:logo");
  }

  @Test
  void showsTheFirstImageOfAContentIdThatAMailHasTwice() {
    String rewritten =
        rewriter.rewrite(
            "<img src=\"cid:logo\">", "message-1", List.of(image("1", "logo"), image("2", "LOGO")));

    assertThat(rewritten).contains("src=\"/api/messages/message-1/attachments/1/inline\"");
  }

  @Test
  void showsTheImageOfAContentIdThatIsNearestToTheHtmlBody() {
    List<AttachmentSummary> images =
        List.of(
            image("1", "logo", "message.1.2"),
            image("2", "logo", "message.2.2"),
            image("3", "logo", "message.2.3"));

    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:logo\">",
            "message.2.1",
            images,
            attachment -> "/inline/" + attachment.id());

    assertThat(result.html()).contains("src=\"/inline/2\"");
    assertThat(
            rewriter
                .rewriteWithReport(
                    "<img src=\"cid:logo\">", images, attachment -> "/inline/" + attachment.id())
                .html())
        .contains("src=\"/inline/1\"");
  }

  @Test
  void showsTheImageOfAContentIdThatTheViewerCanShow() {
    List<AttachmentSummary> inOneAlternative =
        List.of(svg("1", "logo", "message.2.1"), image("2", "logo", "message.2.2"));
    List<AttachmentSummary> inTwoAlternatives =
        List.of(svg("1", "logo", "message.2.2"), image("2", "logo", "message.1.2"));

    for (List<AttachmentSummary> images : List.of(inOneAlternative, inTwoAlternatives)) {
      CidImageSourceRewriter.Result result =
          rewriter.rewriteWithReport(
              "<img src=\"cid:logo\">",
              "message.2.1",
              images,
              attachment -> "/inline/" + attachment.id());

      assertThat(result.html()).contains("src=\"/inline/2\"");
      assertThat(result.resolvedCount()).isEqualTo(1);
      assertThat(result.notShownImages()).isEmpty();
    }
  }

  @Test
  void reportsTheImagesThatTheViewerDoesNotShow() {
    AttachmentSummary undecodable =
        new AttachmentSummary(
            "3",
            "broken.png",
            "image/png",
            4L,
            Part.INLINE,
            "broken",
            true,
            "Failed to decode attachment",
            "message.4");

    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:logo\"><img src=\"cid:photo\" srcset=\"cid:logo 2x\">"
                + "<div style=\"background: url(cid:LOGO)\">a</div><img src=\"cid:broken\">"
                + "<img src=\"cid:spacer\">",
            "message.1",
            List.of(
                svg("1", "<logo>", "message.2"),
                image("2", "photo", "message.3"),
                undecodable,
                empty("4", "spacer", "message.5")),
            attachment -> "/inline/" + attachment.id());

    assertThat(result.html())
        .contains(
            "<img src=\"cid:logo\">",
            "<img src=\"/inline/2\">",
            "style=\"background: url(cid:LOGO)\"",
            "<img src=\"cid:broken\">",
            "<img src=\"cid:spacer\">")
        .doesNotContain("srcset");
    assertThat(result.resolvedCount()).isEqualTo(1);
    assertThat(result.resolvedContentIds()).containsExactly("photo");
    assertThat(result.missingContentIds()).isEmpty();
    assertThat(result.notShownImages())
        .extracting(CidImageSourceRewriter.NotShownImage::contentId)
        .containsExactly("logo", "broken", "spacer");
    assertThat(result.notShownImages())
        .extracting(image -> image.attachment().id())
        .containsExactly("1", "3", "4");
    assertThat(result.notShownImages())
        .extracting(CidImageSourceRewriter.NotShownImage::reason)
        .containsExactly(
            AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED,
            CidImageSourceRewriter.NOT_SHOWN_UNDECODABLE,
            AttachmentPreviewSupport.UNAVAILABLE_EMPTY);
  }

  @Test
  void showsAnImageWithContentRatherThanAnEmptyOne() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:logo\">",
            "message.1",
            List.of(empty("1", "logo", "message.2"), image("2", "logo", "message.3")),
            attachment -> "/inline/" + attachment.id());

    assertThat(result.html()).contains("src=\"/inline/2\"");
    assertThat(result.resolvedCount()).isEqualTo(1);
    assertThat(result.notShownImages()).isEmpty();
  }

  @Test
  void rewritesCidImageSourceWithAngleBracketsInContentId() {
    String rewritten =
        rewriter.rewrite(
            "<img src=\"cid:logo\" alt=\"Logo\">", "message-1", List.of(image("1", "<logo>")));

    assertThat(rewritten).contains("src=\"/api/messages/message-1/attachments/1/inline\"");
  }

  @Test
  void rewritesCidImageSourceWithUrlEncodedContentId() {
    String rewritten =
        rewriter.rewrite(
            "<img src=\"cid:logo%40example.com\" alt=\"Logo\">",
            "message-1", List.of(image("1", "<logo@example.com>")));

    assertThat(rewritten).contains("src=\"/api/messages/message-1/attachments/1/inline\"");
  }

  @Test
  void rewritesTheCidImagesOfASrcsetToo() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:base\" srcset=\"cid:hi 2x, cid:missing 3x\">"
                + "<picture><source srcset=\"cid:hi\"><img src=\"cid:base\"></picture>",
            "message-1",
            List.of(image("1", "base"), image("2", "hi")));

    assertThat(result.html())
        .contains(
            "srcset=\"/api/messages/message-1/attachments/2/inline 2x\"",
            "<source srcset=\"/api/messages/message-1/attachments/2/inline\">")
        .doesNotContain("cid:");
    assertThat(result.missingContentIds()).containsExactly("missing");
    assertThat(result.resolvedCount()).isEqualTo(4);
  }

  @Test
  void keepsTheHeadAndTheBodyWithItsAttributes() {
    String rewritten =
        rewriter.rewrite(
            "<head><style>.wide { background: url(cid:bg) }</style></head>"
                + "<body dir=\"rtl\" class=\"wide\" background=\"cid:bg\"><p>Text</p></body>",
            "message-1",
            List.of(image("1", "bg")));

    assertThat(rewritten)
        .isEqualTo(
            "<head><style>.wide { background: url(\"/api/messages/message-1/attachments/1/inline\")"
                + " }</style></head>"
                + "<body dir=\"rtl\" class=\"wide\""
                + " background=\"/api/messages/message-1/attachments/1/inline\">"
                + "<p>Text</p></body>");
  }

  @Test
  void rewritesTheBackgroundsOfMailLayouts() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<table background=\"cid:bg\"><tr><td background=\"CID:missing\">x</td></tr></table>",
            "message-1",
            List.of(image("1", "bg")));

    assertThat(result.html())
        .contains(
            "<table background=\"/api/messages/message-1/attachments/1/inline\">",
            "<td background=\"CID:missing\">");
    assertThat(result.resolvedCount()).isEqualTo(1);
    assertThat(result.missingContentIds()).containsExactly("missing");
  }

  @Test
  void rewritesTheCidImagesOfStyles() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<style>body { background-image: url(\"cid:bg\"); }</style>"
                + "<div style=\"background: #fff url( 'cid:bg' ) no-repeat\">a</div>"
                + "<div style=\"background-image: URL(cid:BG), url(cid:missing)\">b</div>",
            "message-1",
            List.of(image("1", "bg")));

    String url = "/api/messages/message-1/attachments/1/inline";
    assertThat(result.html())
        .contains(
            "<style>body { background-image: url(\"" + url + "\"); }</style>",
            "style=\"background: #fff url(&quot;" + url + "&quot;) no-repeat\"",
            "style=\"background-image: url(&quot;" + url + "&quot;), url(cid:missing)\"");
    assertThat(result.resolvedCount()).isEqualTo(3);
    assertThat(result.resolvedContentIds()).containsExactly("bg");
    assertThat(result.missingContentIds()).containsExactly("missing");
  }

  @Test
  void keepsAPlusInTheContentId() {
    List<AttachmentSummary> attachments = List.of(image("1", "<logo+part@example.com>"));

    assertThat(
            rewriter.rewrite("<img src=\"cid:logo+part@example.com\">", "message-1", attachments))
        .contains("src=\"/api/messages/message-1/attachments/1/inline\"");
    assertThat(
            rewriter.rewrite(
                "<img src=\"cid:logo%2Bpart%40example.com\">", "message-1", attachments))
        .contains("src=\"/api/messages/message-1/attachments/1/inline\"");
  }

  @Test
  void usesTheGivenSourceForEachImage() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:logo\">",
            List.of(image("1", "logo")),
            attachment -> "data:image/png;base64,QUJD");

    assertThat(result.html()).contains("src=\"data:image/png;base64,QUJD\"");
    assertThat(result.resolvedCount()).isEqualTo(1);
  }

  @Test
  void keepsUnmatchedCidImageSourceUnchanged() {
    String rewritten =
        rewriter.rewrite(
            "<img src=\"cid:missing\" alt=\"Missing\">", "message-1", List.of(image("1", "logo")));

    assertThat(rewritten).contains("src=\"cid:missing\"");
  }

  @Test
  void encodesMessageIdAndAttachmentIdInGeneratedUrl() {
    String rewritten =
        rewriter.rewrite(
            "<img src=\"cid:logo\" alt=\"Logo\">",
            "message id/1",
            List.of(image("attachment id/1", "logo")));

    assertThat(rewritten)
        .contains("src=\"/api/messages/message%20id%2F1/attachments/attachment%20id%2F1/inline\"");
  }

  @Test
  void returnsOriginalHtmlWhenThereAreNoContentIds() {
    String html = "<p>Hello</p><img src=\"cid:logo\" alt=\"Logo\">";

    assertThat(rewriter.rewrite(html, "message-1", List.of())).isEqualTo(html);
  }

  @Test
  void reportsResolvedAndMissingCidImageSources() {
    CidImageSourceRewriter.Result result =
        rewriter.rewriteWithReport(
            "<img src=\"cid:logo\" alt=\"Logo\">\n"
                + "<img src=\"cid:missing\" alt=\"Missing\">\n"
                + "<img src=\"cid:missing\" alt=\"Duplicate missing\">",
            "message-1",
            List.of(image("1", "logo")));

    assertThat(result.html()).contains("src=\"/api/messages/message-1/attachments/1/inline\"");
    assertThat(result.resolvedCount()).isEqualTo(1);
    assertThat(result.resolvedContentIds()).containsExactly("logo");
    assertThat(result.missingContentIds()).containsExactly("missing");
  }

  private static AttachmentSummary image(String id, String contentId) {
    return new AttachmentSummary(
        id, "image.png", "image/png", 4L, Part.INLINE, contentId, true, null);
  }

  private static AttachmentSummary image(String id, String contentId, String partPath) {
    return new AttachmentSummary(
        id, "image.png", "image/png", 4L, Part.INLINE, contentId, true, null, partPath);
  }

  private static AttachmentSummary empty(String id, String contentId, String partPath) {
    return new AttachmentSummary(
        id, "image.png", "image/png", 0L, Part.INLINE, contentId, true, null, partPath);
  }

  private static AttachmentSummary svg(String id, String contentId, String partPath) {
    return new AttachmentSummary(
        id, "image.svg", "image/svg+xml", 4L, Part.INLINE, contentId, true, null, partPath);
  }
}
