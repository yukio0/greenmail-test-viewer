package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineTestKit;

@GreenMailTestViewer
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OverlappingExecutionSample {

  public static volatile Path outputDirectory;

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  @Order(1)
  void sendsBefore() {
    Mails.send(greenMail, "outer before");
  }

  @Test
  @Order(2)
  void runsAnotherExecution() {
    EngineTestKit.engine("junit-jupiter")
        .configurationParameter("greenmail.testviewer.output-dir", outputDirectory.toString())
        .configurationParameter("greenmail.testviewer.keep-runs", "0")
        .selectors(selectClass(RecipientsSample.class))
        .execute()
        .testEvents()
        .assertStatistics(stats -> stats.succeeded(1).failed(0));
  }

  @Test
  @Order(3)
  void sendsAfter() {
    Mails.send(greenMail, "outer after");
  }
}
