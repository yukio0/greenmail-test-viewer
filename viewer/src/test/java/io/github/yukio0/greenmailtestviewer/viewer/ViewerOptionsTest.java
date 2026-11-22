package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewerOptionsTest {

  @TempDir Path temporary;

  @Test
  void usesTheDefaults() {
    ViewerOptions options = ViewerOptions.parse();

    assertThat(options.directories()).containsExactly(Path.of(""));
    assertThat(options.host()).isEqualTo("127.0.0.1");
    assertThat(options.port()).isEqualTo(3080);
    assertThat(options.allowedHosts()).isEmpty();
    assertThat(options.staticDirectory()).isNull();
    assertThat(options.open()).isFalse();
    assertThat(options.blockRemoteCssAndFonts()).isFalse();
    assertThat(options.help()).isFalse();
  }

  @Test
  void opensTheBrowserWhenAsked() {
    assertThat(ViewerOptions.parse("--open").open()).isTrue();
    assertThat(ViewerOptions.USAGE).contains("--open");
  }

  @Test
  void blocksRemoteStyleSheetsAndFontsWhenAsked() {
    assertThat(ViewerOptions.parse("--block-remote-css-and-fonts").blockRemoteCssAndFonts())
        .isTrue();
    assertThat(ViewerOptions.USAGE).contains("--block-remote-css-and-fonts");
  }

  @Test
  void acceptsSeveralDirectoriesAndBothForms() {
    ViewerOptions options =
        ViewerOptions.parse(
            "--dir",
            temporary.toString(),
            "--dir=" + temporary,
            "--host=0.0.0.0",
            "--port",
            "0",
            "--allowed-host",
            "build-server",
            "--allowed-host=build-server.example",
            "--static-dir",
            temporary.toString());

    assertThat(options.directories()).containsExactly(temporary, temporary);
    assertThat(options.host()).isEqualTo("0.0.0.0");
    assertThat(options.port()).isZero();
    assertThat(options.allowedHosts()).containsExactly("build-server", "build-server.example");
    assertThat(options.staticDirectory()).isEqualTo(temporary);
  }

  @Test
  void rejectsWrongValues() {
    assertThatThrownBy(() -> ViewerOptions.parse("--port", "http")).hasMessageContaining("--port");
    assertThatThrownBy(() -> ViewerOptions.parse("--port", "70000")).hasMessageContaining("--port");
    assertThatThrownBy(() -> ViewerOptions.parse("--dir")).hasMessageContaining("needs a value");
    assertThatThrownBy(() -> ViewerOptions.parse("--dir", temporary.resolve("missing").toString()))
        .hasMessageContaining("not a directory");
    assertThatThrownBy(() -> ViewerOptions.parse("--verbose"))
        .hasMessageContaining("Unknown option");
  }

  @Test
  void rejectsAValueForAnOptionThatTakesNone() {
    for (String arg :
        new String[] {
          "--open=false", "--block-remote-css-and-fonts=no", "--help=false", "--open="
        }) {
      assertThatThrownBy(() -> ViewerOptions.parse(arg))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("does not take a value")
          .hasMessageContaining(arg);
    }
  }

  @Test
  void rejectsAnAllowedHostWithAPortOrAScheme() {
    for (String value :
        new String[] {"myhost:3080", "http://myhost", "myhost/", "[::1]:3080", "[::1"}) {
      assertThatThrownBy(() -> ViewerOptions.parse("--allowed-host", value))
          .as(value)
          .hasMessageContaining("--allowed-host takes a host name without a scheme or port")
          .hasMessageContaining(value);
    }
    assertThatThrownBy(() -> ViewerOptions.parse("--allowed-host", " "))
        .hasMessageContaining("--allowed-host needs a host name");
    assertThat(
            ViewerOptions.parse("--allowed-host", "::1", "--allowed-host", "[::1]").allowedHosts())
        .containsExactly("::1", "[::1]");
    assertThat(ViewerOptions.parse("--allowed-host", "*").allowedHosts()).containsExactly("*");
  }

  @Test
  void recognizesHelp() {
    assertThat(ViewerOptions.parse("--help").help()).isTrue();
  }

  @Test
  void showsHelpEvenIfTheDirectoryIsMissing() {
    String missing = temporary.resolve("missing").toString();

    assertThat(ViewerOptions.parse("--dir", missing, "--help").help()).isTrue();
  }
}
