package io.github.yukio0.greenmailtestviewer.jupiter;

import com.icegreen.greenmail.base.GreenMailOperations;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailFinder.FoundGreenMail;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.TestAbortedException;

/**
 * JUnit Jupiter extension that records the emails GreenMail receives, with the test that was
 * running when they arrived. Use it through {@link GreenMailTestViewer}.
 *
 * <p>It does not start or stop GreenMail. It collects the new emails at points of the test
 * lifecycle, such as right after each test method and each {@code @AfterEach} method, before other
 * extensions stop GreenMail. Recording never makes a test fail: problems are logged.
 */
public final class GreenMailTestViewerExtension
    implements BeforeAllCallback,
        BeforeEachCallback,
        BeforeTestExecutionCallback,
        AfterTestExecutionCallback,
        AfterEachCallback,
        AfterAllCallback,
        TestWatcher,
        LifecycleMethodExecutionExceptionHandler,
        InvocationInterceptor {

  private static final Logger LOGGER =
      System.getLogger(GreenMailTestViewerExtension.class.getName());
  private static final Namespace NAMESPACE = Namespace.create(GreenMailTestViewerExtension.class);
  private static final MailCollector COLLECTOR = new MailCollector();
  private static final ActiveTests ACTIVE_TESTS = new ActiveTests();

  // Taking the mails from a GreenMail instance and deciding their owner is one atomic step, as are
  // starting and finishing tests; otherwise a test finishing in between could get the mails of
  // another test.
  private static final Object OWNERSHIP = new Object();

  // Compared by identity, as GreenMail does not override equals.
  private static final Set<GreenMailOperations> WATCHED =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  /** Creates the extension. JUnit calls this; register the extension with the annotation. */
  public GreenMailTestViewerExtension() {}

  /**
   * Registers a GreenMail instance that is not held in a field of the test class. It is used by all
   * test classes with the extension. The extension holds only a weak reference to it, so
   * registering it does not prevent garbage collection.
   *
   * @param greenMail the GreenMail whose emails are recorded
   */
  public static void watch(GreenMailOperations greenMail) {
    WATCHED.add(Objects.requireNonNull(greenMail, "greenMail"));
  }

  static void unwatchAll() {
    WATCHED.clear();
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    execution(context);
    record(
        context,
        () -> {
          List<GreenMailOperations> greenMails = classGreenMails(context);
          MailOwner owner =
              enclosingClassContext(context)
                  .map(MailOwner::outsideTest)
                  .orElse(MailOwner.outsideClass());
          collect(context, greenMails, owner);
          startRecording(greenMails);
        });
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    record(
        context,
        () -> {
          List<GreenMailOperations> greenMails = testGreenMails(context);
          collect(context, greenMails, MailOwner.outsideTest(classContext(context)));
          startRecording(greenMails);
          startTest(context, greenMails);
        });
  }

  @Override
  public void beforeTestExecution(ExtensionContext context) {
    // GreenMail started by @BeforeEach or by other extensions is found only now. Recording starts
    // here at the latest, so that the mails of the test method can be distinguished by their SMTP
    // transaction.
    record(
        context,
        () -> {
          List<GreenMailOperations> greenMails = testGreenMails(context);
          startRecording(greenMails);
          startTest(context, greenMails);
        });
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    waitForAsynchronousMails(context);
    record(context, () -> collectForTest(context));
  }

  @Override
  public void interceptAfterEachMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    try {
      invocation.proceed();
    } finally {
      // Collects before other extensions stop GreenMail, which discards its mails.
      record(extensionContext, () -> collectForTest(extensionContext));
    }
  }

  @Override
  public void interceptDynamicTest(
      Invocation<Void> invocation,
      DynamicTestInvocationContext invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    recordDynamicTest(invocation, extensionContext);
  }

  /**
   * Records a dynamic test with JUnit 5.6 and 5.7, which call this method rather than the one
   * above.
   *
   * @deprecated JUnit calls it, not users. It is kept for JUnit 5.6 and 5.7.
   */
  @Override
  @Deprecated
  public void interceptDynamicTest(Invocation<Void> invocation, ExtensionContext extensionContext)
      throws Throwable {
    recordDynamicTest(invocation, extensionContext);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    record(
        context,
        () -> {
          List<GreenMailOperations> greenMails = testGreenMails(context);
          try {
            collect(context, greenMails, MailOwner.test(context));
          } finally {
            // Finishes the test even if a mail could not be written; a test left running would make
            // later mails ambiguous.
            finishTest(context, greenMails);
          }
        });
    // JUnit does not report a @TestFactory method to test watchers, so its own failure is recorded
    // here.
    if (isTestFactory(context)) {
      context
          .getExecutionException()
          .ifPresent(failure -> writeTestResult(context, resultOf(failure), failure));
    }
  }

  @Override
  public void interceptAfterAllMethod(
      Invocation<Void> invocation,
      ReflectiveInvocationContext<Method> invocationContext,
      ExtensionContext extensionContext)
      throws Throwable {
    try {
      invocation.proceed();
    } finally {
      record(extensionContext, () -> collectOutsideTests(extensionContext));
    }
  }

  @Override
  public void afterAll(ExtensionContext context) {
    record(context, () -> collectOutsideTests(context));
    // Recorded separately from the mails, so that a mail that cannot be written does not cause the
    // class failure to be lost.
    record(
        context,
        () -> {
          if (usesGreenMail(context)) {
            writeKeptResults(context);
          }
        });
    record(
        context,
        () -> {
          ClassState state = classState(context);
          Class<?> testClass = context.getRequiredTestClass();
          Optional<Throwable> failure = context.getExecutionException();
          if (failure.isPresent() && (usesGreenMail(context) || isAnnotated(testClass))) {
            for (Map.Entry<String, Throwable> stage : state.failures(failure.get()).entrySet()) {
              writer(context)
                  .writeClassFailure(
                      MailOwner.outsideTest(context), stage.getKey(), stage.getValue());
            }
          }
          if (state.isEmpty() && WATCHED.isEmpty() && isAnnotated(testClass)) {
            LOGGER.log(
                Level.WARNING,
                "No GreenMail instance was found in the fields of {0}. If it is held elsewhere,"
                    + " register it with GreenMailTestViewerExtension.watch(greenMail).",
                testClass.getName());
          }
        });
  }

  @Override
  public void handleBeforeAllMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    record(
        context,
        () -> {
          classState(context).failedAt(ClassState.BEFORE_ALL, throwable);
          collectOutsideTests(context);
        });
    throw throwable;
  }

  @Override
  public void handleBeforeEachMethodExecutionException(
      ExtensionContext context, Throwable throwable) throws Throwable {
    // The test does not run, and other extensions may stop GreenMail before afterEach.
    record(context, () -> collectForTest(context));
    throw throwable;
  }

  @Override
  public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    record(context, () -> collectForTest(context));
    throw throwable;
  }

  @Override
  public void handleAfterAllMethodExecutionException(ExtensionContext context, Throwable throwable)
      throws Throwable {
    record(
        context,
        () -> {
          classState(context).failedAt(ClassState.AFTER_ALL, throwable);
          collectOutsideTests(context);
        });
    throw throwable;
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    writeTestResult(context, "passed", null);
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    writeTestResult(context, "failed", cause);
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    writeTestResult(context, "aborted", cause);
  }

  @Override
  public void testDisabled(ExtensionContext context, Optional<String> reason) {
    writeTestResult(context, "disabled", null);
  }

  private static void recordDynamicTest(
      Invocation<Void> invocation, ExtensionContext dynamicContext) throws Throwable {
    Optional<ExtensionContext> found = methodContext(dynamicContext);
    if (!found.isPresent()) {
      invocation.proceed();
      return;
    }
    ExtensionContext factoryContext = found.get();
    MailOwner test = MailOwner.dynamicTest(dynamicContext, factoryContext);
    record(
        dynamicContext,
        () -> {
          List<GreenMailOperations> greenMails = testGreenMails(factoryContext);
          collect(factoryContext, greenMails, MailOwner.test(factoryContext));
          startRecording(greenMails);
          startTest(dynamicContext, greenMails, test);
        });
    Throwable failure = null;
    try {
      invocation.proceed();
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      waitForAsynchronousMails(factoryContext);
      record(
          dynamicContext,
          () -> {
            List<GreenMailOperations> greenMails = testGreenMails(factoryContext);
            try {
              collect(dynamicContext, greenMails, test);
            } finally {
              finishTest(dynamicContext, greenMails);
            }
          });
      writeTestResult(dynamicContext, () -> test, resultOf(failure), failure);
    }
  }

  private static String resultOf(Throwable failure) {
    if (failure == null) {
      return "passed";
    }
    return failure instanceof TestAbortedException ? "aborted" : "failed";
  }

  private static boolean isTestFactory(ExtensionContext context) {
    return context
        .getTestMethod()
        .map(method -> AnnotationSupport.isAnnotated(method, TestFactory.class))
        .orElse(false);
  }

  private static void writeTestResult(ExtensionContext context, String result, Throwable error) {
    writeTestResult(context, () -> MailOwner.test(context), result, error);
  }

  private static void writeTestResult(
      ExtensionContext context, Supplier<MailOwner> test, String result, Throwable error) {
    record(
        context,
        () -> {
          ExtensionContext classContext = classContext(context);
          for (ClassState state : classStates(classContext)) {
            state.testReported();
          }
          ReportedResult reported = new ReportedResult(context, test.get(), result, error);
          if (!usesGreenMail(classContext)) {
            classState(classContext).keepResult(reported);
            return;
          }
          writeKeptResults(classContext);
          writeResult(reported);
        });
  }

  /** Writes the results that were held back until GreenMail was found in the class. */
  private static void writeKeptResults(ExtensionContext classContext) {
    for (ReportedResult kept : classState(classContext).takeKeptResults()) {
      record(kept.context, () -> writeResult(kept));
    }
  }

  private static void writeResult(ReportedResult reported) throws IOException {
    ExtensionContext context = reported.context;
    writer(context).writeTestResult(reported.test, reported.result, reported.error);
    if ("failed".equals(reported.result)) {
      logViewerLink(context);
    }
  }

  /**
   * Whether GreenMail was found in the fields of the class, mails were collected for it, or it is
   * annotated and an instance was registered with {@link #watch}. Results of other classes are not
   * written, so that enabling the extension for every class does not fill the viewer.
   */
  private static boolean usesGreenMail(ExtensionContext classContext) {
    ClassState state = classState(classContext);
    return !state.isEmpty()
        || state.hasMails()
        || (!WATCHED.isEmpty() && isAnnotated(classContext.getRequiredTestClass()));
  }

  private static List<GreenMailOperations> classGreenMails(ExtensionContext classContext) {
    addFound(classContext, GreenMailFinder.findInFields(classContext));
    return withWatched(classState(classContext).greenMails());
  }

  private static List<GreenMailOperations> testGreenMails(ExtensionContext methodContext) {
    List<FoundGreenMail> found = GreenMailFinder.findInFields(methodContext);
    addFound(methodContext, found);
    TestState state =
        methodContext
            .getStore(NAMESPACE)
            .getOrComputeIfAbsent(TestState.class, key -> new TestState(), TestState.class);
    return withWatched(state.add(found));
  }

  private static List<GreenMailOperations> withWatched(List<GreenMailOperations> found) {
    List<GreenMailOperations> all = new ArrayList<>(found);
    synchronized (WATCHED) {
      for (GreenMailOperations watched : WATCHED) {
        if (!containsInstance(all, watched)) {
          all.add(watched);
        }
      }
    }
    return all;
  }

  private static boolean containsInstance(List<GreenMailOperations> list, Object instance) {
    for (Object element : list) {
      if (element == instance) {
        return true;
      }
    }
    return false;
  }

  private static void collectForTest(ExtensionContext methodContext) throws IOException {
    collect(methodContext, testGreenMails(methodContext), MailOwner.test(methodContext));
  }

  private static void collectOutsideTests(ExtensionContext classContext) throws IOException {
    collect(classContext, classGreenMails(classContext), MailOwner.outsideTest(classContext));
  }

  /**
   * Writes the new mails of the given GreenMail instances. A mail of a GreenMail that other tests
   * use at the same time is recorded as ambiguous. The run directory is created on the first mail.
   */
  private static void collect(
      ExtensionContext context, List<GreenMailOperations> greenMails, MailOwner owner)
      throws IOException {
    RunWriter writer = null;
    String testKey = owner.phase() == MailOwner.Phase.TEST ? runningTestKey(context) : null;
    for (GreenMailOperations greenMail : greenMails) {
      OwnedMails taken = takeNewMails(greenMail, owner, testKey);
      if (taken == null) {
        continue;
      }
      // Written outside the lock, so that tests running in parallel do not wait for it.
      if (owner.phase() != MailOwner.Phase.OUTSIDE_CLASS) {
        for (ClassState state : classStates(context)) {
          state.mailsCollected();
        }
      }
      if (writer == null) {
        writer = writer(context);
      }
      for (ReceivedMail mail : taken.mails) {
        try {
          writer.writeMail(taken.owner, mail);
        } catch (IOException | RuntimeException e) {
          LOGGER.log(
              Level.WARNING,
              "Could not record an email from GreenMail for "
                  + context.getUniqueId()
                  + ". The other emails are still recorded.",
              e);
        }
      }
    }
  }

  /**
   * Takes the new mails of a GreenMail instance and decides their owner, or returns {@code null}
   * when there are none. Mails still being delivered are taken later, without waiting for them.
   */
  static OwnedMails takeNewMails(
      GreenMailOperations greenMail, MailOwner intended, String intendedKey) {
    synchronized (OWNERSHIP) {
      List<ReceivedMail> mails = COLLECTOR.collectNew(greenMail);
      if (mails.isEmpty()) {
        return null;
      }
      return new OwnedMails(mails, ACTIVE_TESTS.ownerOf(greenMail, intended, intendedKey));
    }
  }

  static final class OwnedMails {
    final List<ReceivedMail> mails;
    final MailOwner owner;

    OwnedMails(List<ReceivedMail> mails, MailOwner owner) {
      this.mails = mails;
      this.owner = owner;
    }
  }

  private static void startRecording(List<GreenMailOperations> greenMails) {
    for (GreenMailOperations greenMail : greenMails) {
      COLLECTOR.startRecording(greenMail);
    }
  }

  private static void startTest(
      ExtensionContext methodContext, List<GreenMailOperations> greenMails) {
    startTest(methodContext, greenMails, MailOwner.test(methodContext));
  }

  private static void startTest(
      ExtensionContext context, List<GreenMailOperations> greenMails, MailOwner test) {
    String key = runningTestKey(context);
    synchronized (OWNERSHIP) {
      for (GreenMailOperations greenMail : greenMails) {
        ACTIVE_TESTS.start(greenMail, key, test);
      }
    }
  }

  private static void finishTest(
      ExtensionContext methodContext, List<GreenMailOperations> greenMails) {
    String key = runningTestKey(methodContext);
    synchronized (OWNERSHIP) {
      for (GreenMailOperations greenMail : greenMails) {
        ACTIVE_TESTS.finish(greenMail, key);
      }
    }
  }

  private static String runningTestKey(ExtensionContext methodContext) {
    return execution(methodContext).testKey(methodContext.getUniqueId());
  }

  private static JUnitExecution execution(ExtensionContext context) {
    return context
        .getRoot()
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent(
            JUnitExecution.class, key -> new JUnitExecution(ACTIVE_TESTS), JUnitExecution.class);
  }

  private static void waitForAsynchronousMails(ExtensionContext context) {
    long millis =
        findAnnotation(context.getRequiredTestClass())
            .map(GreenMailTestViewer::asyncWaitMillis)
            .orElse(0L);
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static RunWriter writer(ExtensionContext context) throws IOException {
    Path outputDirectory =
        OutputDirectory.resolve(
            context.getConfigurationParameter(OutputDirectory.CONFIGURATION_PARAMETER),
            Path.of("").toAbsolutePath());
    int keepRuns =
        OldRuns.keepRuns(
            context.getConfigurationParameter(OldRuns.KEEP_RUNS_PARAMETER).orElse(null));
    return execution(context).writer(outputDirectory, keepRuns);
  }

  private static void logViewerLink(ExtensionContext context) {
    ViewerLink.forTest(
            context.getConfigurationParameter(ViewerLink.CONFIGURATION_PARAMETER),
            context.getUniqueId())
        .ifPresent(link ->
            LOGGER.log(
                Level.INFO,
                "{0} failed. See its emails in GreenMail Test Viewer: {1}",
                context.getDisplayName(),
                link));
  }

  /**
   * Runs a recording step. Failures are logged and never make the test fail. A LinkageError also
   * stops recording for the rest of the execution.
   */
  private static void record(ExtensionContext context, RecordingStep step) {
    JUnitExecution execution = execution(context);
    if (execution.isRecordingStopped()) {
      return;
    }
    try {
      step.run();
    } catch (IOException | RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "Could not record the emails from GreenMail for " + context.getUniqueId(),
          e);
    } catch (LinkageError e) {
      if (execution.stopRecording()) {
        LOGGER.log(
            Level.WARNING,
            "Stopped recording the emails from GreenMail in this JUnit execution: a class or method"
                + " that the extension uses is missing or has changed, probably because this"
                + " version of GreenMail or JUnit is not supported. The tests run as usual.",
            e);
      }
    }
  }

  // Keyed by the class: a @Nested class would otherwise find the state of its enclosing class.
  private static ClassState classState(ExtensionContext classContext) {
    return classContext
        .getStore(NAMESPACE)
        .getOrComputeIfAbsent(
            "class:" + classContext.getUniqueId(), key -> new ClassState(), ClassState.class);
  }

  private static List<ClassState> classStates(ExtensionContext context) {
    List<ClassState> states = new ArrayList<>();
    Optional<ExtensionContext> current = Optional.of(classContext(context));
    while (current.isPresent() && current.get().getTestClass().isPresent()) {
      states.add(classState(current.get()));
      current = current.get().getParent();
    }
    return states;
  }

  private static void addFound(ExtensionContext context, List<FoundGreenMail> found) {
    List<ClassState> states = classStates(context);
    for (int i = 0; i < states.size(); i++) {
      states.get(i).add(found, i == 0);
    }
  }

  private static ExtensionContext classContext(ExtensionContext context) {
    ExtensionContext current = context;
    while ((current.getTestMethod().isPresent() || !current.getTestClass().isPresent())
        && current.getParent().isPresent()) {
      current = current.getParent().get();
    }
    return current;
  }

  private static Optional<ExtensionContext> methodContext(ExtensionContext context) {
    Optional<ExtensionContext> current = Optional.of(context);
    while (current.isPresent()
        && !current.get().getTestMethod().isPresent()
        && !current.get().getTestClass().isPresent()) {
      current = current.get().getParent();
    }
    return current.filter(found -> found.getTestMethod().isPresent());
  }

  private static Optional<ExtensionContext> enclosingClassContext(ExtensionContext classContext) {
    return classContext.getParent().filter(parent -> parent.getTestClass().isPresent());
  }

  private static boolean isAnnotated(Class<?> testClass) {
    return findAnnotation(testClass).isPresent();
  }

  private static Optional<GreenMailTestViewer> findAnnotation(Class<?> testClass) {
    for (Class<?> type = testClass; type != null; type = type.getEnclosingClass()) {
      Optional<GreenMailTestViewer> annotation =
          AnnotationSupport.findAnnotation(type, GreenMailTestViewer.class);
      if (annotation.isPresent()) {
        return annotation;
      }
    }
    return Optional.empty();
  }

  @FunctionalInterface
  private interface RecordingStep {
    void run() throws IOException;
  }
}
