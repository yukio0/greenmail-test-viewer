package io.github.yukio0.greenmailtestviewer.viewer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The command line options of the viewer. */
final class ViewerOptions {

  static final String DEFAULT_HOST = "127.0.0.1";
  static final int DEFAULT_PORT = 3080;

  static final String USAGE =
      String.join(
          System.lineSeparator(),
          "Usage: java -jar greenmail-test-viewer.jar [options]",
          "",
          "Shows the emails that the greenmail-test-viewer extension recorded in tests.",
          "",
          "Options:",
          "  --dir <directory>         Search this directory and the directories below it for",
          "                            output directories, found by their marker file. Can be",
          "                            given more than once. Default: the current directory",
          "  --host <address>          Address to listen on. Default: " + DEFAULT_HOST,
          "  --port <port>             Port to listen on. 0 picks a free port. Default: "
              + DEFAULT_PORT,
          "  --allowed-host <name>     Also answer requests for this host name, without a port,",
          "                            such as the name of this machine. localhost and IP",
          "                            addresses are always answered. Can be given more than",
          "                            once. * accepts any name, which lets other websites read",
          "                            the emails (DNS rebinding)",
          "  --open                    Open the viewer in the default browser of this machine",
          "  --block-remote-css-and-fonts",
          "                            Do not let emails load style sheets and fonts from other",
          "                            servers. Images on other servers are still shown",
          "  --static-dir <directory>  Serve the UI from this directory instead of the jar",
          "                            (for developing the viewer)",
          "  --help                    Show this help");

  private final List<Path> directories;
  private final String host;
  private final int port;
  private final List<String> allowedHosts;
  private final Path staticDirectory;
  private final boolean open;
  private final boolean blockRemoteCssAndFonts;
  private final boolean help;

  private ViewerOptions(
      List<Path> directories,
      String host,
      int port,
      List<String> allowedHosts,
      Path staticDirectory,
      boolean open,
      boolean blockRemoteCssAndFonts,
      boolean help) {
    this.directories = List.copyOf(directories);
    this.host = host;
    this.port = port;
    this.allowedHosts = List.copyOf(allowedHosts);
    this.staticDirectory = staticDirectory;
    this.open = open;
    this.blockRemoteCssAndFonts = blockRemoteCssAndFonts;
    this.help = help;
  }

  static ViewerOptions parse(String... args) {
    List<Path> directories = new ArrayList<>();
    String host = DEFAULT_HOST;
    int port = DEFAULT_PORT;
    List<String> allowedHosts = new ArrayList<>();
    Path staticDirectory = null;
    boolean open = false;
    boolean blockRemoteCssAndFonts = false;
    boolean help = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      String name = arg;
      String value = null;
      int equals = arg.indexOf('=');
      if (arg.startsWith("--") && equals > 0) {
        name = arg.substring(0, equals);
        value = arg.substring(equals + 1);
      }
      switch (name) {
        case "--help":
        case "-h":
          noValue(name, value);
          help = true;
          break;
        case "--open":
          noValue(name, value);
          open = true;
          break;
        case "--block-remote-css-and-fonts":
          noValue(name, value);
          blockRemoteCssAndFonts = true;
          break;
        case "--dir":
          if (value == null) {
            value = next(args, ++i, name);
          }
          directories.add(Path.of(value));
          break;
        case "--host":
          host = value != null ? value : next(args, ++i, name);
          break;
        case "--port":
          port = port(value != null ? value : next(args, ++i, name));
          break;
        case "--allowed-host":
          allowedHosts.add(allowedHost(value != null ? value : next(args, ++i, name)));
          break;
        case "--static-dir":
          staticDirectory = Path.of(value != null ? value : next(args, ++i, name));
          if (!Files.isDirectory(staticDirectory)) {
            throw new IllegalArgumentException(
                "--static-dir is not a directory: " + staticDirectory.toAbsolutePath());
          }
          break;
        default:
          throw new IllegalArgumentException("Unknown option: " + arg);
      }
    }
    if (directories.isEmpty()) {
      directories.add(Path.of(""));
    }
    for (Path directory : help ? List.<Path>of() : directories) {
      if (!Files.isDirectory(directory)) {
        throw new IllegalArgumentException(
            "--dir is not a directory: " + directory.toAbsolutePath());
      }
    }
    return new ViewerOptions(
        directories, host, port, allowedHosts, staticDirectory, open, blockRemoteCssAndFonts, help);
  }

  // Otherwise --open=false would open the browser.
  private static void noValue(String name, String value) {
    if (value != null) {
      throw new IllegalArgumentException(name + " does not take a value: " + name + "=" + value);
    }
  }

  private static String next(String[] args, int index, String name) {
    if (index >= args.length) {
      throw new IllegalArgumentException(name + " needs a value");
    }
    return args[index];
  }

  // Requests are compared by host name alone, so a value with a port or scheme would never match.
  private static String allowedHost(String value) {
    String host = value.trim();
    if (host.isEmpty()) {
      throw new IllegalArgumentException("--allowed-host needs a host name");
    }
    boolean bracketed = host.startsWith("[");
    boolean port =
        bracketed
            ? !host.endsWith("]")
            : host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':');
    if (port || host.indexOf('/') >= 0) {
      throw new IllegalArgumentException(
          "--allowed-host takes a host name without a scheme or port, such as myhost: " + value);
    }
    return host;
  }

  private static int port(String value) {
    try {
      int port = Integer.parseInt(value);
      if (port >= 0 && port <= 65535) {
        return port;
      }
    } catch (NumberFormatException e) {
    }
    throw new IllegalArgumentException("--port must be an integer from 0 to 65535: " + value);
  }

  List<Path> directories() {
    return directories;
  }

  String host() {
    return host;
  }

  int port() {
    return port;
  }

  List<String> allowedHosts() {
    return allowedHosts;
  }

  Path staticDirectory() {
    return staticDirectory;
  }

  boolean open() {
    return open;
  }

  boolean blockRemoteCssAndFonts() {
    return blockRemoteCssAndFonts;
  }

  boolean help() {
    return help;
  }
}
