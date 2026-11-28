package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.yukio0.greenmailtestviewer.jupiter.GreenMailTestViewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@GreenMailTestViewer
public class PurgeDuringTestSample {

  @RegisterExtension
  final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

  @Test
  void purgesBetweenTwoMails() throws FolderException {
    Mails.send(greenMail, "before the purge");
    greenMail.purgeEmailFromAllMailboxes();
    Mails.send(greenMail, "after the purge");
  }
}
