package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewerExtension;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public final class ConstraintSamples {

  private ConstraintSamples() {}

  @GreenMailTestViewer
  @Execution(ExecutionMode.CONCURRENT)
  public static class Parallel {

    public static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static final CyclicBarrier BOTH_SENT = new CyclicBarrier(2);

    @Test
    void first() throws Exception {
      Mails.send(GREEN_MAIL, "parallel first");
      BOTH_SENT.await(10, TimeUnit.SECONDS);
    }

    @Test
    void second() throws Exception {
      Mails.send(GREEN_MAIL, "parallel second");
      BOTH_SENT.await(10, TimeUnit.SECONDS);
    }
  }

  @GreenMailTestViewer
  @Execution(ExecutionMode.CONCURRENT)
  public static class ParallelWithOwnGreenMail {

    static final CyclicBarrier BOTH_SENT = new CyclicBarrier(2);

    @RegisterExtension
    final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void first() throws Exception {
      Mails.send(greenMail, "own greenmail first");
      BOTH_SENT.await(10, TimeUnit.SECONDS);
    }

    @Test
    void second() throws Exception {
      Mails.send(greenMail, "own greenmail second");
      BOTH_SENT.await(10, TimeUnit.SECONDS);
    }
  }

  @GreenMailTestViewer
  public static class AsyncWithoutWait {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    public static Thread sender;

    @Test
    void sendsLater() {
      sender = sendAfter(greenMail, 300, "async without wait");
    }
  }

  @GreenMailTestViewer(asyncWaitMillis = 2000)
  public static class AsyncWithWait {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    public static Thread sender;

    @Test
    void sendsLater() {
      sender = sendAfter(greenMail, 300, "async with wait");
    }
  }

  @GreenMailTestViewer
  @Disabled("for the sample")
  public static class DisabledClass {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void neverRuns() {
      Mails.send(greenMail, "disabled class");
    }
  }

  @GreenMailTestViewer
  public static class SharedGreenMailAssignedInBeforeEach {

    GreenMail greenMail;

    @BeforeEach
    void assign() {
      greenMail = SharedGreenMail.GREEN_MAIL;
    }

    @Test
    void sends() {
      Mails.send(greenMail, "assigned in before each");
    }
  }

  @GreenMailTestViewer
  public static class ContainerFailures {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @TestFactory
    Stream<DynamicNode> group() {
      DynamicTest first =
          DynamicTest.dynamicTest("first", () -> Mails.send(greenMail, "first of the group"));
      Stream<DynamicTest> children =
          Stream.of(1, 2)
              .map(number -> {
                if (number == 2) {
                  throw new IllegalStateException("cannot make the second");
                }
                return first;
              });
      return Stream.of(DynamicContainer.dynamicContainer("group", children));
    }

    static Stream<String> brokenSource() {
      throw new IllegalStateException("cannot provide the arguments");
    }

    @ParameterizedTest
    @MethodSource("brokenSource")
    void parameterized(String value) {
      Mails.send(greenMail, "never sent " + value);
    }
  }

  static Extension resultChangingInterceptor() {
    InvocationInterceptor unchanged = new InvocationInterceptor() {};
    return (Extension)
        Proxy.newProxyInstance(
            ConstraintSamples.class.getClassLoader(),
            new Class<?>[] {InvocationInterceptor.class},
            (proxy, method, arguments) -> {
              switch (method.getName()) {
                case "equals":
                  return proxy == arguments[0];
                case "hashCode":
                  return System.identityHashCode(proxy);
                case "toString":
                  return "result changing interceptor";
                default:
                  break;
              }
              if (!method.getName().equals("interceptDynamicTest")) {
                try {
                  return method.invoke(unchanged, arguments);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              }
              Invocation<?> invocation = (Invocation<?>) arguments[0];
              ExtensionContext context = (ExtensionContext) arguments[arguments.length - 1];
              try {
                invocation.proceed();
              } catch (AssertionError e) {
                return null;
              }
              if (context.getDisplayName().equals("passes")) {
                throw new AssertionError("failed by another extension");
              }
              return null;
            });
  }

  abstract static class ChangedDynamicTests {

    @RegisterExtension
    @Order(3)
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @TestFactory
    Stream<DynamicTest> changed() {
      return Stream.of(
          DynamicTest.dynamicTest("passes", () -> Mails.send(greenMail, "passes")),
          DynamicTest.dynamicTest(
              "fails",
              () -> {
                throw new AssertionError("fails by itself");
              }));
    }
  }

  public static class ResultsChangedAroundTheViewer extends ChangedDynamicTests {

    @RegisterExtension
    @Order(1)
    static final Extension changer = resultChangingInterceptor();

    @RegisterExtension
    @Order(2)
    static final GreenMailTestViewerExtension viewer = new GreenMailTestViewerExtension();
  }

  public static class ResultsChangedInsideTheViewer extends ChangedDynamicTests {

    @RegisterExtension
    @Order(1)
    static final GreenMailTestViewerExtension viewer = new GreenMailTestViewerExtension();

    @RegisterExtension
    @Order(2)
    static final Extension changer = resultChangingInterceptor();
  }

  private static Thread sendAfter(GreenMailOperations greenMail, long millis, String subject) {
    Thread thread =
        new Thread(() -> {
          try {
            Thread.sleep(millis);
            Mails.send(greenMail, subject);
          } catch (InterruptedException | RuntimeException e) {
          }
        });
    thread.start();
    return thread;
  }
}
