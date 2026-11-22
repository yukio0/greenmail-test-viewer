package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShadowJarTest {

  private static final Pattern RUNNING_AT = Pattern.compile("running at (http://\\S+?)/?$");
  private static final Pattern LICENSE_FILE = Pattern.compile("(LICENSE|NOTICE)[^/]*$");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path project;

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void startsWithJavaJarAndShowsTheMails() throws Exception {
    String jar = System.getProperty("greenmail.testviewer.shadowJar");
    assumeTrue(jar != null, "the path of the jar is not set");
    RecordFiles.RunFiles run =
        RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
            .run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(
        "com.example.SignupTest",
        "sendsWelcome",
        htmlMail().getBytes(StandardCharsets.UTF_8),
        "2026-11-01T10:00:01.000Z");
    run.test("com.example.SignupTest", "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.finish("2026-11-01T10:00:03.000Z");

    Process viewer =
        new ProcessBuilder(java(), "-jar", jar, "--port", "0", "--dir", project.toString())
            .redirectErrorStream(true)
            .start();
    try {
      String url = waitForUrl(viewer);

      JsonNode messages = json(url + "/api/messages").path("messages");
      assertThat(messages).hasSize(1);
      JsonNode detail = json(url + "/api/messages/" + messages.get(0).path("id").asText());
      assertThat(detail.path("subject").asText()).isEqualTo("Welcome ようこそ");
      assertThat(detail.path("textBody").asText()).isEqualTo("Hello");
      assertThat(detail.path("htmlBody").asText())
          .contains("<b>html</b>")
          .doesNotContain("<script");
      assertThat(get(url + "/").body()).contains("GreenMail Test Viewer");
      assertThat(get(url + "/js/main.js").statusCode()).isEqualTo(200);
      assertThat(get(url + "/favicon.svg").statusCode()).isEqualTo(200);
      assertThat(get(url + "/js").statusCode()).isEqualTo(404);
    } finally {
      viewer.destroy();
      viewer.waitFor(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void keepsTheLicenseFilesOfEachLibrary() throws IOException {
    String jar = System.getProperty("greenmail.testviewer.shadowJar");
    String libraries = System.getProperty("greenmail.testviewer.libraries");
    assumeTrue(jar != null && libraries != null, "the jar or its libraries are not set");
    try (ZipFile zip = new ZipFile(jar)) {
      List<String> names = zip.stream().map(ZipEntry::getName).collect(Collectors.toList());

      byte[] license = zip.getInputStream(zip.getEntry("META-INF/LICENSE")).readAllBytes();
      assertThat(new String(license, StandardCharsets.UTF_8))
          .contains("Apache License")
          .contains("Version 2.0");
      // Any other in META-INF could be one library's file that overwrote another's.
      assertThat(names)
          .filteredOn(name -> name.startsWith("META-INF/") && LICENSE_FILE.matcher(name).find())
          .allMatch(
              name -> name.equals("META-INF/LICENSE") || name.startsWith("META-INF/licenses/"));
      for (String library : libraries.split(",")) {
        assertThat(names)
            .as(library)
            .anyMatch(name -> name.startsWith("META-INF/licenses/" + library + "/LICENSE"));
      }
      assertThat(names)
          .contains(
              "META-INF/licenses/jakarta.activation/jakarta.activation-api/NOTICE.md",
              "META-INF/licenses/org.eclipse.angus/angus-activation/NOTICE.md",
              "META-INF/licenses/org.eclipse.angus/jakarta.mail/NOTICE.md",
              "META-INF/licenses/com.fasterxml.jackson.core/jackson-databind/NOTICE",
              "META-INF/licenses/org.jsoup/jsoup/LICENSE");
    }
  }

  private static String htmlMail() {
    return "From: shop@example.com\r\n"
        + "To: customer@example.com\r\n"
        + "Subject: Welcome ようこそ\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: multipart/alternative; boundary=\"part\"\r\n"
        + "\r\n"
        + "--part\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: utf-8\r\n"
        + "\r\n"
        + "Hello\r\n"
        + "--part\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n"
        + "\r\n"
        + "<p>Hello <b>html</b></p><script>alert(1)</script>\r\n"
        + "--part--\r\n";
  }

  private static String java() {
    return ProcessHandle.current()
        .info()
        .command()
        .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
  }

  private static String waitForUrl(Process process) throws InterruptedException {
    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(() -> {
          try (BufferedReader output =
              new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
              lines.add(line);
            }
          } catch (IOException e) {
          }
        });
    reader.setDaemon(true);
    reader.start();
    StringBuilder output = new StringBuilder();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      String line = lines.poll(100, TimeUnit.MILLISECONDS);
      if (line == null) {
        if (!process.isAlive() && lines.isEmpty()) {
          break;
        }
        continue;
      }
      output.append(line).append('\n');
      Matcher matcher = RUNNING_AT.matcher(line);
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    throw new AssertionError("The viewer did not start:\n" + output);
  }

  private JsonNode json(String url) throws Exception {
    HttpResponse<String> response = get(url);
    assertThat(response.statusCode()).as(url).isEqualTo(200);
    return MAPPER.readTree(response.body());
  }

  private HttpResponse<String> get(String url) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}
