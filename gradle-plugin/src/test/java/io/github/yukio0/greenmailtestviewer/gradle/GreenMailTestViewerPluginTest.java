package io.github.yukio0.greenmailtestviewer.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GreenMailTestViewerPluginTest {

  private static final String REPOSITORY = System.getProperty("greenmail.testviewer.repository");
  private static final String OLDEST_GRADLE =
      System.getProperty("greenmail.testviewer.oldestGradle");

  private static final String CURRENT = "current";

  @TempDir Path project;

  @BeforeEach
  void writeSettings() throws IOException {
    write(
        "settings.gradle",
        "dependencyResolutionManagement {",
        "  repositories {",
        "    maven { url = uri('" + slashes(REPOSITORY) + "') }",
        "    mavenCentral()",
        "  }",
        "}",
        "rootProject.name = 'shop'");
  }

  static List<String> gradleVersions() {
    return Arrays.asList(OLDEST_GRADLE, CURRENT);
  }

  @ParameterizedTest(name = "Gradle {0}")
  @MethodSource("gradleVersions")
  void startsTheViewerOfTheVersionOfThePlugin(String gradleVersion) throws IOException {
    write("build.gradle", "plugins {", "  id 'io.github.yukio0.greenmail-test-viewer'", "}");

    BuildResult result =
        runner(gradleVersion, "greenmailTestViewer", "--args=--help", "--configuration-cache")
            .build();
    assertThat(result.getOutput())
        .contains("Usage: java -jar greenmail-test-viewer.jar")
        .contains("--dir <directory>");

    BuildResult again =
        runner(gradleVersion, "greenmailTestViewer", "--args=--help", "--configuration-cache")
            .build();
    assertThat(again.getOutput()).contains("Reusing configuration cache");
  }

  @Test
  void passesTheSettingsOfTheBuildScriptToTheViewer() throws IOException {
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "greenmailTestViewer {",
        "  port = 70000",
        "}");

    BuildResult result = runner(null, "greenmailTestViewer").buildAndFail();

    assertThat(result.getOutput()).contains("--port must be an integer from 0 to 65535: 70000");
  }

  @ParameterizedTest(name = "Gradle {0}")
  @MethodSource("gradleVersions")
  void readsTheSettingsOfAKotlinBuildScript(String gradleVersion) throws IOException {
    // Kotlin can assign to a property from Gradle 8.2, as the README says. Before, it calls set.
    boolean assigns = gradleVersion.equals(CURRENT);
    write(
        "build.gradle.kts",
        "plugins {",
        "  id(\"io.github.yukio0.greenmail-test-viewer\")",
        "}",
        "greenmailTestViewer {",
        assigns ? "  port = 70000" : "  port.set(70000)",
        assigns ? "  open = false" : "  open.set(false)",
        "}");

    BuildResult result = runner(gradleVersion, "greenmailTestViewer").buildAndFail();

    assertThat(result.getOutput()).contains("--port must be an integer from 0 to 65535: 70000");
  }

  @ParameterizedTest(name = "Gradle {0}")
  @MethodSource("gradleVersions")
  void theOptionsOfTheTaskTakePrecedence(String gradleVersion) throws IOException {
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "greenmailTestViewer {",
        "  port = 9000",
        "}");

    BuildResult port =
        runner(gradleVersion, "greenmailTestViewer", "--port=70001", "--no-open").buildAndFail();
    assertThat(port.getOutput()).contains("--port must be an integer from 0 to 65535: 70001");

    Files.createDirectories(project.resolve("ci-mails"));
    BuildResult dir =
        runner(gradleVersion, "greenmailTestViewer", "--dir=ci-mails", "--dir=old-mails")
            .buildAndFail();
    assertThat(dir.getOutput()).containsPattern("--dir is not a directory: .*[\\\\/]old-mails");
  }

  @ParameterizedTest(name = "Gradle {0}")
  @MethodSource("gradleVersions")
  void theOptionsSetWhetherTheBrowserOpens(String gradleVersion) throws IOException {
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "def viewerOpen = tasks.named('greenmailTestViewer').flatMap { it.open }",
        "tasks.named('greenmailTestViewer') { enabled = false }",
        "tasks.register('showOpen') {",
        "  doLast { println \"open=${viewerOpen.get()}\" }",
        "}");

    BuildResult byDefault = runner(gradleVersion, "greenmailTestViewer", "showOpen").build();
    assertThat(byDefault.getOutput()).contains("open=true");
    BuildResult noOpen =
        runner(gradleVersion, "greenmailTestViewer", "--no-open", "showOpen").build();
    assertThat(noOpen.getOutput()).contains("open=false");
    BuildResult help = runner(gradleVersion, "help", "--task", "greenmailTestViewer").build();
    assertThat(help.getOutput()).contains("--no-open");
    for (BuildResult result : Arrays.asList(byDefault, noOpen, help)) {
      assertThat(result.getOutput()).doesNotContain("was disabled for clashing");
    }
    if (!gradleVersion.equals(CURRENT)) {
      return;
    }

    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "greenmailTestViewer {",
        "  open = false",
        "}",
        "def viewerOpen = tasks.named('greenmailTestViewer').flatMap { it.open }",
        "tasks.named('greenmailTestViewer') { enabled = false }",
        "tasks.register('showOpen') {",
        "  doLast { println \"open=${viewerOpen.get()}\" }",
        "}");
    BuildResult opens = runner(gradleVersion, "greenmailTestViewer", "--open", "showOpen").build();
    assertThat(opens.getOutput()).contains("open=true").doesNotContain("was disabled for clashing");
  }

  @Test
  void aTaskOfTheTypeThatTheBuildRegistersStartsTheViewerToo() throws IOException {
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "tasks.register('ciMails', io.github.yukio0.greenmailtestviewer.gradle.GreenMailTestViewerTask) {",
        "  classpath = configurations.greenmailTestViewer",
        "  args '--help'",
        "}");

    BuildResult result = runner(null, "ciMails").build();

    assertThat(result.getOutput()).contains("Usage: java -jar greenmail-test-viewer.jar");
  }

  @Test
  void startsAnotherVersionOfTheViewer() throws IOException {
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "greenmailTestViewer {",
        "  viewerVersion = '0.0.1'",
        "}");

    BuildResult result = runner(null, "greenmailTestViewer").buildAndFail();

    assertThat(result.getOutput()).contains("io.github.yukio0:greenmail-test-viewer:0.0.1");
  }

  @Test
  void tellsWhichDependencyNeedsARepository() throws IOException {
    write("settings.gradle", "rootProject.name = 'shop'");
    write("build.gradle", "plugins {", "  id 'io.github.yukio0.greenmail-test-viewer'", "}");

    BuildResult result = runner(null, "greenmailTestViewer").buildAndFail();

    assertThat(result.getOutput())
        .contains(
            "io.github.yukio0:greenmail-test-viewer:" + GreenMailTestViewerPlugin.pluginVersion())
        .contains("no repositories are defined");
  }

  @Test
  @Timeout(value = 3, unit = TimeUnit.MINUTES)
  void showsTheRecordsOfTheModulesOfTheProject() throws Exception {
    int port = freePort();
    write(
        "build.gradle",
        "plugins {",
        "  id 'io.github.yukio0.greenmail-test-viewer'",
        "}",
        "greenmailTestViewer {",
        "  port = " + port,
        "  open = false",
        "}",
        "tasks.named('greenmailTestViewer') {",
        "  timeout = java.time.Duration.ofSeconds(20)",
        "}");
    writeRecords(project.resolve("app/build/greenmail-test-viewer"));

    CompletableFuture<BuildResult> build =
        CompletableFuture.supplyAsync(() -> runner(null, "greenmailTestViewer").buildAndFail());
    String messages = waitForAnswer("http://127.0.0.1:" + port + "/api/messages", build);

    assertThat(messages).contains("Welcome aboard");
    assertThat(build.get(2, TimeUnit.MINUTES).getOutput()).contains("Timeout has been exceeded");
  }

  private static void writeRecords(Path output) throws IOException {
    Files.createDirectories(output);
    Files.writeString(output.resolve("greenmail-test-viewer.json"), "{\"formatVersion\":1}");
    Path run = output.resolve("runs/20261101T100000Z-1234-00000001");
    Files.createDirectories(run.resolve("tests"));
    Files.createDirectories(run.resolve("messages"));
    Files.writeString(
        run.resolve("run.json"),
        "{\"formatVersion\":1,\"startedAt\":\"2026-11-01T10:00:00.000Z\",\"pid\":1234,"
            + "\"finishedAt\":\"2026-11-01T10:00:03.000Z\"}");
    String test = "[engine:junit-jupiter]/[class:com.example.SignupTest]/[method:sendsWelcome()]";
    Files.writeString(
        run.resolve("tests/000001.json"),
        "{\"formatVersion\":1,\"kind\":\"method\",\"testClass\":\"com.example.SignupTest\","
            + "\"testMethod\":\"sendsWelcome\",\"displayName\":\"sendsWelcome()\",\"uniqueId\":\""
            + test
            + "\",\"finishedAt\":\"2026-11-01T10:00:02.000Z\",\"result\":\"passed\"}");
    Files.writeString(
        run.resolve("messages/000001.json"),
        "{\"formatVersion\":1,\"testClass\":\"com.example.SignupTest\",\"testMethod\":\"sendsWelcome\","
            + "\"displayName\":\"sendsWelcome()\",\"uniqueId\":\""
            + test
            + "\",\"phase\":\"test\",\"capturedAt\":\"2026-11-01T10:00:01.000Z\","
            + "\"deliveredTo\":[\"alice@example.com\"]}");
    Files.writeString(
        run.resolve("messages/000001.eml"),
        "From: shop@example.com\r\nTo: alice@example.com\r\nSubject: Welcome aboard\r\n"
            + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n");
  }

  private static String waitForAnswer(String url, CompletableFuture<BuildResult> build)
      throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build();
    while (true) {
      if (build.isDone()) {
        throw new AssertionError(
            "The build ended before the viewer answered:\n" + build.get().getOutput());
      }
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return response.body();
        }
      } catch (ConnectException e) {
      }
      Thread.sleep(500);
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private GradleRunner runner(String gradleVersion, String... arguments) {
    List<String> all = new ArrayList<>(Arrays.asList(arguments));
    all.add("--stacktrace");
    all.add("--warning-mode=fail");
    GradleRunner runner =
        GradleRunner.create()
            .withProjectDir(project.toFile())
            .withPluginClasspath()
            .withArguments(all)
            .forwardOutput();
    return gradleVersion == null || gradleVersion.equals(CURRENT)
        ? runner
        : runner.withGradleVersion(gradleVersion);
  }

  private void write(String file, String... lines) throws IOException {
    Path path = project.resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  private static String slashes(String path) {
    return path.replace('\\', '/');
  }
}
