package io.github.yukio0.greenmailtestviewer.viewer;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Opens the viewer in the default browser for {@code --open}, with the command of the operating
 * system rather than {@code java.awt.Desktop}, which would start AWT. Where none works, such as in
 * a container, it logs the URL instead.
 */
final class BrowserOpener {

  private static final Logger LOGGER = System.getLogger(BrowserOpener.class.getName());

  private static final long WAIT_SECONDS = 5;

  interface Runner {
    Integer run(List<String> command) throws IOException, InterruptedException;
  }

  private BrowserOpener() {}

  static boolean open(URI url) {
    return open(url, System.getProperty("os.name", ""), BrowserOpener::start);
  }

  static boolean open(URI url, String osName, Runner runner) {
    List<String> command = command(osName, url);
    try {
      Integer exitCode = runner.run(command);
      if (exitCode == null || exitCode == 0) {
        LOGGER.log(Level.INFO, "Opened {0} in the browser", url);
        return true;
      }
    } catch (IOException e) {
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    LOGGER.log(Level.WARNING, "Could not open a browser here. Open {0} in a browser.", url);
    return false;
  }

  static List<String> command(String osName, URI url) {
    String os = osName.toLowerCase(Locale.ROOT);
    if (os.startsWith("windows")) {
      return List.of("rundll32", "url.dll,FileProtocolHandler", url.toString());
    }
    if (os.startsWith("mac")) {
      return List.of("open", url.toString());
    }
    return List.of("xdg-open", url.toString());
  }

  private static Integer start(List<String> command) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    return process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS) ? process.exitValue() : null;
  }
}
