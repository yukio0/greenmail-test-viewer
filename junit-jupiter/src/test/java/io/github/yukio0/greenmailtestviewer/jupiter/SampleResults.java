package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.Events;

final class SampleResults {

  private SampleResults() {}

  static void assertPassed(EngineExecutionResults results, boolean abortedToo) {
    List<String> problems = new ArrayList<>();
    addReasons(problems, results.allEvents().failed());
    if (abortedToo) {
      addReasons(problems, results.testEvents().aborted());
    }
    assertThat(problems).as("samples that did not pass").isEmpty();
  }

  private static void addReasons(List<String> problems, Events events) {
    events.stream()
        .forEach(event ->
            problems.add(
                event.getTestDescriptor().getDisplayName()
                    + ": "
                    + event
                        .getPayload(TestExecutionResult.class)
                        .flatMap(TestExecutionResult::getThrowable)
                        .map(String::valueOf)
                        .orElse("no reason given")));
  }
}
