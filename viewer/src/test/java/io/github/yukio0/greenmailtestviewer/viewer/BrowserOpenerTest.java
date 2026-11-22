package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserOpenerTest {

  private static final URI URL = URI.create("http://127.0.0.1:3080/");

  @Test
  void usesTheCommandOfTheOperatingSystem() {
    assertThat(BrowserOpener.command("Windows 11", URL))
        .containsExactly("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:3080/");
    assertThat(BrowserOpener.command("Mac OS X", URL))
        .containsExactly("open", "http://127.0.0.1:3080/");
    assertThat(BrowserOpener.command("Linux", URL))
        .containsExactly("xdg-open", "http://127.0.0.1:3080/");
  }

  @Test
  void opensWhenTheCommandWorksOrStillRuns() {
    List<List<String>> commands = new ArrayList<>();

    assertThat(BrowserOpener.open(URL, "Linux", command -> record(commands, command, 0))).isTrue();
    assertThat(BrowserOpener.open(URL, "Linux", command -> record(commands, command, null)))
        .isTrue();
    assertThat(commands).hasSize(2);
  }

  @Test
  void tellsToOpenTheUrlByHandWhenNoBrowserCanBeOpened() {
    assertThat(BrowserOpener.open(URL, "Linux", command -> 3)).isFalse();
    assertThat(
            BrowserOpener.open(
                URL,
                "Linux",
                command -> {
                  throw new IOException("Cannot run program \"xdg-open\"");
                }))
        .isFalse();
  }

  private static Integer record(List<List<String>> commands, List<String> command, Integer exit) {
    commands.add(command);
    return exit;
  }
}
