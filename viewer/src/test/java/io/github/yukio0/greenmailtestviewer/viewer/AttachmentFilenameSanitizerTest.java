package io.github.yukio0.greenmailtestviewer.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AttachmentFilenameSanitizerTest {

  private final AttachmentFilenameSanitizer sanitizer = new AttachmentFilenameSanitizer();

  @Test
  void keepsNormalFileName() {
    assertThat(sanitizer.sanitize("hello.txt", "1")).isEqualTo("hello.txt");
  }

  @Test
  void keepsJapaneseFileName() {
    assertThat(sanitizer.sanitize("日本語.txt", "1")).isEqualTo("日本語.txt");
  }

  @Test
  void usesBaseNameForUnixPathTraversalStyleFileName() {
    assertThat(sanitizer.sanitize("../../evil.txt", "1")).isEqualTo("evil.txt");
  }

  @Test
  void usesBaseNameForWindowsPathStyleFileName() {
    assertThat(sanitizer.sanitize("C:\\Windows\\system.ini", "1")).isEqualTo("system.ini");
  }

  @Test
  void usesFallbackForTraversalPathWithoutBaseName() {
    assertThat(sanitizer.sanitize("../../", "3")).isEqualTo("attachment-3");
  }

  @Test
  void replacesControlCharactersAndUnsafeCharacters() {
    assertThat(sanitizer.sanitize("hello\r\nworld?.txt", "1")).isEqualTo("hello_world_.txt");
  }

  @Test
  void usesFallbackWhenFileNameIsNull() {
    assertThat(sanitizer.sanitize(null, "7")).isEqualTo("attachment-7");
  }

  @Test
  void usesFallbackWhenFileNameIsBlank() {
    assertThat(sanitizer.sanitize("   ", "7")).isEqualTo("attachment-7");
  }

  @Test
  void usesFallbackWhenAttachmentIdIsBlank() {
    assertThat(sanitizer.sanitize(null, "")).isEqualTo("attachment-file");
  }

  @Test
  void usesFallbackWhenFileNameOnlyHasDotsAndSpaces() {
    assertThat(sanitizer.sanitize(" .. . ", "8")).isEqualTo("attachment-8");
  }

  @Test
  void prefixesWindowsReservedNames() {
    assertThat(sanitizer.sanitize("CON.txt", "1")).isEqualTo("_CON.txt");
  }

  @Test
  void prefixesWindowsReservedNamesWithoutExtension() {
    assertThat(sanitizer.sanitize("NUL", "1")).isEqualTo("_NUL");
  }

  @Test
  void prefixesNumberedWindowsReservedNamesCaseInsensitively() {
    assertThat(sanitizer.sanitize("com1.log", "1")).isEqualTo("_com1.log");
  }

  @Test
  void limitsVeryLongFileNameWhilePreservingExtension() {
    String sanitized = sanitizer.sanitize("a".repeat(200) + ".txt", "1");

    assertThat(sanitized).hasSize(120).endsWith(".txt");
  }

  @Test
  void limitsVeryLongFileNameWithoutShortExtension() {
    assertThat(sanitizer.sanitize("a".repeat(200), "1")).isEqualTo("a".repeat(120));
  }

  @Test
  void doesNotCutACharacterInTwoWhenLimitingTheLength() {
    String mail = new String(Character.toChars(0x1F4E7));

    assertThat(sanitizer.sanitize("x" + mail.repeat(100) + ".txt", "1"))
        .isEqualTo("x" + mail.repeat(57) + ".txt");
    assertThat(sanitizer.sanitize("x" + mail.repeat(100), "1")).isEqualTo("x" + mail.repeat(59));
  }
}
