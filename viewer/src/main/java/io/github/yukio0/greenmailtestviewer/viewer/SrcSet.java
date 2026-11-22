package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.ArrayList;
import java.util.List;

/**
 * The candidates of a {@code srcset}, read as browsers read them: a URL runs to the next
 * whitespace, so that a {@code data:} URL keeps its commas.
 */
final class SrcSet {

  private SrcSet() {}

  static List<Candidate> parse(String value) {
    List<Candidate> candidates = new ArrayList<>();
    int length = value.length();
    int i = 0;
    while (true) {
      while (i < length && (isWhitespace(value.charAt(i)) || value.charAt(i) == ',')) {
        i++;
      }
      if (i >= length) {
        return candidates;
      }
      int start = i;
      while (i < length && !isWhitespace(value.charAt(i))) {
        i++;
      }
      String url = value.substring(start, i);
      String descriptor = "";
      if (url.endsWith(",")) {
        url = url.replaceAll(",+$", "");
      } else {
        int descriptorStart = i;
        boolean inParentheses = false;
        while (i < length) {
          char c = value.charAt(i);
          if (c == '(') {
            inParentheses = true;
          } else if (c == ')') {
            inParentheses = false;
          } else if (c == ',' && !inParentheses) {
            break;
          }
          i++;
        }
        descriptor = value.substring(descriptorStart, i).trim();
      }
      if (!url.isEmpty()) {
        candidates.add(new Candidate(url, descriptor));
      }
    }
  }

  static String format(List<Candidate> candidates) {
    StringBuilder value = new StringBuilder();
    for (Candidate candidate : candidates) {
      if (value.length() > 0) {
        value.append(", ");
      }
      value.append(candidate.url());
      if (!candidate.descriptor().isEmpty()) {
        value.append(' ').append(candidate.descriptor());
      }
    }
    return value.toString();
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
  }

  static final class Candidate {
    private final String url;
    private final String descriptor;

    Candidate(String url, String descriptor) {
      this.url = url;
      this.descriptor = descriptor;
    }

    String url() {
      return url;
    }

    String descriptor() {
      return descriptor;
    }

    Candidate withUrl(String newUrl) {
      return new Candidate(newUrl, descriptor);
    }
  }
}
