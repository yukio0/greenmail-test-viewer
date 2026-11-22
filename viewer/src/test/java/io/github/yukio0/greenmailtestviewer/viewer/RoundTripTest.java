package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripBrokenParameterizedClassSample;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripDynamicSample;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripNestedParameterizedClassSample;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripParameterizedClassKindsSample;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripParameterizedClassSample;
import io.github.yukio0.greenmailtestviewer.viewer.samples.RoundTripSample;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.testkit.engine.EngineTestKit;

class RoundTripTest {

  @TempDir Path temporary;

  @Test
  void readsWhatTheExtensionWrote() throws Exception {
    Path outputDirectory = temporary.resolve("reports/mail");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(1).failed(1));

    RecordStore store = new RecordStore(List.of(outputDirectory));
    store.refresh(true);

    List<Source> sources = store.snapshot().sources();
    assertThat(sources).hasSize(1);
    assertThat(sources.get(0).name()).isEqualTo("mail");
    assertThat(sources.get(0).latestRun().finishedAt()).isNotNull();
    ClassNode classNode = ResultView.latest(sources).modules().get(0).classes().get(0);
    assertThat(classNode.testClass()).isEqualTo(RoundTripSample.class.getName());
    List<String> results =
        classNode.tests().stream()
            .map(test -> test.testMethod() + "=" + test.result())
            .collect(Collectors.toList());
    assertThat(results).containsExactly("failsWithoutMail=failed", "sendsAMail=passed");

    TestNode sendsAMail = classNode.tests().get(1);
    assertThat(sendsAMail.mails()).hasSize(1);
    MailRecord mail = sendsAMail.mails().get(0);
    assertThat(mail.summary().subject()).isEqualTo("Round trip");
    assertThat(mail.summary().bodyText()).isEqualTo("Sent by the extension");
    assertThat(mail.deliveredTo()).containsExactly("receiver@example.com");
    assertThat(classNode.tests().get(0).error()).contains("expected by the sample");
  }

  @Test
  void readsTheDynamicTestsThatTheExtensionWrote() throws Exception {
    Path outputDirectory = temporary.resolve("build/greenmail-test-viewer");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripDynamicSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(1).failed(1));

    RecordStore store = new RecordStore(List.of(outputDirectory));
    store.refresh(true);

    ClassNode classNode =
        ResultView.latest(store.snapshot().sources()).modules().get(0).classes().get(0);
    assertThat(classNode.testClass()).isEqualTo(RoundTripDynamicSample.class.getName());
    assertThat(classNode.tests())
        .extracting(TestNode::testMethod, TestNode::displayName, TestNode::result)
        .containsExactly(
            tuple("signup", "confirms", "failed"), tuple("signup", "welcomes", "passed"));
    assertThat(classNode.tests())
        .extracting(test -> test.mails().get(0).summary().subject())
        .containsExactly("Confirm", "Welcome");
    assertThat(classNode.failedTestCount()).isEqualTo(1);
  }

  @Test
  void tellsApartTheInvocationsOfAParameterizedClass() throws Exception {
    Path outputDirectory = temporary.resolve("build/greenmail-test-viewer");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripParameterizedClassSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(2));

    RecordStore store = new RecordStore(List.of(outputDirectory));
    store.refresh(true);

    List<ClassNode> classes =
        ResultView.latest(store.snapshot().sources()).modules().get(0).classes();
    assertThat(classes)
        .extracting(ClassNode::testClass, ClassNode::classDisplayName)
        .containsExactly(
            tuple(RoundTripParameterizedClassSample.class.getName(), "[1] language=en"),
            tuple(RoundTripParameterizedClassSample.class.getName(), "[2] language=ja"));
    assertThat(classes)
        .extracting(
            classNode -> classNode.tests().get(0).classDisplayName(),
            classNode -> classNode.tests().get(0).mails().get(0).summary().subject(),
            classNode -> classNode.tests().get(0).mails().get(0).test().classDisplayName())
        .containsExactly(
            tuple("[1] language=en", "Welcome en", "[1] language=en"),
            tuple("[2] language=ja", "Welcome ja", "[2] language=ja"));
  }

  @Test
  void aParameterizedClassWhoseArgumentsCannotBeProvidedLeavesNoRecords() {
    Path outputDirectory = temporary.resolve("build/greenmail-test-viewer");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripBrokenParameterizedClassSample.class))
        .execute()
        .containerEvents()
        .assertStatistics(stats -> stats.failed(1));

    assertThat(outputDirectory.resolve("runs")).doesNotExist();
  }

  @Test
  void keepsTheTestsOfAnInvocationOfAParameterizedClassTogether() throws Exception {
    Path outputDirectory = temporary.resolve("build/greenmail-test-viewer");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripParameterizedClassKindsSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(10));

    RecordStore store = new RecordStore(List.of(outputDirectory));
    store.refresh(true);

    List<ClassNode> classes =
        ResultView.latest(store.snapshot().sources()).modules().get(0).classes();
    String sample = RoundTripParameterizedClassKindsSample.class.getName();
    assertThat(classes)
        .extracting(ClassNode::testClass, ClassNode::classDisplayName, node -> testNames(node))
        .containsExactly(
            tuple(sample, "[1] language=en", List.of("confirm", "[1] 1", "[2] 2", "welcomes()")),
            tuple(sample, "[2] language=ja", List.of("confirm", "[1] 1", "[2] 2", "welcomes()")),
            tuple(sample + "$Settings", "[1] language=en", List.of("saves()")),
            tuple(sample + "$Settings", "[2] language=ja", List.of("saves()")));
    assertThat(classes.get(1).tests())
        .extracting(test -> test.mails().get(0).summary().subject())
        .containsExactly("Confirm ja", "Reminder ja 1", "Reminder ja 2", "Welcome ja");
  }

  @Test
  void keepsTheInvocationsOfANestedParameterizedClassApart() throws Exception {
    Path outputDirectory = temporary.resolve("build/greenmail-test-viewer");
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .selectors(selectClass(RoundTripNestedParameterizedClassSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(3));

    RecordStore store = new RecordStore(List.of(outputDirectory));
    store.refresh(true);

    List<ClassNode> classes =
        ResultView.latest(store.snapshot().sources()).modules().get(0).classes();
    String sample = RoundTripNestedParameterizedClassSample.class.getName();
    assertThat(classes)
        .extracting(
            ClassNode::testClass,
            ClassNode::classDisplayName,
            node -> node.tests().get(0).mails().get(0).summary().subject())
        .containsExactly(
            tuple(sample, null, "Signed up"),
            tuple(sample + "$Localized", "[1] language=en", "Welcome en"),
            tuple(sample + "$Localized", "[2] language=ja", "Welcome ja"));
  }

  private static List<String> testNames(ClassNode node) {
    return node.tests().stream().map(TestNode::displayName).collect(Collectors.toList());
  }
}
