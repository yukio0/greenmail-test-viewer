package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticFileHandlerTest {

  @Test
  void letsThePreviewLoadImagesStyleSheetsAndFontsFromOtherServers() {
    Map<String, String> directives = directives(StaticFileHandler.contentSecurityPolicy(false));

    assertThat(directives)
        .containsEntry("default-src", "'self'")
        .containsEntry("img-src", "* data:")
        .containsEntry("style-src", "* 'unsafe-inline'")
        .containsEntry("font-src", "* data:")
        .containsEntry("object-src", "'none'")
        .containsEntry("base-uri", "'none'")
        .containsEntry("form-action", "'none'");
    assertThat(directives)
        .doesNotContainKeys("script-src", "frame-src", "media-src", "connect-src");
  }

  @Test
  void blocksStyleSheetsAndFontsFromOtherServersWhenAsked() {
    Map<String, String> directives = directives(StaticFileHandler.contentSecurityPolicy(true));

    assertThat(directives)
        .containsEntry("img-src", "* data:")
        .containsEntry("style-src", "'self' 'unsafe-inline'")
        .containsEntry("font-src", "'self' data:");
  }

  private static Map<String, String> directives(String policy) {
    Map<String, String> directives = new LinkedHashMap<>();
    for (String directive : policy.split(";")) {
      String[] parts = directive.trim().split(" ", 2);
      directives.put(parts[0], parts.length > 1 ? parts[1] : "");
    }
    return directives;
  }
}
