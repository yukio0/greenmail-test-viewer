package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which mails the list shows: those of the selected part of a view that match the search, in the
 * requested order, and with a limit, only that many. It also tells whether the mail named by {@code
 * selected} is in the list, even when it is beyond the limit.
 */
final class MailListQuery {

  static final String ORDER_NEWEST = "newest";
  static final String ORDER_OLDEST = "oldest";

  private static final List<String> GROUPS =
      List.of(
          ResultView.GROUP_OUTSIDE_TESTS,
          ResultView.GROUP_OUTSIDE_CLASSES,
          ResultView.GROUP_AMBIGUOUS);

  private final String source;
  private final String classId;
  private final String testId;
  private final String group;
  private final List<String> terms;
  private final boolean newestFirst;
  private final Integer limit;
  private final String selected;

  private MailListQuery(
      String source,
      String classId,
      String testId,
      String group,
      List<String> terms,
      boolean newestFirst,
      Integer limit,
      String selected) {
    this.source = source;
    this.classId = classId;
    this.testId = testId;
    this.group = group;
    this.terms = List.copyOf(terms);
    this.newestFirst = newestFirst;
    this.limit = limit;
    this.selected = selected;
  }

  static MailListQuery parse(Map<String, String> query) {
    String group = blankToNull(query.get("group"));
    if (group != null && !GROUPS.contains(group)) {
      throw new IllegalArgumentException(
          "Unknown group: " + group + ". Use one of " + GROUPS + ".");
    }
    String order = blankToNull(query.get("order"));
    if (order != null && !order.equals(ORDER_NEWEST) && !order.equals(ORDER_OLDEST)) {
      throw new IllegalArgumentException(
          "Unknown order: " + order + ". Use " + ORDER_NEWEST + " or " + ORDER_OLDEST + ".");
    }
    String limitText = blankToNull(query.get("limit"));
    Integer limit = positiveInteger(limitText);
    if (limitText != null && limit == null) {
      throw new IllegalArgumentException(
          "The limit must be a positive integer: " + query.get("limit"));
    }
    return new MailListQuery(
        blankToNull(query.get("source")),
        blankToNull(query.get("class")),
        blankToNull(query.get("test")),
        group,
        searchTerms(query.get("q")),
        ORDER_NEWEST.equals(order),
        limit,
        blankToNull(query.get("selected")));
  }

  List<String> terms() {
    return terms;
  }

  Result select(ResultView view) {
    List<ShownMail> matching = new ArrayList<>();
    for (ShownMail mail : view.mails()) {
      if (matches(mail)) {
        matching.add(mail);
      }
    }
    if (newestFirst) {
      Collections.reverse(matching);
    }
    int count = limit == null ? matching.size() : Math.min(limit, matching.size());
    boolean selectedInList =
        selected != null && matching.stream().anyMatch(mail -> mail.mail().id().equals(selected));
    return new Result(
        matching.subList(0, count), matching.size(), newestCapturedAt(matching), selectedInList);
  }

  private boolean matches(ShownMail mail) {
    return (source == null || source.equals(mail.module().source().id()))
        && (classId == null
            || (mail.classNode() != null && classId.equals(mail.classNode().uniqueId())))
        && (testId == null || (mail.test() != null && testId.equals(mail.test().uniqueId())))
        && (group == null || group.equals(mail.group()))
        && mail.mail().matches(terms);
  }

  private static Timestamp newestCapturedAt(List<ShownMail> mails) {
    Timestamp newest = null;
    for (ShownMail mail : mails) {
      Timestamp capturedAt = mail.mail().capturedAt();
      if (capturedAt != null
          && (newest == null || capturedAt.instant().isAfter(newest.instant()))) {
        newest = capturedAt;
      }
    }
    return newest;
  }

  // Split at any whitespace, including the ideographic space of Japanese input.
  static List<String> searchTerms(String query) {
    List<String> terms = new ArrayList<>();
    if (query == null) {
      return terms;
    }
    for (String term : query.toLowerCase(Locale.ROOT).split("(?U)\\s+")) {
      if (!term.isEmpty()) {
        terms.add(term);
      }
    }
    return terms;
  }

  private static Integer positiveInteger(String value) {
    if (value == null) {
      return null;
    }
    try {
      int number = Integer.parseInt(value.trim());
      return number >= 1 ? number : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static final class Result {
    private final List<ShownMail> shown;
    private final int total;
    private final Timestamp newestCapturedAt;
    private final boolean selectedInList;

    Result(List<ShownMail> shown, int total, Timestamp newestCapturedAt, boolean selectedInList) {
      this.shown = List.copyOf(shown);
      this.total = total;
      this.newestCapturedAt = newestCapturedAt;
      this.selectedInList = selectedInList;
    }

    List<ShownMail> shown() {
      return shown;
    }

    int total() {
      return total;
    }

    Timestamp newestCapturedAt() {
      return newestCapturedAt;
    }

    boolean selectedInList() {
      return selectedInList;
    }
  }
}
