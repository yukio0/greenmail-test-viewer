package io.github.yukio0.greenmailtestviewer.viewer;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.BindException;
import java.nio.file.Path;

/** Starts the viewer: {@code java -jar greenmail-test-viewer.jar [options]}. */
public final class ViewerMain {

  private static final Logger LOGGER = System.getLogger(ViewerMain.class.getName());

  static final String ALLOW_UTF8 = "mail.mime.allowutf8";

  static final String IGNORE_UNKNOWN_ENCODING = "mail.mime.ignoreunknownencoding";

  private ViewerMain() {}

  /**
   * Lets Jakarta Mail read headers written in UTF-8 (RFC 6532), which GreenMail keeps as sent. Only
   * the system property also applies to the parts of a message. Jakarta Mail reads it once, so it
   * is set first. A value that the user set is kept.
   */
  static void allowUtf8Headers() {
    setUnlessSet(ALLOW_UTF8);
  }

  /**
   * Lets Jakarta Mail read a body in an unknown transfer encoding as is, like mail programs do, so
   * that it is shown. Set first, like {@link #allowUtf8Headers}.
   */
  static void readUnknownEncodingsAsTheyAre() {
    setUnlessSet(IGNORE_UNKNOWN_ENCODING);
  }

  private static void setUnlessSet(String name) {
    if (System.getProperty(name) == null) {
      System.setProperty(name, "true");
    }
  }

  /**
   * Starts the viewer and serves the UI until the process is stopped.
   *
   * @param args the options, such as {@code --dir}, {@code --port} and {@code --open}; {@code
   *     --help} lists them
   */
  public static void main(String[] args) {
    allowUtf8Headers();
    readUnknownEncodingsAsTheyAre();
    ViewerOptions options;
    try {
      options = ViewerOptions.parse(args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.err.println();
      System.err.println(ViewerOptions.USAGE);
      System.exit(2);
      return;
    }
    if (options.help()) {
      System.out.println(ViewerOptions.USAGE);
      return;
    }

    for (Path directory : options.directories()) {
      LOGGER.log(
          Level.INFO, "Looking for records below {0}", directory.toAbsolutePath().normalize());
    }
    RecordStore store = new RecordStore(options.directories());
    ViewerServer server;
    try {
      // Bind before reading the records so the viewer can report immediately if the port is
      // already in use.
      server = ViewerServer.bind(options, store);
    } catch (IOException e) {
      LOGGER.log(Level.ERROR, listenError(options.host(), options.port(), e));
      store.close();
      System.exit(1);
      return;
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(() -> {
              server.close();
              store.close();
            }));

    store.start();
    if (store.isClosed()) {
      return;
    }
    if (store.snapshot().sources().isEmpty()) {
      LOGGER.log(
          Level.INFO,
          "No records found yet. Run tests with @GreenMailTestViewer; new records are found within"
              + " {0} seconds.",
          RecordStore.DISCOVERY_INTERVAL.getSeconds());
    }
    if (options.staticDirectory() != null) {
      LOGGER.log(
          Level.INFO,
          "Serving the UI from {0}",
          options.staticDirectory().toAbsolutePath().normalize());
    }
    server.start();
    LOGGER.log(Level.INFO, "GreenMail Test Viewer is running at {0}", server.url());
    if (options.open()) {
      BrowserOpener.open(server.url());
    }
    try {
      // The server threads are daemon threads.
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static String listenError(String host, int port, IOException e) {
    String error = "Could not listen on " + host + ":" + port + ": " + e.getMessage();
    if (!(e instanceof BindException) || port == 0) {
      return error;
    }
    int other = port < 65535 ? port + 1 : port - 1;
    return error
        + ". If another program uses port "
        + port
        + ", start the viewer with another port, such as --port "
        + other
        + ", and set the JUnit configuration parameter greenmail.testviewer.viewer-url to"
        + " http://127.0.0.1:"
        + other
        + ", so that the links logged for failed tests open this viewer.";
  }
}
