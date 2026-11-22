package io.github.yukio0.greenmailtestviewer.jupiter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/** The link to a test in the viewer, logged next to the failure of the test. */
final class ViewerLink {

  static final String CONFIGURATION_PARAMETER = "greenmail.testviewer.viewer-url";

  static final String DEFAULT_URL = "http://127.0.0.1:3080";

  static final String NONE = "none";

  private ViewerLink() {}

  static Optional<String> forTest(Optional<String> configured, String uniqueId) {
    String url = configured.map(String::trim).orElse(DEFAULT_URL);
    if (url.isEmpty() || url.toLowerCase(Locale.ROOT).equals(NONE)) {
      return Optional.empty();
    }
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return Optional.of(url + "/#test=" + URLEncoder.encode(uniqueId, StandardCharsets.UTF_8));
  }
}
