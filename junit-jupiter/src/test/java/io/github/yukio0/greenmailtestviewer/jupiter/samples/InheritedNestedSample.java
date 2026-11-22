package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.base.GreenMailOperations;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class InheritedNestedSample extends InheritedNestedBaseSample {

  @RegisterExtension
  static final GreenMailExtension mailServer =
      new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Override
  protected GreenMailOperations greenMail() {
    return mailServer;
  }
}
