package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;

class MailAddressFormatterTest {

  private final MailAddressFormatter formatter = new MailAddressFormatter();

  @Test
  void joinsAddressesWithCommas() throws AddressException {
    Address[] addresses = InternetAddress.parse("alice@example.com, Bob <bob@example.com>");

    assertThat(formatter.format(addresses)).isEqualTo("alice@example.com, Bob <bob@example.com>");
  }

  @Test
  void returnsNullForNoAddresses() {
    assertThat(formatter.format(null)).isNull();
    assertThat(formatter.format(new Address[0])).isNull();
  }

  @Test
  void decodesEncodedDisplayNames() throws AddressException {
    Address[] addresses =
        InternetAddress.parse("=?UTF-8?B?44K144Oz44OX44Or5ZWG5bqX?= <no-reply@example.com>");

    assertThat(formatter.toList(addresses)).containsExactly("サンプル商店 <no-reply@example.com>");
  }
}
