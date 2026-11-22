package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.Comparator;

/** Compares numbers in strings by value, so that {@code [2]} comes before {@code [10]}. */
final class NaturalOrder implements Comparator<String> {

  static final NaturalOrder INSTANCE = new NaturalOrder();

  private NaturalOrder() {}

  @Override
  public int compare(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (isDigit(ca) && isDigit(cb)) {
        int startA = i;
        int startB = j;
        while (i < a.length() && isDigit(a.charAt(i))) {
          i++;
        }
        while (j < b.length() && isDigit(b.charAt(j))) {
          j++;
        }
        int byValue = compareNumbers(a.substring(startA, i), b.substring(startB, j));
        if (byValue != 0) {
          return byValue;
        }
      } else {
        int byChar = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
        if (byChar != 0) {
          return byChar;
        }
        i++;
        j++;
      }
    }
    int byLength = Integer.compare(a.length() - i, b.length() - j);
    return byLength != 0 ? byLength : a.compareTo(b);
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int compareNumbers(String a, String b) {
    String trimmedA = a.replaceFirst("^0+(?=.)", "");
    String trimmedB = b.replaceFirst("^0+(?=.)", "");
    if (trimmedA.length() != trimmedB.length()) {
      return Integer.compare(trimmedA.length(), trimmedB.length());
    }
    return trimmedA.compareTo(trimmedB);
  }
}
