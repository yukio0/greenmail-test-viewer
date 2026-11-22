package io.github.yukio0.greenmailtestviewer.jupiter.samples;

import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

public final class Servers {

  private static final long SMTPS_STARTUP_TIMEOUT = 10_000;

  private Servers() {}

  public static ServerSetup smtps() {
    ServerSetup smtps = ServerSetupTest.SMTPS.dynamicPort();
    smtps.setServerStartupTimeout(SMTPS_STARTUP_TIMEOUT);
    return smtps;
  }
}
