package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class DynamicTestsSample {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @TestFactory
  Stream<DynamicNode> signup() {
    Mails.send(greenMail, "made by the factory");
    return Stream.of(
        DynamicTest.dynamicTest("passes", () -> Mails.send(greenMail, "dynamic passes")),
        DynamicTest.dynamicTest(
            "fails",
            () -> {
              Mails.send(greenMail, "dynamic fails");
              throw new AssertionError("the dynamic test fails");
            }),
        DynamicTest.dynamicTest("is aborted", () -> assumeTrue(false, "not this time")),
        DynamicContainer.dynamicContainer(
            "group",
            Stream.of(
                DynamicTest.dynamicTest(
                    "in a group", () -> Mails.send(greenMail, "dynamic in a group")))));
  }

  @TestFactory
  Stream<DynamicTest> brokenFactory() {
    Mails.send(greenMail, "made by the broken factory");
    throw new IllegalStateException("cannot make the dynamic tests");
  }
}
