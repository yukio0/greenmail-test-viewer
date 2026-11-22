package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ViewerLinkTest {

  private static final String TEST = "[engine:junit-jupiter]/[class:a.B]/[method:c(int, int)]";
  private static final String ENCODED =
      "%5Bengine%3Ajunit-jupiter%5D%2F%5Bclass%3Aa.B%5D%2F%5Bmethod%3Ac%28int%2C+int%29%5D";

  @Test
  void pointsToTheViewerAtItsDefaultAddress() {
    assertThat(ViewerLink.forTest(Optional.empty(), TEST))
        .hasValue("http://127.0.0.1:3080/#test=" + ENCODED);
  }

  @Test
  void pointsToTheConfiguredViewer() {
    assertThat(ViewerLink.forTest(Optional.of(" https://viewer.example.test/mails// "), TEST))
        .hasValue("https://viewer.example.test/mails/#test=" + ENCODED);
  }

  @Test
  void isLeftOutWhenTurnedOff() {
    assertThat(ViewerLink.forTest(Optional.of("NONE"), TEST)).isEmpty();
    assertThat(ViewerLink.forTest(Optional.of(" "), TEST)).isEmpty();
  }
}
