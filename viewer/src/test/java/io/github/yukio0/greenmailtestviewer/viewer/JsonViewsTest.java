package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonViewsTest {

  private static final String START = "Hello Alice. ".repeat(20);

  @Test
  void showsTheStartOfTheBodyWithoutSearchTerms() {
    String body = START + "Your code is ZX-42.";

    assertThat(JsonViews.preview(body, List.of()))
        .isEqualTo(body.substring(0, JsonViews.PREVIEW_LENGTH));
    assertThat(JsonViews.preview(null, List.of("zx-42"))).isNull();
  }

  @Test
  void showsTheStartOfTheBodyWhenItHasATerm() {
    String body = START + "Your code is ZX-42.";

    assertThat(JsonViews.preview(body, List.of("alice", "zx-42")))
        .isEqualTo(body.substring(0, JsonViews.PREVIEW_LENGTH));
  }

  @Test
  void showsThePartAroundATermThatIsFoundAfterTheStart() {
    String body = START + "Your code is ZX-42. Thank you.";

    String preview = JsonViews.preview(body, List.of("zx-42"));

    int start = body.indexOf("ZX-42") - JsonViews.PREVIEW_CONTEXT;
    assertThat(preview).isEqualTo("\u2026" + body.substring(start));
  }

  @Test
  void showsTheFirstTermThatIsFound() {
    String body = START + "first ONE, then TWO.";

    assertThat(JsonViews.preview(body, List.of("two", "one")))
        .startsWith("\u2026")
        .contains("first ONE, then TWO.");
  }

  @Test
  void findsATermAsTheSearchFindsIt() {
    String body = "P\u0131n " + "Hello Bob. ".repeat(20) + "Your PIN is 42.";

    int start = body.indexOf("PIN") - JsonViews.PREVIEW_CONTEXT;
    assertThat(JsonViews.preview(body, List.of("pin"))).isEqualTo("\u2026" + body.substring(start));
  }

  @Test
  void findsATermAfterCharactersThatAreLongerInLowerCase() {
    String body = "\u0130zmir and \u0130stanbul. " + START + "Your code is ZX-42.";

    int start = body.indexOf("ZX-42") - JsonViews.PREVIEW_CONTEXT;
    assertThat(JsonViews.preview(body, List.of("zx-42")))
        .isEqualTo("\u2026" + body.substring(start));
  }

  @Test
  void doesNotCutACharacterInTwo() {
    String emoji = "\uD83D\uDE00";
    String body = emoji.repeat(100) + " end";

    assertThat(JsonViews.preview(body, List.of("end")))
        .startsWith("\u2026" + emoji)
        .endsWith("end");
    assertThat(JsonViews.preview("x" + emoji.repeat(100), List.of())).endsWith(emoji);
  }
}
