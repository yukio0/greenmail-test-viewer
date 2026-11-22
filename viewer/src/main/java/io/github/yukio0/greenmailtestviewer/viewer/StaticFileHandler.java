package io.github.yukio0.greenmailtestviewer.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;

/** Serves the UI files from the jar, or from {@code --static-dir} while developing them. */
final class StaticFileHandler implements HttpHandler {

  private static final String RESOURCE_ROOT = "/static/";

  /**
   * The Content-Security-Policy of the page, which the HTML preview inherits: scripts only from the
   * viewer, images from anywhere, and style sheets and fonts too unless {@code
   * blockRemoteCssAndFonts}.
   */
  static String contentSecurityPolicy(boolean blockRemoteCssAndFonts) {
    String cssAndFonts = blockRemoteCssAndFonts ? "'self'" : "*";
    return "default-src 'self'; img-src * data:; style-src "
        + cssAndFonts
        + " 'unsafe-inline'; font-src "
        + cssAndFonts
        + " data:; object-src 'none'; base-uri 'none'; form-action 'none';"
        + " frame-ancestors 'none'";
  }

  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "css", "text/css; charset=utf-8",
          "js", "text/javascript; charset=utf-8",
          "json", "application/json; charset=utf-8",
          "svg", "image/svg+xml",
          "png", "image/png",
          "ico", "image/x-icon",
          "txt", "text/plain; charset=utf-8");

  private final Path staticDirectory;
  private final Path realStaticDirectory;
  private final String contentSecurityPolicy;

  StaticFileHandler(Path staticDirectory, boolean blockRemoteCssAndFonts) {
    this.staticDirectory =
        staticDirectory == null ? null : staticDirectory.toAbsolutePath().normalize();
    this.realStaticDirectory = this.staticDirectory == null ? null : realPath(this.staticDirectory);
    this.contentSecurityPolicy = contentSecurityPolicy(blockRemoteCssAndFonts);
  }

  private static Path realPath(Path directory) {
    try {
      return directory.toRealPath();
    } catch (IOException e) {
      return directory;
    }
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      String method = exchange.getRequestMethod();
      if (!"GET".equals(method) && !"HEAD".equals(method)) {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        Http.sendError(exchange, 405, "Method not allowed: " + method);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      String relative = path.equals("/") ? "index.html" : path.substring(1);
      byte[] content;
      try {
        content = isSafe(relative) ? read(relative) : null;
      } catch (IOException e) {
        Http.sendError(exchange, 500, "Could not read " + path + ": " + e);
        return;
      }
      if (content == null) {
        Http.sendError(exchange, 404, "Not found: " + path);
        return;
      }
      exchange.getResponseHeaders().set("Content-Type", contentType(relative));
      exchange.getResponseHeaders().set("Cache-Control", "no-cache");
      exchange.getResponseHeaders().set("Content-Security-Policy", contentSecurityPolicy);
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      Http.send(exchange, 200, content);
    } finally {
      exchange.close();
    }
  }

  private static boolean isSafe(String relative) {
    if (relative.isEmpty() || relative.contains("\\") || relative.contains(":")) {
      return false;
    }
    for (String segment : relative.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  private byte[] read(String relative) throws IOException {
    if (staticDirectory != null) {
      Path file;
      try {
        file = staticDirectory.resolve(relative).normalize();
      } catch (InvalidPathException e) {
        return null;
      }
      // A link may lead out of the directory.
      if (!file.startsWith(staticDirectory)
          || !Files.isRegularFile(file)
          || !file.toRealPath().startsWith(realStaticDirectory)) {
        return null;
      }
      return Files.readAllBytes(file);
    }
    URL resource = StaticFileHandler.class.getResource(RESOURCE_ROOT + relative);
    if (resource == null || !isFile(resource)) {
      return null;
    }
    try (InputStream input = resource.openStream()) {
      return input.readAllBytes();
    }
  }

  private static boolean isFile(URL resource) throws IOException {
    URLConnection connection = resource.openConnection();
    if (connection instanceof JarURLConnection) {
      JarEntry entry = ((JarURLConnection) connection).getJarEntry();
      return entry != null && !entry.isDirectory();
    }
    if ("file".equals(resource.getProtocol())) {
      try {
        return Files.isRegularFile(Path.of(resource.toURI()));
      } catch (URISyntaxException | IllegalArgumentException e) {
        return false;
      }
    }
    return true;
  }

  private static String contentType(String relative) {
    String extension = relative.substring(relative.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
  }
}
