package io.github.yukio0.greenmailtestviewer.viewer;

import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;

final class MailAddressFormatter {

  String format(Address[] addresses) {
    List<String> list = toList(addresses);
    return list.isEmpty() ? null : String.join(", ", list);
  }

  List<String> toList(Address[] addresses) {
    List<String> list = new ArrayList<>();
    if (addresses == null) {
      return list;
    }
    for (Address address : addresses) {
      if (address instanceof InternetAddress) {
        list.add(((InternetAddress) address).toUnicodeString());
      } else if (address != null) {
        list.add(address.toString());
      }
    }
    return list;
  }
}
