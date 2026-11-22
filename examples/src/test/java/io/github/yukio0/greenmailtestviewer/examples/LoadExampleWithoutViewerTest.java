package io.github.yukio0.greenmailtestviewer.examples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.extension.RegisterExtension;

class LoadExampleWithoutViewerTest extends LoadExampleTests {

  @RegisterExtension
  static final GreenMailExtension greenMail =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort()).withPerMethodLifecycle(false);

  @Override
  GreenMailExtension greenMail() {
    return greenMail;
  }
}
