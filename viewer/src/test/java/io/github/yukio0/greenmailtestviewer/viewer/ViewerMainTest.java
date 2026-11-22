package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewerMainTest {

  @TempDir Path project;

  @Test
  void tellsHowToUseAnotherPortWhenThePortIsTaken() {
    assertThat(ViewerMain.listenError("127.0.0.1", 3080, new BindException("Address in use")))
        .isEqualTo(
            "Could not listen on 127.0.0.1:3080: Address in use. If another program uses port"
                + " 3080, start the viewer with another port, such as --port 3081, and set the"
                + " JUnit configuration parameter greenmail.testviewer.viewer-url to"
                + " http://127.0.0.1:3081, so that the links logged for failed tests open this"
                + " viewer.");
  }

  @Test
  void aTakenPortGetsTheHint() throws IOException {
    try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        RecordStore store = new RecordStore(List.of(project))) {
      int port = taken.getLocalPort();
      IOException e =
          catchThrowableOfType(
              IOException.class,
              () ->
                  ViewerServer.bind(
                      ViewerOptions.parse(
                          "--port", String.valueOf(port), "--dir", project.toString()),
                      store));

      assertThat(e).isInstanceOf(BindException.class);
      assertThat(ViewerMain.listenError("127.0.0.1", port, e)).contains("--port ");
    }
  }

  @Test
  void answersOnceStartedAfterThePortWasTaken() throws Exception {
    try (RecordStore store = new RecordStore(List.of(project));
        ViewerServer server =
            ViewerServer.bind(
                ViewerOptions.parse("--port", "0", "--dir", project.toString()), store)) {
      store.start();
      server.start();

      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(server.url().resolve("/api/status")).build(),
                  HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  @Test
  void givesNoHintForOtherErrors() {
    assertThat(ViewerMain.listenError("no-such-host", 3080, new IOException("Unknown host")))
        .isEqualTo("Could not listen on no-such-host:3080: Unknown host");
    assertThat(ViewerMain.listenError("127.0.0.1", 0, new BindException("Address in use")))
        .isEqualTo("Could not listen on 127.0.0.1:0: Address in use");
  }

  @Test
  void setsThePropertiesOfJakartaMailUnlessTheUserSetOtherwise() {
    assertSetUnlessSet(ViewerMain.ALLOW_UTF8, ViewerMain::allowUtf8Headers);
    assertSetUnlessSet(
        ViewerMain.IGNORE_UNKNOWN_ENCODING, ViewerMain::readUnknownEncodingsAsTheyAre);
  }

  private static void assertSetUnlessSet(String name, Runnable set) {
    String before = System.getProperty(name);
    try {
      System.clearProperty(name);
      set.run();
      assertThat(System.getProperty(name)).as(name).isEqualTo("true");

      System.setProperty(name, "false");
      set.run();
      assertThat(System.getProperty(name)).as(name).isEqualTo("false");
    } finally {
      if (before == null) {
        System.clearProperty(name);
      } else {
        System.setProperty(name, before);
      }
    }
  }
}
