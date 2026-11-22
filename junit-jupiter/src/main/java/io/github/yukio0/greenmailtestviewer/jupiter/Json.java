package io.github.yukio0.greenmailtestviewer.jupiter;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Writes the JSON files of the records without a JSON library, because the extension runs on the
 * user's test classpath.
 */
final class Json {

  private static final String INDENT = "  ";

  private Json() {}

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    writeValue(out, value, 0);
    return out.append('\n').toString();
  }

  private static void writeValue(StringBuilder out, Object value, int depth) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String) {
      writeString(out, (String) value);
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map) {
      writeObject(out, (Map<?, ?>) value, depth);
    } else if (value instanceof Collection) {
      writeArray(out, (Collection<?>) value, depth);
    } else {
      throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }
  }

  private static void writeObject(StringBuilder out, Map<?, ?> object, int depth) {
    if (object.isEmpty()) {
      out.append("{}");
      return;
    }
    out.append("{\n");
    Iterator<? extends Map.Entry<?, ?>> entries = object.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<?, ?> entry = entries.next();
      indent(out, depth + 1);
      writeString(out, String.valueOf(entry.getKey()));
      out.append(": ");
      writeValue(out, entry.getValue(), depth + 1);
      if (entries.hasNext()) {
        out.append(',');
      }
      out.append('\n');
    }
    indent(out, depth);
    out.append('}');
  }

  private static void writeArray(StringBuilder out, Collection<?> array, int depth) {
    boolean multiline = false;
    for (Object element : array) {
      if (element instanceof Map || element instanceof Collection) {
        multiline = true;
        break;
      }
    }
    if (!multiline || array.isEmpty()) {
      out.append('[');
      Iterator<?> elements = array.iterator();
      while (elements.hasNext()) {
        writeValue(out, elements.next(), depth);
        if (elements.hasNext()) {
          out.append(", ");
        }
      }
      out.append(']');
      return;
    }
    out.append("[\n");
    Iterator<?> elements = array.iterator();
    while (elements.hasNext()) {
      indent(out, depth + 1);
      writeValue(out, elements.next(), depth + 1);
      if (elements.hasNext()) {
        out.append(',');
      }
      out.append('\n');
    }
    indent(out, depth);
    out.append(']');
  }

  private static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          // Also escapes the line separators that break JavaScript parsers.
          if (c < 0x20 || c == '\u2028' || c == '\u2029') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  private static void indent(StringBuilder out, int depth) {
    for (int i = 0; i < depth; i++) {
      out.append(INDENT);
    }
  }
}
