package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A module as {@link ResultView} shows it: the results of one output directory. */
final class ModuleNode {
  private final Source source;
  private final List<ClassNode> classes;
  private final Map<MailRecord, Run> outsideClassMails;
  private final Map<MailRecord, Run> ambiguousMails;

  ModuleNode(
      Source source,
      List<ClassNode> classes,
      Map<MailRecord, Run> outsideClassMails,
      Map<MailRecord, Run> ambiguousMails) {
    this.source = source;
    this.classes = classes;
    this.outsideClassMails = outsideClassMails;
    this.ambiguousMails = ambiguousMails;
  }

  Source source() {
    return source;
  }

  List<ClassNode> classes() {
    return classes;
  }

  List<MailRecord> outsideClassMails() {
    return new ArrayList<>(outsideClassMails.keySet());
  }

  List<MailRecord> ambiguousMails() {
    return new ArrayList<>(ambiguousMails.keySet());
  }

  int failureCount() {
    int count = 0;
    for (ClassNode node : classes) {
      count += node.failureCount();
    }
    return count;
  }

  List<ShownMail> mails() {
    List<ShownMail> mails = new ArrayList<>();
    for (ClassNode classNode : classes) {
      for (TestNode test : classNode.tests()) {
        for (MailRecord mail : test.mails()) {
          mails.add(new ShownMail(mail, this, test.run(), classNode, test, null));
        }
      }
      for (MailRecord mail : classNode.outsideTestMails()) {
        mails.add(
            new ShownMail(
                mail, this, classNode.run(), classNode, null, ResultView.GROUP_OUTSIDE_TESTS));
      }
    }
    for (Map.Entry<MailRecord, Run> mail : outsideClassMails.entrySet()) {
      mails.add(
          new ShownMail(
              mail.getKey(), this, mail.getValue(), null, null, ResultView.GROUP_OUTSIDE_CLASSES));
    }
    for (Map.Entry<MailRecord, Run> mail : ambiguousMails.entrySet()) {
      mails.add(
          new ShownMail(
              mail.getKey(), this, mail.getValue(), null, null, ResultView.GROUP_AMBIGUOUS));
    }
    return mails;
  }
}
