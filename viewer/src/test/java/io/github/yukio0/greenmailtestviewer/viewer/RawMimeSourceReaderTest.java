package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class RawMimeSourceReaderTest {

  private static final Session SESSION = Session.getInstance(new Properties());

  private final RawMimeSourceReader reader = new RawMimeSourceReader();

  @Test
  void readsTheHeadersAndTheBodyWithoutGeneratingMessageId() throws MessagingException {
    MimeMessage message = testMessage();

    String source = reader.readDisplaySource(message);

    assertThat(source).contains("Subject: raw source test").contains("hello raw source");
    assertThat(message.getHeader("Message-ID")).isNull();
  }

  @Test
  void showsATransferEncodedBodyAndEncodedWordsAsTheyAre() throws MessagingException {
    String subject = "=?UTF-8?B?44GU5rOo5paH?=";
    String base64 =
        Base64.getEncoder().encodeToString("ご注文ありがとうございます".getBytes(StandardCharsets.UTF_8));
    MimeMessage base64Mail =
        rawMessage(
            "Subject: " + subject + "\r\n",
            "text/plain; charset=UTF-8",
            "base64",
            base64.getBytes(StandardCharsets.US_ASCII));
    MimeMessage quotedPrintable =
        rawMessage(
            "Subject: Menu\r\n",
            "text/plain; charset=ISO-8859-1",
            "quoted-printable",
            "Caf=E9 menu".getBytes(StandardCharsets.US_ASCII));

    assertThat(reader.readDisplaySource(base64Mail))
        .contains("Subject: " + subject, "Content-Transfer-Encoding: base64\r\n\r\n" + base64)
        .doesNotContain("ご注文");
    assertThat(reader.readDisplaySource(quotedPrintable)).endsWith("\r\n\r\nCaf=E9 menu");
  }

  @Test
  void readsABodyInAnUnknownTransferEncodingInItsCharset() throws MessagingException {
    String body = "これはShift_JISで送信したメールです。";
    MimeMessage message =
        rawMessage(
            "Subject: SJIS\r\n",
            "text/plain; charset=Shift_JIS",
            "8-bit",
            body.getBytes(Charset.forName("Shift_JIS")));

    assertThat(reader.readDisplaySource(message))
        .contains("Content-Transfer-Encoding: 8-bit")
        .endsWith("\r\n\r\n" + body);
  }

  @Test
  void showsAnAsciiBodyAsItIsAlsoInACharsetThatWouldReadItOtherwise() throws MessagingException {
    String base64 = Base64.getEncoder().encodeToString("Hello".getBytes(StandardCharsets.UTF_16));
    MimeMessage message =
        rawMessage(
            "Subject: UTF-16\r\n",
            "text/plain; charset=UTF-16",
            "base64",
            base64.getBytes(StandardCharsets.US_ASCII));

    assertThat(reader.readDisplaySource(message)).endsWith("\r\n\r\n" + base64);
  }

  @Test
  void readsTheBodyOfShiftJisInItsCharset()
      throws MessagingException, UnsupportedEncodingException {
    String body = "これはShift_JISで送信したメールです。\n半角カナ: ｶﾀｶﾅ。";
    MimeMessage message = singleTextRawMessage("Shift_JIS", "[LEGACY] SJISメール", body);

    String source = reader.readDisplaySource(message);

    assertThat(source)
        .contains("Subject: " + MimeUtility.encodeText("[LEGACY] SJISメール", "Shift_JIS", "B"))
        .contains(body);
    assertThat(message.getHeader("Message-ID")).isNull();
  }

  @Test
  void readsTheBodyOfEucKrInItsCharset() throws MessagingException, UnsupportedEncodingException {
    String body = "이 메일은 EUC-KR로 보낸 메일입니다.";
    MimeMessage message = singleTextRawMessage("EUC-KR", "[LEGACY] EUC-KR 한글 메일", body);

    String source = reader.readDisplaySource(message);

    assertThat(source).contains(body);
  }

  @Test
  void readsTheBodiesOfCommonLegacyCharsetsInTheirCharsets()
      throws MessagingException, UnsupportedEncodingException {
    String[][] cases = {
      {"ISO-2022-JP", "ISO-2022-JPメール", "これはISO-2022-JPで送信したメールです。"},
      {"GB18030", "GB18030 中文邮件", "你好，这是用GB18030发送的中文邮件。"},
      {"Big5", "Big5 繁體中文郵件", "你好，這是用Big5發送的繁體中文郵件。"},
      {"windows-1252", "windows-1252 café mail", "Café déjà vu – résumé: naïve façade."},
    };
    for (String[] testCase : cases) {
      MimeMessage message = singleTextRawMessage(testCase[0], testCase[1], testCase[2]);

      String source = reader.readDisplaySource(message);

      assertThat(source).as(testCase[0]).contains(testCase[2]);
    }
  }

  private static MimeMessage singleTextRawMessage(String charsetName, String subject, String body)
      throws MessagingException, UnsupportedEncodingException {
    return rawMessage(
        "Subject: " + MimeUtility.encodeText(subject, charsetName, "B") + "\r\n",
        "text/plain; charset=" + charsetName,
        "8bit",
        body.getBytes(Charset.forName(charsetName)));
  }

  private static MimeMessage rawMessage(
      String subjectLine, String contentType, String transferEncoding, byte[] body)
      throws MessagingException {
    String headers =
        "From: alice@example.com\r\n"
            + "To: bob@example.com\r\n"
            + subjectLine
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: "
            + contentType
            + "\r\n"
            + "Content-Transfer-Encoding: "
            + transferEncoding
            + "\r\n"
            + "\r\n";
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    raw.writeBytes(headers.getBytes(StandardCharsets.US_ASCII));
    raw.writeBytes(body);
    return new MimeMessage(SESSION, new ByteArrayInputStream(raw.toByteArray()));
  }

  private static MimeMessage testMessage() throws MessagingException {
    MimeMessage message = new MimeMessage(SESSION);
    message.setFrom("alice@example.com");
    message.setRecipients(RecipientType.TO, "bob@example.com");
    message.setSubject("raw source test");
    message.setText("hello raw source", "UTF-8");
    return message;
  }
}
