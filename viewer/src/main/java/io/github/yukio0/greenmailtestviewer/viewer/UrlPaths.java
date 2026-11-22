package io.github.yukio0.greenmailtestviewer.viewer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Encodes and decodes path segments. Unlike URLEncoder and URLDecoder, "+" is not a space. */
final class UrlPaths {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private UrlPaths() {}

  static String encodeSegment(String value) {
    StringBuilder encoded = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        encoded.append(c);
      } else {
        encoded.append('%').append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
      }
    }
    return encoded.toString();
  }

  static String decodeSegment(String value) {
    if (value.indexOf('%') < 0) {
      return value;
    }
    StringBuilder decoded = new StringBuilder();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '%' && i + 2 < value.length()) {
        int high = hexValue(value.charAt(i + 1));
        int low = hexValue(value.charAt(i + 2));
        if (high >= 0 && low >= 0) {
          bytes.write(high * 16 + low);
          i += 2;
          continue;
        }
      }
      appendBytes(decoded, bytes);
      decoded.append(c);
    }
    appendBytes(decoded, bytes);
    return decoded.toString();
  }

  private static void appendBytes(StringBuilder decoded, ByteArrayOutputStream bytes) {
    if (bytes.size() > 0) {
      decoded.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
      bytes.reset();
    }
  }

  private static int hexValue(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }
}
