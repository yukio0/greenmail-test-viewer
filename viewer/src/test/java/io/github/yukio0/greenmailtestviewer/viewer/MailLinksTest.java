package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.yukio0.greenmailtestviewer.viewer.MailLinks.Link;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MailLinksTest {

  @Test
  void findsTheLinksOfTheHtmlBodyWithTheirText() {
    List<Link> links =
        MailLinks.find(
            "<a href=\"https://example.com/confirm?token=abc\">Confirm   your\n address</a>"
                + "<a href=\"mailto:help@example.com\">help@example.com</a>"
                + "<a href=\"https://example.com/logo\"><img alt=\"Example shop\"></a>"
                + "<a href=\"https://example.com/empty\"></a>"
                + "<a href=\"//example.com/reset\">Reset</a>"
                + "<a href=\"#top\">Top</a><a href=\"tel:0123\">Call</a>"
                + "<a href=\"about:srcdoc#notes\">Notes</a>",
            null);

    assertThat(links)
        .extracting(Link::url, Link::text, Link::inHtml, Link::inText)
        .containsExactly(
            tuple("https://example.com/confirm?token=abc", "Confirm your address", true, false),
            tuple("mailto:help@example.com", "help@example.com", true, false),
            tuple("https://example.com/logo", "Example shop", true, false),
            tuple("https://example.com/empty", null, true, false),
            tuple("//example.com/reset", "Reset", true, false));
  }

  @Test
  void cutsALongTextWithoutHalfACharacter() {
    String fits = "a".repeat(118) + "😀";
    String cut = "a".repeat(119) + "😀b";

    List<Link> links =
        MailLinks.find(
            "<a href=\"https://example.com/fits\">"
                + fits
                + "</a><a href=\"https://example.com/cut\">"
                + cut
                + "</a><a href=\"https://example.com/long\">"
                + "a".repeat(130)
                + "</a>",
            null);

    assertThat(links)
        .extracting(Link::text)
        .containsExactly(fits, "a".repeat(119), "a".repeat(120));
  }

  @Test
  void findsTheAddressesOfTheTextBodyWithoutWhatEndsTheSentence() {
    List<Link> links =
        MailLinks.find(
            null,
            "Confirm at https://example.com/confirm?token=abc.\n"
                + "(See https://example.com/help), or <https://example.com/faq>.\n"
                + "Wiki: https://example.com/wiki/Mail_(protocol)\n"
                + "確認：https://example.com/ja/confirmから進んでください。");

    assertThat(links)
        .extracting(Link::url)
        .containsExactly(
            "https://example.com/confirm?token=abc",
            "https://example.com/help",
            "https://example.com/faq",
            "https://example.com/wiki/Mail_(protocol)",
            "https://example.com/ja/confirm");
    assertThat(links).allMatch(link -> link.inText() && !link.inHtml() && link.text() == null);
  }

  @Test
  void findsTheAddressesOfTheTextBodyWithASchemeInAnyCase() {
    List<Link> links =
        MailLinks.find(
            null,
            "CONFIRM AT HTTPS://EXAMPLE.COM/CONFIRM\n"
                + "Help: Https://example.com/help\n"
                + "Old: HTTP://example.com/old");

    assertThat(links)
        .extracting(Link::url)
        .containsExactly(
            "HTTPS://EXAMPLE.COM/CONFIRM", "Https://example.com/help", "HTTP://example.com/old");
  }

  @Test
  void keepsTheClosingBracketOfAnAddressThatHasTheOpeningOne() {
    List<Link> links =
        MailLinks.find(
            null,
            "Local: http://[::1]\n"
                + "Server: http://[2001:db8::1].\n"
                + "Search: https://example.com/search?tags[]\n"
                + "[See https://example.com/help]");

    assertThat(links)
        .extracting(Link::url)
        .containsExactly(
            "http://[::1]",
            "http://[2001:db8::1]",
            "https://example.com/search?tags[]",
            "https://example.com/help");
  }

  @Test
  void leavesOutTheClosingCharacterOfTheTextAroundAnAddress() {
    List<Link> links =
        MailLinks.find(
            null,
            "[http://[::1]]\n"
                + "(Wiki: https://example.com/wiki/Mail_(protocol))\n"
                + "[See http://[2001:db8::1]:8080/help].");

    assertThat(links)
        .extracting(Link::url)
        .containsExactly(
            "http://[::1]",
            "https://example.com/wiki/Mail_(protocol)",
            "http://[2001:db8::1]:8080/help");
  }

  @Test
  void findsAnAddressFollowedByManyClosingCharactersQuickly() {
    String text =
        "http://example.com/a"
            + ")".repeat(200_000)
            + "\nhttp://[::1]"
            + "]".repeat(200_000)
            + "\n";

    List<Link> links =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> MailLinks.find(null, text));

    assertThat(links).extracting(Link::url).containsExactly("http://example.com/a", "http://[::1]");
  }

  @Test
  void findsTheLinksOfAnImageMapWithTheirDescriptions() {
    List<Link> links =
        MailLinks.find(
            "<a href=\"https://example.com/first\">First</a>"
                + "<img src=\"https://example.com/map.png\" usemap=\"#m\"><map name=\"m\">"
                + "<area shape=\"rect\" coords=\"0,0,10,10\" href=\"https://example.com/sale\""
                + " alt=\"Sale\">"
                + "<area shape=\"rect\" coords=\"10,0,20,10\" href=\"https://example.com/new\">"
                + "<area shape=\"rect\" coords=\"20,0,30,10\" href=\"#top\" alt=\"Top\"></map>"
                + "<a href=\"https://example.com/last\">Last</a>",
            null);

    assertThat(links)
        .extracting(Link::url, Link::text, Link::inHtml, Link::inText)
        .containsExactly(
            tuple("https://example.com/first", "First", true, false),
            tuple("https://example.com/sale", "Sale", true, false),
            tuple("https://example.com/new", null, true, false),
            tuple("https://example.com/last", "Last", true, false));
  }

  @Test
  void listsALinkOfBothBodiesOnce() {
    List<Link> links =
        MailLinks.find(
            "<a href=\"https://example.com/confirm\">Confirm</a>",
            "Confirm: https://example.com/confirm\nOnly in text: https://example.com/text");

    assertThat(links)
        .extracting(Link::url, Link::text, Link::inHtml, Link::inText)
        .containsExactly(
            tuple("https://example.com/confirm", "Confirm", true, true),
            tuple("https://example.com/text", null, false, true));
  }

  @Test
  void listsAtMostTheLimit() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < MailLinks.MAX_LINKS + 10; i++) {
      text.append("https://example.com/").append(i).append('\n');
    }

    assertThat(MailLinks.find(null, text.toString())).hasSize(MailLinks.MAX_LINKS);
  }
}
