package io.github.yukio0.greenmailtestviewer.viewer;

import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.MailPart;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.ParsedAttachment;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.PartTree;
import io.github.yukio0.greenmailtestviewer.viewer.RecordStore.Snapshot;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailSummary;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.TestRef;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Timestamp;
import jakarta.mail.Header;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shapes of the JSON API responses, made from records and views. Not final, so that a test can
 * make a response fail.
 */
class JsonViews {

  static final int PREVIEW_LENGTH = 160;

  static final int MAX_PROBLEMS = 50;

  static final int PREVIEW_CONTEXT = 40;

  static final int MAX_EMBEDDED_IMAGE_BYTES = 2 * 1024 * 1024;

  private final MimeMessageParser parser = new MimeMessageParser();
  private final RawMimeSourceReader sourceReader = new RawMimeSourceReader();
  private final HtmlMailSanitizer sanitizer = new HtmlMailSanitizer();
  private final CidImageSourceRewriter cidRewriter = new CidImageSourceRewriter();
  private final MailReader mailReader = new MailReader();

  static Map<String, Object> status(
      String instance, Snapshot snapshot, List<Path> directories, List<String> problems) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("instance", instance);
    json.put("revision", snapshot.revision());
    Map<String, Object> problemsJson = new LinkedHashMap<>();
    problemsJson.put("count", problems.size());
    problemsJson.put("messages", problems.subList(0, Math.min(problems.size(), MAX_PROBLEMS)));
    json.put("problems", problemsJson);
    List<String> searched = new ArrayList<>();
    for (Path directory : directories) {
      searched.add(directory.toAbsolutePath().normalize().toString());
    }
    json.put("directories", searched);
    List<Object> modules = new ArrayList<>();
    for (Source source : snapshot.sources()) {
      Map<String, Object> module = moduleRef(source);
      module.put("directory", source.directory().toString());
      module.put("runCount", source.runs().size());
      module.put("latestRun", runRef(source.latestRun()));
      modules.add(module);
    }
    json.put("modules", modules);
    return json;
  }

  static Map<String, Object> runs(Snapshot snapshot) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("revision", snapshot.revision());
    List<Object> modules = new ArrayList<>();
    for (Source source : snapshot.sources()) {
      Map<String, Object> module = moduleRef(source);
      List<Object> runs = new ArrayList<>();
      Map<Run, ResultView> views = ResultView.ofEachRun(source);
      for (int i = source.runs().size() - 1; i >= 0; i--) {
        Run run = source.runs().get(i);
        Map<String, Object> runJson = run(run);
        runJson.put("failureCount", views.get(run).modules().get(0).failureCount());
        runs.add(runJson);
      }
      module.put("runs", runs);
      modules.add(module);
    }
    json.put("modules", modules);
    return json;
  }

  static Map<String, Object> tree(Snapshot snapshot, ResultView view) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("revision", snapshot.revision());
    json.put("run", view.selectedRun() == null ? null : run(view.selectedRun()));
    List<Object> modules = new ArrayList<>();
    int failureCount = 0;
    for (ModuleNode module : view.modules()) {
      modules.add(module(module));
      failureCount += module.failureCount();
    }
    json.put("failureCount", failureCount);
    json.put("modules", modules);
    return json;
  }

  static Map<String, Object> messages(
      Snapshot snapshot,
      List<ShownMail> mails,
      int total,
      Timestamp newestCapturedAt,
      boolean selectedInList,
      List<String> terms) {
    List<Object> messages = new ArrayList<>();
    for (ShownMail mail : mails) {
      messages.add(mailSummary(mail, terms));
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("revision", snapshot.revision());
    json.put("total", total);
    json.put("newestCapturedAt", text(newestCapturedAt));
    json.put("selectedInList", selectedInList);
    json.put("messages", messages);
    return json;
  }

  static String previewBody(MailSummary summary, List<String> terms) {
    String body = summary.bodyText();
    String html = summary.otherHtmlText();
    if (html != null
        && !terms.isEmpty()
        && (body == null || firstMatch(body, terms) < 0)
        && firstMatch(html, terms) >= 0) {
      return html;
    }
    return body;
  }

  static String preview(String body, List<String> terms) {
    if (body == null) {
      return null;
    }
    int match = firstMatch(body, terms);
    int start = 0;
    if (match >= 0 && match + longest(terms) > PREVIEW_LENGTH) {
      start = match - PREVIEW_CONTEXT;
    }
    start = Math.max(0, start);
    if (start > 0 && Character.isLowSurrogate(body.charAt(start))) {
      start++;
    }
    int end = Math.min(body.length(), start + PREVIEW_LENGTH);
    if (end < body.length() && Character.isHighSurrogate(body.charAt(end - 1))) {
      end--;
    }
    return (start > 0 ? "\u2026" : "") + body.substring(start, end);
  }

  private static int firstMatch(String text, List<String> terms) {
    String lowerCase = text.toLowerCase(Locale.ROOT);
    int first = -1;
    for (String term : terms) {
      int found = lowerCase.indexOf(term);
      if (found >= 0 && (first < 0 || found < first)) {
        first = found;
      }
    }
    if (first < 0 || lowerCase.length() == text.length()) {
      return first;
    }
    return indexInOriginal(text, first);
  }

  private static int indexInOriginal(String text, int lowerCaseIndex) {
    int lowerCaseEnd = 0;
    for (int i = 0; i < text.length(); ) {
      int codePoint = text.codePointAt(i);
      lowerCaseEnd += new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT).length();
      if (lowerCaseIndex < lowerCaseEnd) {
        return i;
      }
      i += Character.charCount(codePoint);
    }
    return text.length();
  }

  private static int longest(List<String> terms) {
    int longest = 0;
    for (String term : terms) {
      longest = Math.max(longest, term.length());
    }
    return longest;
  }

  static Map<String, Object> textPreview(byte[] bytes, Charset charset) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("text", new String(bytes, charset));
    json.put("charset", charset.name());
    json.put("size", bytes.length);
    json.put("previewLimit", AttachmentPreviewSupport.TEXT_PREVIEW_MAX_BYTES);
    return json;
  }

  static Map<String, Object> runRef(Run run) {
    if (run == null) {
      return null;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", run.id());
    json.put("startedAt", run.startedAt().text());
    return json;
  }

  static Map<String, Object> run(Run run) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", run.id());
    json.put("name", run.name());
    json.put("startedAt", run.startedAt().text());
    json.put("finishedAt", text(run.finishedAt()));
    json.put("finished", run.finishedAt() != null);
    json.put("pid", run.pid());
    json.put("workingDirectory", run.workingDirectory());
    json.put("javaVersion", run.javaVersion());
    json.put("greenMailVersion", run.greenMailVersion());
    int testCount = 0;
    for (TestRecord test : run.tests()) {
      if (test.isMethod()) {
        testCount++;
      }
    }
    json.put("testCount", testCount);
    json.put("mailCount", run.mails().size());
    return json;
  }

  static Map<String, Object> moduleRef(Source source) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", source.id());
    json.put("name", source.name());
    json.put("path", source.path());
    return json;
  }

  static Map<String, Object> module(ModuleNode module) {
    Map<String, Object> json = moduleRef(module.source());
    json.put("latestRun", runRef(module.source().latestRun()));
    List<Object> classes = new ArrayList<>();
    int mailCount = module.outsideClassMails().size() + module.ambiguousMails().size();
    for (ClassNode classNode : module.classes()) {
      Map<String, Object> classJson = classNode(classNode);
      mailCount += (Integer) classJson.get("mailCount");
      classes.add(classJson);
    }
    json.put("mailCount", mailCount);
    json.put("failureCount", module.failureCount());
    json.put("outsideClasses", Map.of("mailCount", module.outsideClassMails().size()));
    json.put("ambiguous", Map.of("mailCount", module.ambiguousMails().size()));
    json.put("classes", classes);
    return json;
  }

  private static Map<String, Object> classNode(ClassNode classNode) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("uniqueId", classNode.uniqueId());
    json.put("testClass", classNode.testClass());
    json.put("classDisplayName", classNode.classDisplayName());
    json.put("simpleName", simpleName(classNode.testClass()));
    json.put("run", runRef(classNode.run()));
    json.put("fromEarlierRun", classNode.fromEarlierRun());
    json.put("setupFailure", classFailure(classNode.setupFailure()));
    json.put("teardownFailure", classFailure(classNode.teardownFailure()));
    int mailCount = classNode.outsideTestMails().size();
    List<Object> tests = new ArrayList<>();
    for (TestNode test : classNode.tests()) {
      mailCount += test.mails().size();
      tests.add(test(test));
    }
    json.put("mailCount", mailCount);
    json.put("failureCount", classNode.failureCount());
    json.put("outsideTests", Map.of("mailCount", classNode.outsideTestMails().size()));
    json.put("tests", tests);
    return json;
  }

  private static Map<String, Object> classFailure(TestRecord record) {
    if (record == null) {
      return null;
    }
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("result", record.result());
    json.put("stage", record.stage());
    json.put("error", record.error());
    json.put("finishedAt", text(record.finishedAt()));
    return json;
  }

  private static Map<String, Object> test(TestNode test) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("uniqueId", test.uniqueId());
    json.put("testClass", test.testClass());
    json.put("classDisplayName", test.classDisplayName());
    json.put("testMethod", test.testMethod());
    json.put("displayName", test.displayName());
    json.put("result", test.result());
    json.put("error", test.error());
    json.put("run", runRef(test.run()));
    json.put("fromEarlierRun", test.fromEarlierRun());
    json.put("mailCount", test.mails().size());
    return json;
  }

  static Map<String, Object> mailSummary(ShownMail shown, List<String> terms) {
    MailRecord mail = shown.mail();
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", mail.id());
    json.put("subject", mail.summary().subject());
    json.put("from", mail.summary().from());
    json.put("to", mail.summary().to());
    json.put("cc", mail.summary().cc());
    json.put("sentAt", mail.summary().sentAt());
    json.put("capturedAt", text(mail.capturedAt()));
    json.put("preview", preview(previewBody(mail.summary(), terms), terms));
    json.put("attachmentCount", mail.summary().attachmentCount());
    json.put("testResult", shown.test() == null ? null : shown.test().result());
    json.put("deliveredTo", mail.deliveredTo());
    putTest(json, mail);
    json.put("module", moduleRef(shown.module().source()));
    json.put("run", runRef(shown.run()));
    json.put("classUniqueId", shown.classNode() == null ? null : shown.classNode().uniqueId());
    json.put("testUniqueId", shown.test() == null ? null : shown.test().uniqueId());
    json.put("group", shown.group());
    return json;
  }

  Map<String, Object> mailDetail(MailRecord mail, MimeMessage message, Source source, Run run) {
    ParsedMail parsed = parser.parse(message);
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", mail.id());
    json.put("subject", MailReader.subject(message));
    json.put("from", mailReader.from(message));
    json.put("to", mailReader.recipients(message, RecipientType.TO));
    json.put("cc", mailReader.recipients(message, RecipientType.CC));
    json.put("bcc", mailReader.recipients(message, RecipientType.BCC));
    json.put("replyTo", mailReader.replyTo(message));
    json.put("sentAt", MailReader.sentAt(message));
    json.put("capturedAt", text(mail.capturedAt()));
    json.put("deliveredTo", mail.deliveredTo());
    putTest(json, mail);
    TestResult result =
        MailRecord.PHASE_TEST.equals(mail.phase()) && mail.test().uniqueId() != null
            ? ResultView.resultOf(run, mail.test().uniqueId())
            : null;
    json.put("testResult", result == null ? null : result.result());
    json.put("testError", result == null ? null : result.error());
    json.put("module", moduleRef(source));
    json.put("run", run(run));
    json.put("headers", headers(message));
    json.put("rawUrl", ApiPaths.raw(mail.id()));

    json.put("textBody", parsed.textBody());
    String html = parsed.htmlBody();
    String sanitized = html == null ? null : sanitizer.sanitize(html);
    CidImageSourceRewriter.Result rendered = null;
    if (html != null) {
      Map<String, String> embedded = embeddedImages(parsed);
      rendered =
          cidRewriter.rewriteWithReport(
              sanitized,
              parsed.htmlBodyPath(),
              parsed.attachments(),
              attachment ->
                  embedded.getOrDefault(
                      attachment.id(), ApiPaths.inlineAttachment(mail.id(), attachment.id())));
    }
    json.put("htmlBody", rendered == null ? null : rendered.html());
    List<Object> links = new ArrayList<>();
    for (MailLinks.Link link : MailLinks.find(sanitized, parsed.textBody())) {
      Map<String, Object> linkJson = new LinkedHashMap<>();
      linkJson.put("url", link.url());
      linkJson.put("text", link.text());
      linkJson.put("inHtml", link.inHtml());
      linkJson.put("inText", link.inText());
      links.add(linkJson);
    }
    json.put("links", links);
    json.put("htmlSource", html);
    Map<String, Object> cidImages = new LinkedHashMap<>();
    cidImages.put("resolvedCount", rendered == null ? 0 : rendered.resolvedCount());
    cidImages.put("missingContentIds", rendered == null ? List.of() : rendered.missingContentIds());
    List<Object> notShown = new ArrayList<>();
    if (rendered != null) {
      for (CidImageSourceRewriter.NotShownImage image : rendered.notShownImages()) {
        Map<String, Object> imageJson = new LinkedHashMap<>();
        imageJson.put("contentId", image.contentId());
        imageJson.put("contentType", MimeMessageParser.mediaType(image.attachment().contentType()));
        imageJson.put("reason", image.reason());
        notShown.add(imageJson);
      }
    }
    cidImages.put("notShownImages", notShown);
    json.put("cidImages", cidImages);

    List<Object> attachments = new ArrayList<>();
    for (AttachmentSummary attachment : parsed.attachments()) {
      attachments.add(attachment(mail.id(), attachment));
    }
    json.put("attachments", attachments);
    json.put("source", sourceReader.readDisplaySource(message));
    json.put("partTree", parsed.partTree() == null ? null : partTree(parsed.partTree()));
    return json;
  }

  /**
   * The images that the HTML preview shows, as {@code data:} URLs by attachment ID. The sandboxed
   * frame has no origin, so browsers may not load them from the viewer. Large ones use their URL.
   */
  private static Map<String, String> embeddedImages(ParsedMail parsed) {
    Map<String, String> images = new HashMap<>();
    for (ParsedAttachment attachment : parsed.attachmentContents()) {
      AttachmentSummary summary = attachment.summary();
      byte[] bytes = attachment.bytes();
      if (summary.contentId() != null
          && bytes != null
          && bytes.length <= MAX_EMBEDDED_IMAGE_BYTES
          && CidImageSourceRewriter.canBeShown(summary)) {
        images.put(
            summary.id(),
            "data:"
                + MimeMessageParser.mediaType(summary.contentType())
                + ";base64,"
                + Base64.getEncoder().encodeToString(bytes));
      }
    }
    return images;
  }

  private static Map<String, Object> attachment(String mailId, AttachmentSummary attachment) {
    boolean decodable = attachment.decodeError() == null;
    String contentType = attachment.contentType();
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", attachment.id());
    json.put("fileName", attachment.fileName());
    json.put("contentType", contentType);
    json.put("size", attachment.size());
    json.put("disposition", attachment.disposition());
    json.put("contentId", attachment.contentId());
    json.put("inlineImage", attachment.inlineImage());
    json.put(
        "previewKind",
        decodable
            ? AttachmentPreviewSupport.previewKind(contentType, attachment.fileName())
            : AttachmentPreviewSupport.KIND_NONE);
    json.put(
        "previewAvailable",
        decodable
            && AttachmentPreviewSupport.isAvailable(
                contentType, attachment.fileName(), attachment.size()));
    json.put("decodeError", attachment.decodeError());
    json.put("downloadUrl", decodable ? ApiPaths.attachment(mailId, attachment.id()) : null);
    json.put(
        "inlineUrl",
        decodable
                && AttachmentPreviewSupport.imageUnavailableReason(contentType, attachment.size())
                    == null
            ? ApiPaths.inlineAttachment(mailId, attachment.id())
            : null);
    json.put(
        "textUrl",
        decodable
                && AttachmentPreviewSupport.textUnavailableReason(
                        contentType, attachment.fileName(), attachment.size())
                    == null
            ? ApiPaths.attachmentText(mailId, attachment.id())
            : null);
    return json;
  }

  private static Map<String, Object> partTree(PartTree tree) {
    MailPart part = tree.part();
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("id", part.id());
    json.put("mediaType", part.mediaType());
    json.put("contentType", part.contentType());
    json.put("charset", part.charset());
    json.put("transferEncoding", part.transferEncoding());
    json.put("disposition", part.disposition());
    json.put("fileName", part.fileName());
    json.put("contentId", part.contentId());
    json.put("size", part.size());
    List<Object> children = new ArrayList<>();
    for (PartTree child : tree.children()) {
      children.add(partTree(child));
    }
    json.put("children", children);
    return json;
  }

  private static void putTest(Map<String, Object> json, MailRecord mail) {
    json.put("phase", mail.phase());
    json.put("testClass", mail.test().testClass());
    json.put("classDisplayName", mail.test().classDisplayName());
    json.put("testMethod", mail.test().testMethod());
    json.put("displayName", mail.test().displayName());
    json.put("uniqueId", mail.test().uniqueId());
    List<Object> candidates = new ArrayList<>();
    for (TestRef candidate : mail.candidates()) {
      Map<String, Object> test = new LinkedHashMap<>();
      test.put("testClass", candidate.testClass());
      test.put("classDisplayName", candidate.classDisplayName());
      test.put("testMethod", candidate.testMethod());
      test.put("displayName", candidate.displayName());
      test.put("uniqueId", candidate.uniqueId());
      candidates.add(test);
    }
    json.put("candidates", candidates);
  }

  private static List<Object> headers(MimeMessage message) {
    List<Object> headers = new ArrayList<>();
    try {
      Enumeration<Header> all = message.getAllHeaders();
      while (all.hasMoreElements()) {
        Header header = all.nextElement();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", header.getName());
        json.put("value", header.getValue());
        headers.add(json);
      }
    } catch (MessagingException e) {
    }
    return headers;
  }

  private static String simpleName(String testClass) {
    if (testClass == null) {
      return null;
    }
    return testClass.substring(testClass.lastIndexOf('.') + 1);
  }

  private static String text(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.text();
  }
}
