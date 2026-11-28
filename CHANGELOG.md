# Changelog

The notable changes in each version of GreenMail Test Viewer.

## Unreleased

### Extension

- After recording stopped during a test, a later JUnit execution in the same JVM no longer
  records the emails of the same GreenMail instance as `(Ambiguous)`.

## 0.1.0 - 2026-11-23

The first release.

| What | Where | Name |
| --- | --- | --- |
| JUnit Jupiter extension | Maven Central | `io.github.yukio0:greenmail-test-viewer-junit-jupiter` |
| Viewer | Maven Central | `io.github.yukio0:greenmail-test-viewer` |
| Viewer with all its dependencies | GitHub Releases | `greenmail-test-viewer.jar` |
| Gradle plugin that starts the viewer | Gradle Plugin Portal | `io.github.yukio0.greenmail-test-viewer` |

### Extension

- `@GreenMailTestViewer` records the emails that GreenMail receives in each test, with the test
  class and method that sent them.
- Finds GreenMail instances in the fields of the test class: `GreenMailExtension`, a `GreenMail`
  that the test class starts, or a Spring bean. Other instances can be registered with
  `GreenMailTestViewerExtension.watch`.
- Ties emails to tests in `@Nested` classes, `@ParameterizedTest`, `@RepeatedTest`,
  `@ParameterizedClass` and the dynamic tests of `@TestFactory`. Emails sent outside tests, such as
  in `@BeforeAll`, are recorded separately.
- Records emails received over SMTP and SMTPS.
- Logs a link to the emails of each failed test in the viewer.
- Never makes a test fail: problems while recording are logged as warnings.
- Settings: `greenmail.testviewer.output-dir`, `greenmail.testviewer.keep-runs`,
  `greenmail.testviewer.viewer-url`, and `@GreenMailTestViewer(asyncWaitMillis = ...)` for emails
  sent asynchronously.

### Viewer

- Shows the records of all modules below the directory where it starts, in a browser: a tree of
  test classes and methods with their results, the emails of the selected item, and the selected
  email.
- Shows the latest result of each test, or a single run. **Failed only** shows only the failed
  tests, and **Search** searches subjects, addresses, bodies and test names.
- Shows an email as an HTML preview at desktop, tablet or mobile width, in a frame that runs no
  scripts, and as its HTML source, text, headers, raw message and links, with its attachments. The
  email can be downloaded as an `.eml` file.
- Checks for new records every few seconds.
- Options: `--dir`, `--host`, `--port`, `--allowed-host`, `--open` and
  `--block-remote-css-and-fonts`.

### Gradle plugin

- Adds the `greenmailTestViewer` task, which starts the viewer of the same version, fetched from
  the repositories of the build.

### Requirements

- Java 11 or later
- GreenMail 2.1.x (2.1.0 or later)
- JUnit 5.6 or later
- Gradle 8.0 or later for the Gradle plugin, and Maven 3.6.3 or later for starting the viewer with
  exec-maven-plugin
