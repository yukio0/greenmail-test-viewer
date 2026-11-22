package io.github.yukio0.greenmailtestviewer.jupiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

class MailOwnerTest {

  private static final String ENGINE = "[engine:junit-jupiter]";
  private static final String TEMPLATE = ENGINE + "/[class-template:app.SignupTest]";

  @Test
  void aTestOfAnInvocationOfAClassTemplateHasTheNameOfTheInvocation() throws Exception {
    ExtensionContext engine = context(null, ENGINE, "JUnit Jupiter", null, null);
    ExtensionContext template = context(engine, TEMPLATE, "SignupTest", SignupTest.class, null);
    ExtensionContext invocation =
        context(
            template,
            TEMPLATE + "/[class-template-invocation:#1]",
            "[1] en",
            SignupTest.class,
            null);
    ExtensionContext method =
        context(
            invocation,
            TEMPLATE + "/[class-template-invocation:#1]/[method:sends()]",
            "sends()",
            SignupTest.class,
            SignupTest.class.getDeclaredMethod("sends"));

    Map<String, Object> mail = new LinkedHashMap<>();
    MailOwner.test(method).putInto(mail);
    Map<String, Object> test = new LinkedHashMap<>();
    MailOwner.test(method).putTestInto(test);
    Map<String, Object> outside = new LinkedHashMap<>();
    MailOwner.outsideTest(invocation).putInto(outside);

    assertThat(mail).containsEntry("classDisplayName", "[1] en");
    assertThat(test).containsEntry("classDisplayName", "[1] en");
    assertThat(outside).containsEntry("classDisplayName", "[1] en");
    ExtensionContext nested =
        context(
            invocation,
            TEMPLATE + "/[class-template-invocation:#1]/[nested-class:Inner]",
            "Inner",
            SignupTest.Inner.class,
            null);
    assertThat(MailOwner.classInvocationNames(nested)).isEqualTo("[1] en");
    ExtensionContext nestedInvocation =
        context(
            nested,
            TEMPLATE
                + "/[class-template-invocation:#1]/[nested-class-template:Inner]"
                + "/[class-template-invocation:#2]",
            "[2] mobile",
            SignupTest.Inner.class,
            null);
    assertThat(MailOwner.classInvocationNames(nestedInvocation)).isEqualTo("[1] en › [2] mobile");
  }

  @Test
  void otherTestsHaveNoNameOfAnInvocation() throws Exception {
    ExtensionContext engine = context(null, ENGINE, "JUnit Jupiter", null, null);
    String classId = ENGINE + "/[class:app.SignupTest]";
    ExtensionContext testClass = context(engine, classId, "SignupTest", SignupTest.class, null);
    ExtensionContext method =
        context(
            testClass,
            classId + "/[method:sends()]",
            "sends()",
            SignupTest.class,
            SignupTest.class.getDeclaredMethod("sends"));

    Map<String, Object> mail = new LinkedHashMap<>();
    MailOwner.test(method).putInto(mail);

    assertThat(mail).doesNotContainKey("classDisplayName");
    assertThat(MailOwner.classInvocationNames(method)).isNull();
  }

  private static ExtensionContext context(
      ExtensionContext parent,
      String uniqueId,
      String displayName,
      Class<?> testClass,
      Method testMethod) {
    return (ExtensionContext)
        Proxy.newProxyInstance(
            MailOwnerTest.class.getClassLoader(),
            new Class<?>[] {ExtensionContext.class},
            (proxy, method, arguments) -> {
              switch (method.getName()) {
                case "getParent":
                  return Optional.ofNullable(parent);
                case "getUniqueId":
                  return uniqueId;
                case "getDisplayName":
                  return displayName;
                case "getTestClass":
                  return Optional.ofNullable(testClass);
                case "getRequiredTestClass":
                  return testClass;
                case "getTestMethod":
                  return Optional.ofNullable(testMethod);
                case "getRequiredTestMethod":
                  return testMethod;
                case "toString":
                  return uniqueId;
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  static class SignupTest {
    void sends() {}

    class Inner {}
  }
}
