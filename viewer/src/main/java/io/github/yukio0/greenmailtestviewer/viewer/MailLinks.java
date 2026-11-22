package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** The links of a mail, from its HTML body and its text body, for the Links tab. */
final class MailLinks {

  static final int MAX_LINKS = 200;

  private static final int MAX_TEXT_LENGTH = 120;

  // The characters allowed in URLs (RFC 3986), so that text right after an address is not part of
  // it.
  private static final Pattern TEXT_URL =
      Pattern.compile("(?i:https?)://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

  private static final String TRAILING = ".,;:!?'\")]";

  private MailLinks() {}

  static List<Link> find(String sanitizedHtml, String text) {
    Map<String, Link> links = new LinkedHashMap<>();
    if (sanitizedHtml != null) {
      for (Element anchor :
          Jsoup.parseBodyFragment(sanitizedHtml).select(HtmlMailSanitizer.LINKS)) {
        String url = anchor.attr("href").trim();
        if (HtmlMailSanitizer.isOpenableLink(url)) {
          links.computeIfAbsent(url, key -> new Link(key, anchorText(anchor))).inHtml = true;
        }
      }
    }
    if (text != null) {
      Matcher matcher = TEXT_URL.matcher(text);
      while (matcher.find()) {
        String url = trimTrailing(matcher.group());
        links.computeIfAbsent(url, key -> new Link(key, null)).inText = true;
      }
    }
    List<Link> list = new ArrayList<>(links.values());
    return list.size() > MAX_LINKS ? list.subList(0, MAX_LINKS) : list;
  }

  private static String anchorText(Element anchor) {
    String text = anchor.normalName().equals("area") ? anchor.attr("alt") : anchor.text();
    text = text.trim();
    if (text.isEmpty()) {
      Element image = anchor.selectFirst("img[alt]");
      text = image == null ? "" : image.attr("alt").trim();
    }
    if (text.isEmpty()) {
      return null;
    }
    if (text.length() <= MAX_TEXT_LENGTH) {
      return text;
    }
    int end =
        Character.isHighSurrogate(text.charAt(MAX_TEXT_LENGTH - 1))
            ? MAX_TEXT_LENGTH - 1
            : MAX_TEXT_LENGTH;
    return text.substring(0, end);
  }

  private static String trimTrailing(String url) {
    int parentheses = balance(url, '(', ')');
    int brackets = balance(url, '[', ']');
    int end = url.length();
    while (end > 0 && TRAILING.indexOf(url.charAt(end - 1)) >= 0) {
      // A closing parenthesis or bracket stays if the address opened it, as in http://[::1].
      char last = url.charAt(end - 1);
      if ((last == ')' && parentheses >= 0) || (last == ']' && brackets >= 0)) {
        break;
      }
      if (last == ')') {
        parentheses++;
      } else if (last == ']') {
        brackets++;
      }
      end--;
    }
    return url.substring(0, end);
  }

  private static int balance(String url, char opening, char closing) {
    int open = 0;
    for (int i = 0; i < url.length(); i++) {
      if (url.charAt(i) == opening) {
        open++;
      } else if (url.charAt(i) == closing) {
        open--;
      }
    }
    return open;
  }

  static final class Link {
    private final String url;
    private final String text;
    private boolean inHtml;
    private boolean inText;

    Link(String url, String text) {
      this.url = url;
      this.text = text;
    }

    String url() {
      return url;
    }

    String text() {
      return text;
    }

    boolean inHtml() {
      return inHtml;
    }

    boolean inText() {
      return inText;
    }
  }
}
