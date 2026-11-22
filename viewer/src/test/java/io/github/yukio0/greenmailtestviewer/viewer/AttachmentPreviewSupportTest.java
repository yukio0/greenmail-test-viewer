package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AttachmentPreviewSupportTest {

  @Test
  void detectsImagePreviewFromContentType() {
    assertThat(AttachmentPreviewSupport.previewKind("image/png", null))
        .isEqualTo(AttachmentPreviewSupport.KIND_IMAGE);
  }

  @Test
  void detectsImagePreviewFromFileExtension() {
    assertThat(AttachmentPreviewSupport.previewKind("application/octet-stream", "logo.png"))
        .isEqualTo(AttachmentPreviewSupport.KIND_IMAGE);
  }

  @Test
  void detectsTextPreviewFromContentType() {
    assertThat(AttachmentPreviewSupport.previewKind("application/json; charset=UTF-8", "data.bin"))
        .isEqualTo(AttachmentPreviewSupport.KIND_TEXT);
  }

  @Test
  void detectsTextPreviewFromFileExtension() {
    assertThat(AttachmentPreviewSupport.previewKind("application/octet-stream", "report.md"))
        .isEqualTo(AttachmentPreviewSupport.KIND_TEXT);
  }

  @Test
  void disablesLargeTextPreview() {
    long size = AttachmentPreviewSupport.TEXT_PREVIEW_MAX_BYTES + 1;

    assertThat(AttachmentPreviewSupport.isAvailable("text/plain; charset=UTF-8", "large.txt", size))
        .isFalse();
    assertThat(
            AttachmentPreviewSupport.unavailableReason(
                "text/plain; charset=UTF-8", "large.txt", size))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_TOO_LARGE);
  }

  @Test
  void allowsTextPreviewAtSizeLimit() {
    assertThat(
            AttachmentPreviewSupport.isAvailable(
                "text/plain; charset=UTF-8",
                "limit.txt",
                AttachmentPreviewSupport.TEXT_PREVIEW_MAX_BYTES))
        .isTrue();
  }

  @Test
  void marksUnsupportedAttachmentAsNotPreviewable() {
    assertThat(AttachmentPreviewSupport.previewKind("application/pdf", "report.pdf"))
        .isEqualTo(AttachmentPreviewSupport.KIND_NONE);
    assertThat(AttachmentPreviewSupport.unavailableReason("application/pdf", "report.pdf", 1024L))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED);
  }

  @Test
  void previewsOnlyTheImagesThatThePageMayShow() {
    assertThat(AttachmentPreviewSupport.isAvailable("image/png; name=logo.png", "logo.png", 10))
        .isTrue();
    assertThat(AttachmentPreviewSupport.unavailableReason("image/svg+xml", "drawing.svg", 10))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED);
    assertThat(
            AttachmentPreviewSupport.unavailableReason("application/octet-stream", "logo.png", 10))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED);
    assertThat(AttachmentPreviewSupport.isInlineImage("image/png; name=logo.png")).isTrue();
    assertThat(AttachmentPreviewSupport.isInlineImage("image/svg+xml")).isFalse();
  }

  @Test
  void doesNotPreviewEmptyImages() {
    assertThat(AttachmentPreviewSupport.isAvailable("image/png", "empty.png", 0)).isFalse();
    assertThat(AttachmentPreviewSupport.unavailableReason("image/png", "empty.png", 0))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_EMPTY);
    assertThat(AttachmentPreviewSupport.imageUnavailableReason("image/png", 1)).isNull();
    assertThat(AttachmentPreviewSupport.imageUnavailableReason("image/svg+xml", 0))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED);
  }

  @Test
  void tellsWhyAnAttachmentCannotBeShownAsText() {
    long limit = AttachmentPreviewSupport.TEXT_PREVIEW_MAX_BYTES;

    assertThat(AttachmentPreviewSupport.textUnavailableReason("text/plain", "a.txt", limit))
        .isNull();
    assertThat(AttachmentPreviewSupport.textUnavailableReason("text/plain", "a.txt", limit + 1))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_TOO_LARGE);
    assertThat(AttachmentPreviewSupport.textUnavailableReason("image/png", "a.png", 10))
        .isEqualTo(AttachmentPreviewSupport.UNAVAILABLE_UNSUPPORTED);
  }

  @Test
  void usesTheCharsetOfTheContentTypeOrUtf8() {
    assertThat(AttachmentPreviewSupport.previewCharset("text/plain; charset=Shift_JIS").name())
        .isEqualTo("Shift_JIS");
    assertThat(AttachmentPreviewSupport.previewCharset("text/plain; charset=unknown-charset"))
        .isEqualTo(StandardCharsets.UTF_8);
    assertThat(AttachmentPreviewSupport.previewCharset("text/plain"))
        .isEqualTo(StandardCharsets.UTF_8);
  }

  @Test
  void findsCharsetNamesThatOnlyMailUses() {
    byte[] eucJp = "見積書".getBytes(Charset.forName("EUC-JP"));

    Charset charset = AttachmentPreviewSupport.previewCharset("text/plain; charset=ja_jp.eucjp");

    assertThat(new String(eucJp, charset)).isEqualTo("見積書");
  }
}
