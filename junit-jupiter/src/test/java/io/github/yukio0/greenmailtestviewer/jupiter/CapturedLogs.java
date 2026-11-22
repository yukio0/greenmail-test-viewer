package io.github.yukio0.greenmailtestviewer.jupiter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

final class CapturedLogs implements AutoCloseable {

  private static final SimpleFormatter FORMATTER = new SimpleFormatter();

  private final Logger logger;
  private final List<LogRecord> records = new CopyOnWriteArrayList<>();
  private final Handler handler =
      new Handler() {
        @Override
        public void publish(LogRecord record) {
          records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  CapturedLogs(Class<?> loggingClass) {
    logger = Logger.getLogger(loggingClass.getName());
    logger.addHandler(handler);
  }

  List<String> warnings() {
    return messages(Level.WARNING);
  }

  List<String> infos() {
    return messages(Level.INFO);
  }

  private List<String> messages(Level level) {
    return records.stream()
        .filter(record -> record.getLevel().equals(level))
        .map(FORMATTER::formatMessage)
        .collect(Collectors.toList());
  }

  @Override
  public void close() {
    logger.removeHandler(handler);
  }
}
