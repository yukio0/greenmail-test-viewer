package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

public final class LifecycleFailureSamples {

  private LifecycleFailureSamples() {}

  @GreenMailTestViewer
  public static class BeforeAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void startAndFail() {
      greenMail.start();
      Mails.send(greenMail, "before all failure");
      throw new IllegalStateException("before all failed");
    }

    @AfterAll
    static void stopMailServer() {
      greenMail.stop();
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class BeforeEachFails {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @BeforeEach
    void sendAndFail() {
      Mails.send(greenMail, "before each failure");
      throw new IllegalStateException("before each failed");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class AfterEachSends {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @AfterEach
    void send() {
      Mails.send(greenMail, "after each mail");
    }

    @Test
    void passes() {}
  }

  @GreenMailTestViewer
  public static class AfterEachFails {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @AfterEach
    void sendAndFail() {
      Mails.send(greenMail, "after each failure");
      throw new IllegalStateException("after each failed");
    }

    @Test
    void passes() {}
  }

  @GreenMailTestViewer
  public static class AfterAllFails {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

    @AfterAll
    static void sendAndFail() {
      Mails.send(greenMail, "after all failure");
      throw new IllegalStateException("after all failed");
    }

    @Test
    void passes() {
      Mails.send(greenMail, "after all test");
    }
  }

  @GreenMailTestViewer
  public static class BeforeAllAborts {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void startAndAbort() {
      greenMail.start();
      Mails.send(greenMail, "before all abort");
      Assumptions.assumeTrue(false, "no mail server here");
    }

    @AfterAll
    static void stopMailServer() {
      greenMail.stop();
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class AfterAllAborts {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

    @AfterAll
    static void abort() {
      Assumptions.assumeTrue(false, "not checked here");
    }

    @Test
    void passes() {}
  }

  @GreenMailTestViewer
  public static class BeforeAllAbortsAndAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void abort() {
      Assumptions.assumeTrue(false, "no mail server here");
    }

    @AfterAll
    static void fail() {
      throw new IllegalStateException("after all failed after the abort");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingAfterAll.class)
  public static class BeforeAllAbortsAndOtherExtensionAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void abort() {
      Assumptions.assumeTrue(false, "no mail server here");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @ExtendWith({AbortingBeforeAll.class, FailingAfterAll.class})
  public static class OtherExtensionBeforeAllAbortsAndAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class BeforeAllAndAfterAllFail {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void failBefore() {
      throw new IllegalStateException("before all failed");
    }

    @AfterAll
    static void failAfter() {
      throw new IllegalStateException("after all failed");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingAfterAll.class)
  public static class BeforeAllAndOtherExtensionAfterAllFail {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void fail() {
      throw new IllegalStateException("before all failed");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingAfterAll.class)
  public static class BeforeAllFailsAfterAllAbortsAndOtherExtensionAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void fail() {
      throw new IllegalStateException("before all failed");
    }

    @AfterAll
    static void abort() {
      Assumptions.assumeTrue(false, "nothing to clean up");
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class BeforeAllFailsWithItsOwnSuppressed {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    static void fail() {
      IllegalStateException failure = new IllegalStateException("before all failed");
      failure.addSuppressed(new IllegalStateException("closing failed"));
      throw failure;
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  public static class AllDisabledAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @AfterAll
    static void fail() {
      throw new IllegalStateException("after all failed");
    }

    @Test
    @Disabled("for the sample")
    void disabled() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingBeforeAll.class)
  public static class OtherExtensionBeforeAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingAfterAll.class)
  public static class OtherExtensionAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void passes() {}
  }

  @GreenMailTestViewer
  @ExtendWith(FailingAfterAll.class)
  public static class AllDisabledOtherExtensionAfterAllFails {

    static final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @Test
    @Disabled("for the sample")
    void disabled() {}
  }

  @GreenMailTestViewer
  public static class NestedBeforeAllFails {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void outerPasses() {}

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    public class Inner {

      @BeforeAll
      void fail() {
        throw new IllegalStateException("nested before all failed");
      }

      @Test
      void neverRuns() {}
    }
  }

  @GreenMailTestViewer
  public static class NestedBeforeAllAndOuterAfterAllFail {

    @RegisterExtension
    static final GreenMailExtension greenMail =
        new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @AfterAll
    static void fail() {
      throw new IllegalStateException("outer after all failed");
    }

    @Test
    void outerPasses() {}

    @Nested
    @TestInstance(Lifecycle.PER_CLASS)
    public class Inner {

      @BeforeAll
      void fail() {
        throw new IllegalStateException("nested before all failed");
      }

      @Test
      void neverRuns() {}
    }
  }

  @GreenMailTestViewer
  public static class CreatedInBeforeEachFails {

    GreenMail greenMail;

    @BeforeEach
    void createSendAndFail() {
      greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
      greenMail.start();
      Mails.send(greenMail, "created in before each");
      throw new IllegalStateException("before each failed");
    }

    @AfterEach
    void stopMailServer() {
      if (greenMail != null) {
        greenMail.stop();
      }
    }

    @Test
    void neverRuns() {}
  }

  @GreenMailTestViewer
  @TestInstance(Lifecycle.PER_CLASS)
  public static class PerClassInstanceBeforeAllFails {

    final GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    @BeforeAll
    void startSendAndFail() {
      greenMail.start();
      Mails.send(greenMail, "per class instance before all");
      throw new IllegalStateException("before all failed");
    }

    @AfterAll
    void stopMailServer() {
      greenMail.stop();
    }

    @Test
    void neverRuns() {}
  }

  public static class FailingBeforeAll implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
      throw new IllegalStateException("other extension before all failed");
    }
  }

  public static class AbortingBeforeAll implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
      Assumptions.assumeTrue(false, "other extension found no mail server");
    }
  }

  public static class FailingAfterAll implements AfterAllCallback {
    @Override
    public void afterAll(ExtensionContext context) {
      throw new IllegalStateException("other extension after all failed");
    }
  }
}
