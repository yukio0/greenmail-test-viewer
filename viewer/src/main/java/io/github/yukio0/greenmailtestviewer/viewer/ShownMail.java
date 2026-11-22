package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;

final class ShownMail {
  private final MailRecord mail;
  private final ModuleNode module;
  private final Run run;
  private final ClassNode classNode;
  private final TestNode test;
  private final String group;

  ShownMail(
      MailRecord mail,
      ModuleNode module,
      Run run,
      ClassNode classNode,
      TestNode test,
      String group) {
    this.mail = mail;
    this.module = module;
    this.run = run;
    this.classNode = classNode;
    this.test = test;
    this.group = group;
  }

  MailRecord mail() {
    return mail;
  }

  ModuleNode module() {
    return module;
  }

  Run run() {
    return run;
  }

  ClassNode classNode() {
    return classNode;
  }

  TestNode test() {
    return test;
  }

  String group() {
    return group;
  }
}
