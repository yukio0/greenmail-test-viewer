package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

public final class SharedGreenMail {

  public static final GreenMail GREEN_MAIL = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

  private SharedGreenMail() {}
}
