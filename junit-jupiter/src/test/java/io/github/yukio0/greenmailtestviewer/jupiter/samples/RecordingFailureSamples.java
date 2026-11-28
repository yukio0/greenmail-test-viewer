package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

public final class RecordingFailureSamples {

  private static final String OUTPUT_DIRECTORY = "greenmail.testviewer.output-dir";

  private RecordingFailureSamples() {}

  @GreenMailTestViewer
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  public static class FailsInAfterEach {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @RegisterExtension
    static final BlockOutputAfterFirstTest blockOutput = new BlockOutputAfterFirstTest();

    @BeforeAll
    static void startMailServer() {
      greenMail.start();
    }

    @AfterAll
    static void stopMailServer() {
      greenMail.stop();
    }

    @Test
    @Order(1)
    void first() {}

    @Test
    @Order(2)
    void second() {
      Mails.send(greenMail, "second mail");
    }

    static class BlockOutputAfterFirstTest implements AfterEachCallback, BeforeEachCallback {

      @Override
      public void afterEach(ExtensionContext context) throws IOException {
        if (context.getRequiredTestMethod().getName().equals("first")) {
          Files.createFile(outputDirectory(context));
          Mails.send(greenMail, "first mail");
        }
      }

      @Override
      public void beforeEach(ExtensionContext context) throws IOException {
        Files.deleteIfExists(outputDirectory(context));
      }
    }
  }

  @ExtendWith(FailsInAfterAll.StopGreenMail.class)
  @GreenMailTestViewer
  public static class FailsInAfterAll {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @RegisterExtension static final BreakMessagesAndSend breakMessages = new BreakMessagesAndSend();

    @BeforeAll
    static void startAndFail() {
      greenMail.start();
      Mails.send(greenMail, "setup mail");
      throw new IllegalStateException("before all failed");
    }

    @Test
    void neverRuns() {}

    static class BreakMessagesAndSend implements AfterAllCallback {

      @Override
      public void afterAll(ExtensionContext context) throws IOException {
        Path messages = runDirectory(outputDirectory(context)).resolve("messages");
        deleteRecursively(messages);
        Files.createFile(messages);
        Mails.send(greenMail, "late mail");
      }
    }

    static class StopGreenMail implements AfterAllCallback {

      @Override
      public void afterAll(ExtensionContext context) {
        greenMail.stop();
      }
    }
  }

  @GreenMailTestViewer
  public static class FailsForOneOfSeveralMails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @RegisterExtension static final BlockNextMailFile blockNextMailFile = new BlockNextMailFile();

    @BeforeAll
    static void startAndSend() {
      greenMail.start();
      Mails.send(greenMail, "setup mail");
    }

    @AfterAll
    static void stopMailServer() {
      greenMail.stop();
    }

    @Test
    void sendsTwoMails() {
      Mails.send(greenMail, "first of two");
      Mails.send(greenMail, "second of two");
    }

    static class BlockNextMailFile implements BeforeTestExecutionCallback {

      @Override
      public void beforeTestExecution(ExtensionContext context) throws IOException {
        Path messages = runDirectory(outputDirectory(context)).resolve("messages");
        Path next = Files.createDirectory(messages.resolve("000002.eml"));
        Files.createFile(next.resolve("blocker"));
      }
    }
  }

  @GreenMailTestViewer
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  public static class FailsForOneOfTheKeptResults {

    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @RegisterExtension
    static final BlockFirstTestRecord blockFirstTestRecord = new BlockFirstTestRecord();

    @Test
    @Order(1)
    @Disabled("for the sample")
    void disabledFirst() {}

    @Test
    @Order(2)
    @Disabled("for the sample")
    void disabledSecond() {}

    @Test
    @Order(3)
    void sends() {
      Mails.send(greenMail, "with kept results");
    }

    @Test
    @Order(4)
    void passesLater() {}

    static class BlockFirstTestRecord implements AfterEachCallback {
      private boolean blocked;

      @Override
      public void afterEach(ExtensionContext context) throws IOException {
        if (blocked) {
          return;
        }
        blocked = true;
        Path tests = runDirectory(outputDirectory(context)).resolve("tests");
        Path first = Files.createDirectory(tests.resolve("000001.json"));
        Files.createFile(first.resolve("blocker"));
      }
    }
  }

  /** GreenMail of a version that lacks the methods the extension calls. */
  @GreenMailTestViewer
  public static class IncompatibleGreenMail {

    static final GreenMailOperations greenMail = lackingEveryMethod();

    @Test
    void first() {}

    @Test
    void second() {}

    private static GreenMailOperations lackingEveryMethod() {
      return (GreenMailOperations)
          Proxy.newProxyInstance(
              GreenMailOperations.class.getClassLoader(),
              new Class<?>[] {GreenMailOperations.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "equals":
                    return proxy == args[0];
                  case "hashCode":
                    return System.identityHashCode(proxy);
                  case "toString":
                    return "incompatible GreenMail";
                  default:
                    throw new NoSuchMethodError("GreenMailOperations." + method.getName());
                }
              });
    }
  }

  /** GreenMail that lacks the methods the extension calls once {@link #broken} is set. */
  public static final class BreakingGreenMail {

    public static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    public static volatile boolean broken;

    public static final GreenMailOperations OPERATIONS =
        (GreenMailOperations)
            Proxy.newProxyInstance(
                GreenMailOperations.class.getClassLoader(),
                new Class<?>[] {GreenMailOperations.class},
                (proxy, method, args) -> {
                  if (method.getDeclaringClass() == Object.class) {
                    switch (method.getName()) {
                      case "equals":
                        return proxy == args[0];
                      case "hashCode":
                        return System.identityHashCode(proxy);
                      default:
                        return "breaking GreenMail";
                    }
                  }
                  if (broken) {
                    throw new NoSuchMethodError("GreenMailOperations." + method.getName());
                  }
                  try {
                    return method.invoke(GREEN_MAIL, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause();
                  }
                });

    private BreakingGreenMail() {}
  }

  @GreenMailTestViewer
  public static class StopsRecordingDuringATest {

    static final GreenMailOperations greenMail = BreakingGreenMail.OPERATIONS;

    @Test
    void sendsAndBreaks() {
      Mails.send(BreakingGreenMail.GREEN_MAIL, "before the break");
      BreakingGreenMail.broken = true;
    }
  }

  @GreenMailTestViewer
  public static class UsesTheSameGreenMailLater {

    static final GreenMailOperations greenMail = BreakingGreenMail.OPERATIONS;

    @Test
    void sends() {
      Mails.send(BreakingGreenMail.GREEN_MAIL, "after the break");
    }
  }

  private static Path outputDirectory(ExtensionContext context) {
    return Path.of(context.getConfigurationParameter(OUTPUT_DIRECTORY).orElseThrow());
  }

  private static Path runDirectory(Path outputDirectory) throws IOException {
    try (Stream<Path> entries = Files.list(outputDirectory.resolve("runs"))) {
      List<Path> runs = entries.filter(Files::isDirectory).collect(Collectors.toList());
      if (runs.size() != 1) {
        throw new IllegalStateException("Expected one run directory: " + runs);
      }
      return runs.get(0);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.delete(path);
      }
    }
  }
}
