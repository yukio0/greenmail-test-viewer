package io.github.yukio0.greenmailtestviewer.jupiter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;

/** The test, test class or neither that a recorded mail belongs to. */
final class MailOwner {

  enum Phase {
    TEST("test"),
    OUTSIDE_TEST("outsideTest"),
    OUTSIDE_CLASS("outsideClass"),
    AMBIGUOUS("ambiguous");

    private final String jsonValue;

    Phase(String jsonValue) {
      this.jsonValue = jsonValue;
    }

    String jsonValue() {
      return jsonValue;
    }
  }

  private static final MailOwner OUTSIDE_CLASS =
      new MailOwner(Phase.OUTSIDE_CLASS, null, null, null, null, null, Collections.emptyList());

  private static final String CLASS_TEMPLATE_INVOCATION = "[class-template-invocation:";

  private final Phase phase;
  private final String testClass;
  private final String classDisplayName;
  private final String testMethod;
  private final String displayName;
  private final String uniqueId;
  private final List<MailOwner> candidates;

  private MailOwner(
      Phase phase,
      String testClass,
      String classDisplayName,
      String testMethod,
      String displayName,
      String uniqueId,
      List<MailOwner> candidates) {
    this.phase = phase;
    this.testClass = testClass;
    this.classDisplayName = classDisplayName;
    this.testMethod = testMethod;
    this.displayName = displayName;
    this.uniqueId = uniqueId;
    this.candidates = candidates;
  }

  static MailOwner test(ExtensionContext methodContext) {
    return new MailOwner(
        Phase.TEST,
        methodContext.getRequiredTestClass().getName(),
        classInvocationNames(methodContext),
        methodContext.getRequiredTestMethod().getName(),
        methodContext.getDisplayName(),
        methodContext.getUniqueId(),
        Collections.emptyList());
  }

  static MailOwner dynamicTest(ExtensionContext dynamicContext, ExtensionContext factoryContext) {
    List<String> names = new ArrayList<>();
    Optional<ExtensionContext> current = Optional.of(dynamicContext);
    while (current.isPresent()
        && !current.get().getUniqueId().equals(factoryContext.getUniqueId())) {
      names.add(0, current.get().getDisplayName());
      current = current.get().getParent();
    }
    return new MailOwner(
        Phase.TEST,
        factoryContext.getRequiredTestClass().getName(),
        classInvocationNames(factoryContext),
        factoryContext.getRequiredTestMethod().getName(),
        String.join(" › ", names),
        dynamicContext.getUniqueId(),
        Collections.emptyList());
  }

  static MailOwner outsideTest(ExtensionContext classContext) {
    return new MailOwner(
        Phase.OUTSIDE_TEST,
        classContext.getRequiredTestClass().getName(),
        classInvocationNames(classContext),
        null,
        null,
        classContext.getUniqueId(),
        Collections.emptyList());
  }

  static MailOwner outsideClass() {
    return OUTSIDE_CLASS;
  }

  static MailOwner ambiguous(List<MailOwner> candidates) {
    return new MailOwner(
        Phase.AMBIGUOUS, null, null, null, null, null, Collections.unmodifiableList(candidates));
  }

  /**
   * The names of the invocations of class templates around a context, outermost first, such as
   * {@code [1] en} for a {@code @ParameterizedClass}, or {@code null} if there are none.
   */
  static String classInvocationNames(ExtensionContext context) {
    List<String> names = new ArrayList<>();
    Optional<ExtensionContext> current = Optional.of(context);
    while (current.isPresent()) {
      String uniqueId = current.get().getUniqueId();
      if (uniqueId.startsWith(CLASS_TEMPLATE_INVOCATION, uniqueId.lastIndexOf("/[") + 1)) {
        names.add(0, current.get().getDisplayName());
      }
      current = current.get().getParent();
    }
    return names.isEmpty() ? null : String.join(" › ", names);
  }

  Phase phase() {
    return phase;
  }

  String uniqueId() {
    return uniqueId;
  }

  void putInto(Map<String, Object> json) {
    json.put("testClass", testClass);
    putClassDisplayName(json, classDisplayName);
    json.put("testMethod", testMethod);
    json.put("displayName", displayName);
    json.put("uniqueId", uniqueId);
    json.put("phase", phase.jsonValue());
    if (phase == Phase.AMBIGUOUS) {
      List<Map<String, Object>> candidateJson = new ArrayList<>();
      for (MailOwner candidate : candidates) {
        Map<String, Object> test = new LinkedHashMap<>();
        test.put("testClass", candidate.testClass);
        putClassDisplayName(test, candidate.classDisplayName);
        test.put("testMethod", candidate.testMethod);
        test.put("displayName", candidate.displayName);
        test.put("uniqueId", candidate.uniqueId);
        candidateJson.add(test);
      }
      json.put("candidates", candidateJson);
    }
  }

  void putTestInto(Map<String, Object> json) {
    json.put("testClass", testClass);
    putClassDisplayName(json, classDisplayName);
    if (phase == Phase.TEST) {
      json.put("testMethod", testMethod);
      json.put("displayName", displayName);
    }
    json.put("uniqueId", uniqueId);
  }

  private static void putClassDisplayName(Map<String, Object> json, String classDisplayName) {
    if (classDisplayName != null) {
      json.put("classDisplayName", classDisplayName);
    }
  }
}
