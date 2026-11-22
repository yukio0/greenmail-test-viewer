package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Makes the HTML body of a mail safe to show and keeps everything else, so that it looks as
 * written. Removes anything that runs code or navigates elsewhere: scripts, frames, plugins, form
 * controls, event handlers, script URLs, meta refresh, {@code base}, and {@code <link>} elements
 * other than style sheets on the web.
 *
 * <p>This is the first of three guards: the preview runs in a sandboxed frame without scripts, and
 * the Content-Security-Policy of the page decides what the styles may load.
 */
final class HtmlMailSanitizer {

  private static final Document.OutputSettings OUTPUT_SETTINGS =
      new Document.OutputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.html);

  private static final String REMOVED_ELEMENTS =
      "script, iframe, frame, frameset, object, embed, applet, base, meta, title,"
          + " input, select, textarea";

  private static final String UNWRAPPED_ELEMENTS = "form, button";

  static final String LINKS = "a[href], area[href]";

  private static final Set<String> LINK_ATTRIBUTES =
      Set.of("href", "xlink:href", "action", "formaction", "cite", "longdesc");

  private static final Set<String> LOADING_ATTRIBUTES =
      Set.of("src", "srcset", "background", "poster", "lowsrc", "dynsrc", "data");

  /**
   * The sanitized body with the styles of the head: a head with them and the body element with its
   * attributes, or the styles followed by what is in the body when it has no attributes.
   */
  String sanitize(String html) {
    Document document = Jsoup.parse(html, "");
    Element body = document.body();
    // Only the body is shown: the styles of the head are sanitized with it, and the direction and
    // language of the whole mail apply to it.
    Element root = document.selectFirst("html");
    for (String name : new String[] {"dir", "lang"}) {
      if (root != null && root.hasAttr(name) && !body.hasAttr(name)) {
        body.attr(name, root.attr(name));
      }
    }
    List<Element> headStyles = document.head().select("style, link");
    for (int i = headStyles.size() - 1; i >= 0; i--) {
      body.prependChild(headStyles.get(i));
    }
    body.select(REMOVED_ELEMENTS).remove();
    body.select(UNWRAPPED_ELEMENTS).unwrap();
    for (Element link : body.select("link")) {
      if (!isStyleSheetOnTheWeb(link)) {
        link.remove();
      }
    }
    for (Element element : body.getAllElements()) {
      removeUnsafeAttributes(element);
    }
    // An image kept in a srcset, or in a source of its picture, is shown even without a src.
    for (Element image : body.select("img:not([src]):not([srcset])")) {
      if (!hasSourceWithImage(image)) {
        image.replaceWith(placeholder(image));
      }
    }
    // Links open in a new tab, as the frame may not show other pages. Links to places in the mail
    // stay in the frame as places in about:srcdoc; otherwise the frame would take them for places
    // in the viewer's page.
    for (Element link : body.select(LINKS)) {
      String href = link.attr("href").trim();
      if (href.startsWith("#")) {
        link.attr("href", "about:srcdoc" + href);
        link.removeAttr("target");
      } else {
        link.attr("target", "_blank");
        link.attr("rel", "noopener noreferrer");
      }
    }
    document.outputSettings(OUTPUT_SETTINGS);
    if (body.attributesSize() == 0) {
      return body.html();
    }
    // Put in a head, so that the first element of the body is still its first child (:first-child).
    StringBuilder sanitized = new StringBuilder("<head>");
    for (Element style : headStyles) {
      if (style.parent() == body) {
        sanitized.append(style.outerHtml());
        style.remove();
      }
    }
    return sanitized.append("</head>").append(body.outerHtml()).toString();
  }

  private static boolean isStyleSheetOnTheWeb(Element link) {
    boolean styleSheet = false;
    for (String rel : link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+")) {
      styleSheet |= rel.equals("stylesheet");
    }
    return styleSheet && isWebAddress(compact(link.attr("href")));
  }

  private static boolean hasSourceWithImage(Element image) {
    Element parent = image.parent();
    if (parent == null || !parent.normalName().equals("picture")) {
      return false;
    }
    for (Element sibling : parent.children()) {
      if (sibling.normalName().equals("source") && sibling.hasAttr("srcset")) {
        return true;
      }
    }
    return false;
  }

  /**
   * The placeholder for an image that is not loaded, such as one with a file: URL: its description,
   * at the image's size.
   */
  private static Element placeholder(Element image) {
    String alt = image.attr("alt").trim();
    StringBuilder style =
        new StringBuilder(
            "display:inline-block;box-sizing:border-box;max-width:100%;overflow:hidden;"
                + "vertical-align:middle;border:1px dashed #9b9b9b;border-radius:3px;"
                + "padding:2px 6px;color:#6b6b6b;font:12px/1.4 system-ui,sans-serif;");
    for (String size : new String[] {"width", "height"}) {
      String value = image.attr(size).trim();
      if (value.matches("[0-9]{1,4}")) {
        style.append(size).append(':').append(value).append("px;");
      }
    }
    return new Element("span")
        .attr(
            "title",
            "This image is not loaded because its address is neither part of the email nor a web"
                + " address.")
        .attr("style", style.toString())
        .text(alt.isEmpty() ? "Image" : alt);
  }

  private static void removeUnsafeAttributes(Element element) {
    for (Attribute attribute : new ArrayList<>(element.attributes().asList())) {
      String name = attribute.getKey().toLowerCase(Locale.ROOT);
      if (name.equals("srcset")) {
        keepLoadableCandidates(element, attribute);
        continue;
      }
      String value = compact(attribute.getValue());
      // A referrerpolicy from the mail could reveal the viewer's address to other servers.
      boolean unsafe =
          name.startsWith("on")
              || name.equals("ping")
              || name.equals("referrerpolicy")
              || (LINK_ATTRIBUTES.contains(name) && !isLink(value))
              || (LOADING_ATTRIBUTES.contains(name) && !isLoadable(value));
      if (unsafe) {
        element.removeAttr(attribute.getKey());
      }
    }
  }

  private static void keepLoadableCandidates(Element element, Attribute attribute) {
    List<SrcSet.Candidate> kept = new ArrayList<>();
    for (SrcSet.Candidate candidate : SrcSet.parse(attribute.getValue())) {
      if (isLoadable(compact(candidate.url()))) {
        kept.add(candidate);
      }
    }
    if (kept.isEmpty()) {
      element.removeAttr(attribute.getKey());
    } else {
      element.attr(attribute.getKey(), SrcSet.format(kept));
    }
  }

  static boolean isOpenableLink(String href) {
    return isOpenable(compact(href));
  }

  private static boolean isOpenable(String compacted) {
    return isWebAddress(compacted) || compacted.startsWith("mailto:");
  }

  private static boolean isLink(String value) {
    return isOpenable(value) || value.startsWith("tel:") || value.startsWith("#");
  }

  private static boolean isLoadable(String value) {
    return value.startsWith("cid:") || value.startsWith("data:image/") || isWebAddress(value);
  }

  private static boolean isWebAddress(String compacted) {
    return compacted.startsWith("http://")
        || compacted.startsWith("https://")
        || compacted.startsWith("//");
  }

  // Normalized as browsers read a URL: without spaces and control characters, in lower case.
  private static String compact(String value) {
    return value.replaceAll("[\\s\\x00-\\x1f]", "").toLowerCase(Locale.ROOT);
  }
}
