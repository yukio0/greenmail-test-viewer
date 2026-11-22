package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MailListQueryTest {

  @Test
  void splitsTheSearchAtAnyWhitespace() {
    assertThat(MailListQuery.searchTerms(" Alpha　beta\tGAMMA "))
        .containsExactly("alpha", "beta", "gamma");
    assertThat(MailListQuery.searchTerms(" 　 ")).isEmpty();
    assertThat(MailListQuery.searchTerms(null)).isEmpty();
    assertThat(MailListQuery.parse(Map.of("q", "a b", "limit", " 5 ")).terms())
        .containsExactly("a", "b");
  }

  @Test
  void rejectsParametersItCannotUse() {
    for (Map<String, String> wrong :
        List.of(
            Map.of("group", "unknown"),
            Map.of("order", "sideways"),
            Map.of("limit", "0"),
            Map.of("limit", "many"))) {
      assertThatThrownBy(() -> MailListQuery.parse(wrong))
          .as(wrong.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(MailListQuery.parse(Map.of("group", " ", "order", "", "limit", " ")).terms())
        .isEmpty();
  }
}
