package io.github.yukio0.greenmailtestviewer.viewer;

import java.util.Locale;
import java.util.Set;

/**
 * Makes a file name from a mail safe to save: no directories, no characters or device names that
 * Windows refuses, and not too long.
 */
final class AttachmentFilenameSanitizer {

  private static final int MAX_LENGTH = 120;
  private static final Set<String> WINDOWS_RESERVED_NAMES =
      Set.of(
          "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7",
          "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

  String sanitize(String fileName, String attachmentId) {
    String fallback = "attachment-" + (attachmentId.isBlank() ? "file" : attachmentId);
    String normalized =
        fileName == null ? "" : trimDotsAndSpaces(replaceUnsafeCharacters(baseName(fileName)));
    if (normalized.isBlank()) {
      normalized = fallback;
    }
    String result = limitLength(avoidWindowsReservedName(normalized, fallback));
    return result.isBlank() ? fallback : result;
  }

  private static String baseName(String fileName) {
    String path = fileName.replace('\\', '/');
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String replaceUnsafeCharacters(String name) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean unsafe = c < 0x20 || c == 0x7f || "<>:\"|?*".indexOf(c) >= 0;
      result.append(unsafe ? '_' : c);
    }
    return result.toString().replaceAll("_+", "_");
  }

  private static String trimDotsAndSpaces(String name) {
    String trimmed = name.trim();
    int start = 0;
    int end = trimmed.length();
    while (start < end && (trimmed.charAt(start) == '.' || trimmed.charAt(start) == ' ')) {
      start++;
    }
    while (end > start && (trimmed.charAt(end - 1) == '.' || trimmed.charAt(end - 1) == ' ')) {
      end--;
    }
    return trimmed.substring(start, end);
  }

  private static String avoidWindowsReservedName(String name, String fallback) {
    int dot = name.lastIndexOf('.');
    String stem = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
    if (stem.isBlank()) {
      return fallback;
    }
    if (WINDOWS_RESERVED_NAMES.contains(stem)) {
      return "_" + name;
    }
    return name;
  }

  private static String limitLength(String name) {
    if (name.length() <= MAX_LENGTH) {
      return name;
    }
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1);
    if (extension.isBlank() || extension.length() > 16) {
      return start(name, MAX_LENGTH);
    }
    String suffix = "." + extension;
    return start(name, MAX_LENGTH - suffix.length()) + suffix;
  }

  private static String start(String name, int length) {
    int end = Character.isHighSurrogate(name.charAt(length - 1)) ? length - 1 : length;
    return name.substring(0, end);
  }
}
