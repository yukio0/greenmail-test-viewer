package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlPathsTest {

  @Test
  void encodesEverythingButUnreservedCharacters() {
    assertThat(UrlPaths.encodeSegment("a b/c+d~e.f_g-h")).isEqualTo("a%20b%2Fc%2Bd~e.f_g-h");
    assertThat(UrlPaths.encodeSegment("見積書.txt")).isEqualTo("%E8%A6%8B%E7%A9%8D%E6%9B%B8.txt");
  }

  @Test
  void decodesPercentEncodedUtf8AndKeepsPlus() {
    assertThat(UrlPaths.decodeSegment("a+b%20c")).isEqualTo("a+b c");
    assertThat(UrlPaths.decodeSegment("%E8%A6%8B%E7%A9%8D%E6%9B%B8.txt")).isEqualTo("見積書.txt");
  }

  @Test
  void keepsPercentSignsThatDoNotStartAnEscape() {
    assertThat(UrlPaths.decodeSegment("100%")).isEqualTo("100%");
    assertThat(UrlPaths.decodeSegment("%zz%4")).isEqualTo("%zz%4");
    assertThat(UrlPaths.decodeSegment("%４１")).isEqualTo("%４１");
  }

  @Test
  void decodesWhatItEncodes() {
    String value = "message id/1+見積書%.txt";

    assertThat(UrlPaths.decodeSegment(UrlPaths.encodeSegment(value))).isEqualTo(value);
  }
}
