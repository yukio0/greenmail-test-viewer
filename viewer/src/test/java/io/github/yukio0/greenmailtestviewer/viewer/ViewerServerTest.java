package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ViewerServerTest {

  @Test
  void answersLocalNamesAndIpAddresses() {
    for (String host :
        new String[] {
          "localhost",
          "LOCALHOST:3080",
          "viewer.localhost:3080",
          "127.0.0.1:3080",
          "192.168.1.10",
          "[::1]:3080",
          "[fe80::1%25eth0]",
          "[::ffff:127.0.0.1]"
        }) {
      assertThat(ViewerServer.isAllowedHost(host, Set.of())).as(host).isTrue();
    }
  }

  @Test
  void rejectsOtherNames() {
    for (String host :
        new String[] {
          "evil.example",
          "evil.example:3080",
          "localhost.evil.example",
          "127.0.0.1.nip.io",
          "[evil]"
        }) {
      assertThat(ViewerServer.isAllowedHost(host, Set.of())).as(host).isFalse();
    }
    assertThat(ViewerServer.isAllowedHost(null, Set.of())).isFalse();
  }

  @Test
  void answersTheAllowedNames() {
    assertThat(ViewerServer.isAllowedHost("build-server:3080", Set.of("build-server"))).isTrue();
    assertThat(ViewerServer.isAllowedHost("evil.example", Set.of("build-server"))).isFalse();
    assertThat(ViewerServer.isAllowedHost("evil.example", Set.of("*"))).isTrue();
  }
}
