package io.github.yukio0.greenmailtestviewer.viewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Http {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Http() {}

  static Map<String, String> queryParameters(HttpExchange exchange) {
    Map<String, String> parameters = new LinkedHashMap<>();
    String query = exchange.getRequestURI().getRawQuery();
    if (query == null || query.isEmpty()) {
      return parameters;
    }
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int separator = pair.indexOf('=');
      String name = separator < 0 ? pair : pair.substring(0, separator);
      String value = separator < 0 ? "" : pair.substring(separator + 1);
      parameters.putIfAbsent(decodeQuery(name), decodeQuery(value));
    }
    return parameters;
  }

  private static String decodeQuery(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(body);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    send(exchange, status, bytes);
  }

  static void sendError(HttpExchange exchange, int status, String message) throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message);
    sendJson(exchange, status, body);
  }

  /**
   * Sends a file from a mail, which is not trusted: the browser may neither guess its type nor run
   * anything from it.
   */
  static void sendFile(
      HttpExchange exchange, String contentType, String disposition, String fileName, byte[] bytes)
      throws IOException {
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", contentType);
    headers.set("Content-Disposition", contentDisposition(disposition, fileName));
    headers.set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:");
    headers.set("Cache-Control", "no-store");
    send(exchange, 200, bytes);
  }

  static void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    if ("HEAD".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(status, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    try (OutputStream body = exchange.getResponseBody()) {
      body.write(bytes);
    }
  }

  static String contentDisposition(String disposition, String fileName) {
    StringBuilder ascii = new StringBuilder();
    for (int i = 0; i < fileName.length(); i++) {
      char c = fileName.charAt(i);
      ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != '%' ? c : '_');
    }
    return disposition
        + "; filename=\""
        + ascii
        + "\"; filename*=UTF-8''"
        + UrlPaths.encodeSegment(fileName);
  }
}
