package io.github.yukio0.greenmailtestviewer.viewer;

final class TestResult {
  private final String result;
  private final String error;

  TestResult(String result, String error) {
    this.result = result;
    this.error = error;
  }

  String result() {
    return result;
  }

  String error() {
    return error;
  }
}
