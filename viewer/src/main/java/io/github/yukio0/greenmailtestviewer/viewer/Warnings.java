package io.github.yukio0.greenmailtestviewer.viewer;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.text.MessageFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Logs each warning once, as the files are read again every few seconds. */
final class Warnings {

  private static final Logger LOGGER = System.getLogger(Warnings.class.getName());

  private final Set<String> logged = ConcurrentHashMap.newKeySet();

  String warnOnce(String key, String message, Object... parameters) {
    String text = parameters.length == 0 ? message : MessageFormat.format(message, parameters);
    if (logged.add(key)) {
      LOGGER.log(Level.WARNING, text);
    }
    return text;
  }
}
