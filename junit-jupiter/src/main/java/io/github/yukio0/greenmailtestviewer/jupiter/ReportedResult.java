package io.github.yukio0.greenmailtestviewer.jupiter;

import org.junit.jupiter.api.extension.ExtensionContext;

final class ReportedResult {
  final ExtensionContext context;

  final MailOwner test;

  final String result;
  final Throwable error;

  ReportedResult(ExtensionContext context, MailOwner test, String result, Throwable error) {
    this.context = context;
    this.test = test;
    this.result = result;
    this.error = error;
  }
}
