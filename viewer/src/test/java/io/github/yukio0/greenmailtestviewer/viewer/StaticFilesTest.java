package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class StaticFilesTest {

  private static final Pattern IMPORT = Pattern.compile("from \"(\\./[^\"]+)\"");
  // Files next to the page. The link to "/" is the page itself.
  private static final Pattern INDEX_LINK =
      Pattern.compile("(?:src|href)=\"(?!#|/\"|[a-z]+:)([^\"]+)\"");

  @Test
  void containsOnlyAsciiSoThatTheScreenIsInEnglish() throws Exception {
    for (Path file : files()) {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      for (int i = 0; i < content.length(); i++) {
        assertThat((int) content.charAt(i))
            .as("character %d of %s", i, file.getFileName())
            .isLessThan(0x80);
      }
    }
  }

  @Test
  void neverParsesStringsAsHtml() throws Exception {
    for (Path file : javaScriptFiles()) {
      String content = Files.readString(file, StandardCharsets.UTF_8);
      assertThat(content)
          .as(file.getFileName().toString())
          .doesNotContain(".innerHTML")
          .doesNotContain(".outerHTML")
          .doesNotContain("insertAdjacentHTML")
          .doesNotContain("document.write")
          .doesNotContain("eval(")
          .doesNotContain("new Function");
    }
  }

  @Test
  void importsOnlyFilesThatExist() throws Exception {
    for (Path file : javaScriptFiles()) {
      Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (matcher.find()) {
        assertThat(file.resolveSibling(matcher.group(1)))
            .as("import in %s", file.getFileName())
            .isRegularFile();
      }
    }
    String index = Files.readString(directory().resolve("index.html"), StandardCharsets.UTF_8);
    assertThat(index).contains("src=\"js/main.js\"", "href=\"styles.css\"", "href=\"favicon.svg\"");
    Matcher link = INDEX_LINK.matcher(index);
    while (link.find()) {
      assertThat(directory().resolve(link.group(1))).as("link in index.html").isRegularFile();
    }
  }

  @Test
  void theIconIsAnSvgImage() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Element root =
        factory
            .newDocumentBuilder()
            .parse(directory().resolve("favicon.svg").toFile())
            .getDocumentElement();

    assertThat(root.getNamespaceURI()).isEqualTo("http://www.w3.org/2000/svg");
    assertThat(root.getLocalName()).isEqualTo("svg");
    assertThat(root.getAttribute("viewBox")).isEqualTo("0 0 32 32");
  }

  private static List<Path> javaScriptFiles() throws Exception {
    return files().stream()
        .filter(file -> file.toString().endsWith(".js"))
        .collect(Collectors.toList());
  }

  private static List<Path> files() throws Exception {
    List<Path> files = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(directory())) {
      paths.filter(Files::isRegularFile).forEach(files::add);
    }
    assertThat(files).isNotEmpty();
    return files;
  }

  private static Path directory() throws URISyntaxException, IOException {
    return Path.of(StaticFilesTest.class.getResource("/static/index.html").toURI()).getParent();
  }
}
