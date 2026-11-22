package io.github.yukio0.greenmailtestviewer.viewer;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** The HTTP server of the viewer, on the JDK's {@link HttpServer}. */
final class ViewerServer implements AutoCloseable {

  private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
  private static final Pattern IPV6 =
      Pattern.compile("\\[[0-9a-f.]*:[0-9a-f:.]*(%25[0-9a-z._~-]+)?\\]");

  private final HttpServer server;
  private final ExecutorService executor;

  private ViewerServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  static ViewerServer start(ViewerOptions options, RecordStore store) throws IOException {
    ViewerServer server = bind(options, store);
    server.start();
    return server;
  }

  static ViewerServer bind(ViewerOptions options, RecordStore store) throws IOException {
    InetSocketAddress address = new InetSocketAddress(options.host(), options.port());
    if (address.isUnresolved()) {
      throw new IOException("Unknown host: " + options.host());
    }
    HttpServer server = HttpServer.create(address, 0);
    Filter hostCheck = new HostCheck(options.allowedHosts());
    HttpContext api = server.createContext("/api/", new ApiHandler(store, options.directories()));
    api.getFilters().add(hostCheck);
    HttpContext screen =
        server.createContext(
            "/",
            new StaticFileHandler(options.staticDirectory(), options.blockRemoteCssAndFonts()));
    screen.getFilters().add(hostCheck);
    ExecutorService executor =
        Executors.newFixedThreadPool(
            8,
            task -> {
              Thread thread = new Thread(task, "greenmail-test-viewer-http");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
    return new ViewerServer(server, executor);
  }

  void start() {
    server.start();
  }

  int port() {
    return server.getAddress().getPort();
  }

  URI url() {
    InetAddress address = server.getAddress().getAddress();
    String host;
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
      host = address.isLoopbackAddress() ? address.getHostAddress() : "localhost";
    } else {
      host = address.getHostAddress();
    }
    if (host.contains(":")) {
      host = "[" + host + "]";
    }
    return URI.create("http://" + host + ":" + port() + "/");
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  /**
   * Whether a request for the given host name may be answered: localhost, IP addresses and the
   * names of {@code --allowed-host}. A web page could otherwise point its own domain at the viewer
   * (DNS rebinding) and read the mails.
   */
  static boolean isAllowedHost(String hostHeader, Set<String> allowedHosts) {
    if (hostHeader == null) {
      return false;
    }
    String host = hostHeader.trim().toLowerCase(Locale.ROOT);
    if (host.startsWith("[")) {
      int end = host.indexOf(']');
      host = end < 0 ? host : host.substring(0, end + 1);
    } else if (host.indexOf(':') >= 0) {
      host = host.substring(0, host.indexOf(':'));
    }
    return host.equals("localhost")
        || host.endsWith(".localhost")
        || IPV4.matcher(host).matches()
        || IPV6.matcher(host).matches()
        || allowedHosts.contains(host)
        || allowedHosts.contains("*");
  }

  private static final class HostCheck extends Filter {
    private final Set<String> allowedHosts = new HashSet<>();

    HostCheck(Collection<String> allowedHosts) {
      for (String host : allowedHosts) {
        this.allowedHosts.add(host.trim().toLowerCase(Locale.ROOT));
      }
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
      if (isAllowedHost(exchange.getRequestHeaders().getFirst("Host"), allowedHosts)) {
        chain.doFilter(exchange);
        return;
      }
      byte[] body =
          ("Forbidden: requests for this host name are not accepted. Open the viewer with"
                  + " localhost or an IP address, or start it with --allowed-host <name>.")
              .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
      Http.send(exchange, 403, body);
      exchange.close();
    }

    @Override
    public String description() {
      return "Accepts only allowed host names";
    }
  }
}
