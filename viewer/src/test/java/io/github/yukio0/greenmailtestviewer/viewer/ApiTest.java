package io.github.yukio0.greenmailtestviewer.viewer;

import static io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.classId;
import static io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.methodId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.yukio0.greenmailtestviewer.viewer.RecordFiles.RunFiles;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Source;
import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiTest {

  private static final String SIGNUP = "com.example.SignupTest";
  private static final String INVOICE_SUBJECT = "請求書のお知らせ";
  private static final byte[] LOGO =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path project;

  private RunFiles run;
  private RecordStore store;
  private ViewerServer server;
  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void startViewer() throws Exception {
    RecordFiles output = RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"));
    run = output.run("run1", "2026-11-01T10:00:00.000Z");
    run.testMail(SIGNUP, "sendsWelcome", "Welcome", "2026-11-01T10:00:01.000Z");
    run.test(SIGNUP, "sendsWelcome", "passed", "2026-11-01T10:00:02.000Z");
    run.testMail(SIGNUP, "sendsInvoice", invoiceMail(), "2026-11-01T10:00:03.000Z");
    run.test(SIGNUP, "sendsInvoice", "passed", "2026-11-01T10:00:04.000Z");
    run.outsideClassMail("Outside", "2026-11-01T10:00:05.000Z");
    run.finish("2026-11-01T10:00:06.000Z");
    store = new RecordStore(List.of(project));
    store.refresh(true);
    server = ViewerServer.start(options(), store);
  }

  @AfterEach
  void stopViewer() {
    server.close();
  }

  private ViewerOptions options(String... more) {
    List<String> args = new ArrayList<>(List.of("--port", "0", "--dir", project.toString()));
    args.addAll(List.of(more));
    return ViewerOptions.parse(args.toArray(new String[0]));
  }

  @Test
  void statusListsTheModules() throws Exception {
    JsonNode status = json("/api/status");

    assertThat(status.path("revision").asLong()).isPositive();
    assertThat(status.path("directories"))
        .extracting(JsonNode::asText)
        .containsExactly(project.toAbsolutePath().normalize().toString());
    assertThat(status.path("problems").path("count").asInt()).isZero();
    JsonNode module = status.path("modules").get(0);
    assertThat(module.path("name").asText()).isEqualTo("app");
    assertThat(module.path("path").asText()).isEqualTo("app/build/greenmail-test-viewer");
    assertThat(module.path("runCount").asInt()).isEqualTo(1);
  }

  @Test
  void statusListsWhatCouldNotBeRead() throws Exception {
    Path broken = run.directory().resolve("messages/000009.json");
    Files.writeString(run.directory().resolve("messages/000009.eml"), RecordFiles.eml("x", "x"));
    Files.writeString(broken, "{ not json");
    store.refresh(false);

    JsonNode problems = json("/api/status").path("problems");

    assertThat(problems.path("count").asInt()).isEqualTo(1);
    assertThat(problems.path("messages").get(0).asText()).contains(broken.toString(), "JSON");
  }

  @Test
  void statusTellsARestartedViewerApart() throws Exception {
    RecordStore restartedStore = new RecordStore(List.of(project));
    restartedStore.refresh(true);
    try (ViewerServer restarted = ViewerServer.start(options(), restartedStore)) {
      JsonNode before = json("/api/status");
      JsonNode after =
          MAPPER.readTree(body("http://127.0.0.1:" + restarted.port() + "/api/status"));

      assertThat(after.path("revision")).isEqualTo(before.path("revision"));
      assertThat(after.path("instance").asText())
          .isNotBlank()
          .isNotEqualTo(before.path("instance").asText());
      assertThat(json("/api/status").path("instance")).isEqualTo(before.path("instance"));
    }
  }

  @Test
  void runsAreListedNewestFirst() throws Exception {
    RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
        .run("run2", "2026-11-01T11:00:00.000Z");
    store.refresh(false);

    JsonNode runs = json("/api/runs").path("modules").get(0).path("runs");

    assertThat(runs).extracting(node -> node.path("name").asText()).containsExactly("run2", "run1");
    assertThat(runs.get(1).path("mailCount").asInt()).isEqualTo(3);
    assertThat(runs.get(1).path("testCount").asInt()).isEqualTo(2);
    assertThat(runs.get(1).path("finished").asBoolean()).isTrue();
    assertThat(runs.get(0).path("finished").asBoolean()).isFalse();
  }

  @Test
  void treeShowsTheClassesTestsAndMailCounts() throws Exception {
    JsonNode module = json("/api/tree").path("modules").get(0);

    assertThat(module.path("mailCount").asInt()).isEqualTo(3);
    assertThat(module.path("outsideClasses").path("mailCount").asInt()).isEqualTo(1);
    JsonNode signup = module.path("classes").get(0);
    assertThat(signup.path("testClass").asText()).isEqualTo(SIGNUP);
    assertThat(signup.path("simpleName").asText()).isEqualTo("SignupTest");
    assertThat(signup.path("tests"))
        .extracting(test -> test.path("testMethod").asText() + "=" + test.path("mailCount"))
        .containsExactly("sendsInvoice=1", "sendsWelcome=1");
    assertThat(signup.path("tests").get(0).path("result").asText()).isEqualTo("passed");
    assertThat(signup.path("tests").get(0).path("fromEarlierRun").asBoolean()).isFalse();
  }

  @Test
  void namesTheInvocationsOfAParameterizedClass() throws Exception {
    String localized = "com.example.LocalizedTest";
    run.classInvocationTest(
        localized, 1, "[1] en", "welcomes", "Welcome en", "2026-11-01T10:00:05.100Z");
    run.classInvocationTest(
        localized, 2, "[2] ja", "welcomes", "Welcome ja", "2026-11-01T10:00:05.200Z");
    store.refresh(false);

    List<JsonNode> classes = new ArrayList<>();
    json("/api/tree").path("modules").get(0).path("classes").forEach(classes::add);
    assertThat(classes)
        .filteredOn(node -> node.path("testClass").asText().equals(localized))
        .extracting(
            node -> node.path("classDisplayName").asText(),
            node -> node.path("tests").get(0).path("classDisplayName").asText())
        .containsExactly(tuple("[1] en", "[1] en"), tuple("[2] ja", "[2] ja"));
    assertThat(classes)
        .filteredOn(node -> node.path("testClass").asText().equals(SIGNUP))
        .singleElement()
        .satisfies(node -> assertThat(node.path("classDisplayName").isNull()).isTrue());
    JsonNode mail = message("Welcome ja");
    assertThat(mail.path("classDisplayName").asText()).isEqualTo("[2] ja");
    assertThat(json("/api/messages/" + mail.path("id").asText()).path("classDisplayName").asText())
        .isEqualTo("[2] ja");
    JsonNode found = json("/api/messages?q=" + URLEncoder.encode("[2] ja", StandardCharsets.UTF_8));
    assertThat(found.path("total").asInt()).isEqualTo(1);
  }

  @Test
  void treeOfASelectedRunNamesTheRunAndAnUnknownRunIsNotFound() throws Exception {
    String runId = json("/api/runs").path("modules").get(0).path("runs").get(0).path("id").asText();

    assertThat(json("/api/tree?run=" + runId).path("run").path("name").asText()).isEqualTo("run1");
    HttpResponse<byte[]> unknown = get("/api/tree?run=unknown");
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(MAPPER.readTree(unknown.body()).path("error").asText()).contains("unknown");
  }

  @Test
  void messagesCanBeFilteredAndSearched() throws Exception {
    String source = json("/api/status").path("modules").get(0).path("id").asText();

    assertThat(subjects("/api/messages")).containsExactly("Welcome", INVOICE_SUBJECT, "Outside");
    assertThat(
            subjects(
                "/api/messages?source="
                    + source
                    + "&test="
                    + encode(methodId(SIGNUP, "sendsInvoice"))))
        .containsExactly(INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?class=" + encode(classId(SIGNUP))))
        .containsExactly("Welcome", INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?group=outsideClasses")).containsExactly("Outside");
    assertThat(subjects("/api/messages?q=" + encode("INVOICE text")))
        .containsExactly(INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?q=" + encode("INVOICE　text　")))
        .containsExactly(INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?q=sendswelcome")).containsExactly("Welcome");
    assertThat(subjects("/api/messages?q=" + encode("請求書"))).containsExactly(INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?q=customer%40example.com")).containsExactly(INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?q=nothing-matches")).isEmpty();
    assertThat(get("/api/messages?group=unknown").statusCode()).isEqualTo(400);
  }

  @Test
  void theMailListCanBeLimitedInEitherOrder() throws Exception {
    JsonNode newest = json("/api/messages?order=newest&limit=2");
    assertThat(newest.path("total").asInt()).isEqualTo(3);
    assertThat(subjects("/api/messages?order=newest&limit=2"))
        .containsExactly("Outside", INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?order=oldest&limit=2"))
        .containsExactly("Welcome", INVOICE_SUBJECT);
    assertThat(subjects("/api/messages?order=newest&limit=10"))
        .containsExactly("Outside", INVOICE_SUBJECT, "Welcome");
    assertThat(json("/api/messages").path("total").asInt()).isEqualTo(3);
    assertThat(subjects("/api/messages")).containsExactly("Welcome", INVOICE_SUBJECT, "Outside");

    JsonNode searched = json("/api/messages?q=signup&limit=1&order=oldest");
    assertThat(searched.path("total").asInt()).isEqualTo(2);
    assertThat(searched.path("messages")).hasSize(1);

    assertThat(json("/api/messages?order=oldest&limit=1").path("newestCapturedAt").asText())
        .isEqualTo(message("Outside").path("capturedAt").asText());

    for (String wrong : List.of("order=sideways", "limit=0", "limit=-1", "limit=many")) {
      assertThat(get("/api/messages?" + wrong).statusCode()).as(wrong).isEqualTo(400);
    }
  }

  @Test
  void theMailListTellsWhetherItHasTheSelectedMailWithoutGrowingToIt() throws Exception {
    String welcome = message("Welcome").path("id").asText();

    String newest = "/api/messages?order=newest&limit=1&selected=" + encode(welcome);
    assertThat(subjects(newest)).containsExactly("Outside");
    assertThat(json(newest).path("selectedInList").asBoolean()).isTrue();
    String oldest = "/api/messages?order=oldest&limit=1&selected=" + encode(welcome);
    assertThat(subjects(oldest)).containsExactly("Welcome");
    assertThat(json(oldest).path("selectedInList").asBoolean()).isTrue();
    String otherGroup = "/api/messages?group=outsideClasses&limit=1&selected=" + encode(welcome);
    assertThat(subjects(otherGroup)).containsExactly("Outside");
    assertThat(json(otherGroup).path("selectedInList").asBoolean()).isFalse();
    assertThat(json("/api/messages?limit=1").path("selectedInList").asBoolean()).isFalse();
  }

  @Test
  void theMailListHasWhatTheListShows() throws Exception {
    JsonNode invoice = message(INVOICE_SUBJECT);

    assertThat(invoice.path("from").asText()).contains("サンプル商店").contains("shop@example.com");
    assertThat(invoice.path("to").get(0).asText()).isEqualTo("customer@example.com");
    assertThat(invoice.path("preview").asText()).isEqualTo("Invoice text body");
    assertThat(invoice.path("attachmentCount").asInt()).isEqualTo(3);
    assertThat(invoice.path("testMethod").asText()).isEqualTo("sendsInvoice");
    assertThat(invoice.path("testUniqueId").asText()).isEqualTo(methodId(SIGNUP, "sendsInvoice"));
    assertThat(invoice.path("module").path("name").asText()).isEqualTo("app");
  }

  @Test
  void whileSearchingThePreviewShowsWhereTheBodyHasTheTerm() throws Exception {
    String body = "Hello. ".repeat(40) + "Your tracking number is ZX-42.";
    run.testMail(
        SIGNUP,
        "sendsTracking",
        RecordFiles.eml("Tracking", body).getBytes(StandardCharsets.UTF_8),
        "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    assertThat(message("Tracking").path("preview").asText()).startsWith("Hello. Hello.");
    JsonNode found = json("/api/messages?q=" + encode("zx-42")).path("messages").get(0);
    assertThat(found.path("preview").asText())
        .startsWith("\u2026")
        .endsWith("Your tracking number is ZX-42.");
  }

  @Test
  void theSearchFindsWordsThatOnlyTheHtmlBodyHas() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Confirm your address",
            "MIME-Version: 1.0",
            "Content-Type: multipart/alternative; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "Open https://example.com/confirm to go on.",
            "--part",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p>Press <a href=\"https://example.com/confirm\">Yes, it is my address</a></p>",
            "--part--",
            "");
    run.testMail(
        SIGNUP,
        "sendsConfirmation",
        mail.getBytes(StandardCharsets.UTF_8),
        "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    assertThat(message("Confirm your address").path("preview").asText())
        .startsWith("Open https://example.com/confirm");
    JsonNode found = json("/api/messages?q=" + encode("my address")).path("messages").get(0);
    assertThat(found.path("subject").asText()).isEqualTo("Confirm your address");
    assertThat(found.path("preview").asText()).contains("Yes, it is my address");
    JsonNode inText = json("/api/messages?q=" + encode("go on")).path("messages").get(0);
    assertThat(inText.path("preview").asText()).contains("to go on");
  }

  @Test
  void readsHeadersWrittenInUtf8() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: 山田商店 <shop@example.com>",
            "To: 花子 <hanako@example.com>",
            "Subject: ご注文の確認 café",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "Thank you.",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Disposition: attachment; filename=\"注文書.txt\"",
            "",
            "Order",
            "--part--",
            "");
    run.testMail(
        SIGNUP, "sendsOrder", mail.getBytes(StandardCharsets.UTF_8), "2026-11-01T10:00:05.500Z");
    byte[] latin =
        String.join("\r\n", "From: cafe@example.com", "Subject: Café menu", "", "Menu", "")
            .getBytes(StandardCharsets.ISO_8859_1);
    run.testMail(SIGNUP, "sendsMenu", latin, "2026-11-01T10:00:05.600Z");
    store.refresh(false);

    JsonNode listed = message("ご注文の確認 café");
    assertThat(listed.path("from").asText()).isEqualTo("山田商店 <shop@example.com>");
    assertThat(subjects("/api/messages?q=" + encode("注文"))).containsExactly("ご注文の確認 café");
    JsonNode detail = json("/api/messages/" + listed.path("id").asText());
    assertThat(detail.path("to"))
        .extracting(JsonNode::asText)
        .containsExactly("花子 <hanako@example.com>");
    assertThat(detail.path("headers"))
        .filteredOn(header -> header.path("name").asText().equals("Subject"))
        .extracting(header -> header.path("value").asText())
        .containsExactly("ご注文の確認 café");
    assertThat(detail.path("attachments"))
        .extracting(attachment -> attachment.path("fileName").asText())
        .containsExactly("注文書.txt");
    assertThat(detail.path("source").asText())
        .contains("From: 山田商店 <shop@example.com>", "Subject: ご注文の確認 café");
    assertThat(subjects("/api/messages?q=menu")).containsExactly("Café menu");
  }

  @Test
  void showsTheImageOfTheAlternativeWhoseHtmlIsShown() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Two logos",
            "MIME-Version: 1.0",
            "Content-Type: multipart/alternative; boundary=\"alternative\"",
            "",
            "--alternative",
            "Content-Type: multipart/related; boundary=\"first\"",
            "",
            "--first",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "Welcome",
            "--first",
            "Content-Type: image/svg+xml",
            "Content-ID: <logo>",
            "",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "--first--",
            "--alternative",
            "Content-Type: multipart/related; boundary=\"second\"",
            "",
            "--second",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p><img src=\"cid:logo\" alt=\"Logo\">Welcome</p>",
            "--second",
            "Content-Type: image/png",
            "Content-ID: <logo>",
            "Content-Transfer-Encoding: base64",
            "",
            Base64.getEncoder().encodeToString(LOGO),
            "--second--",
            "--alternative--",
            "");
    run.testMail(
        SIGNUP, "sendsLogos", mail.getBytes(StandardCharsets.UTF_8), "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    JsonNode detail = json("/api/messages/" + message("Two logos").path("id").asText());

    assertThat(detail.path("htmlBody").asText())
        .contains("src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(LOGO) + "\"");
  }

  @Test
  void showsTheFormOfAnImageThatTheViewerShows() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Logo forms",
            "MIME-Version: 1.0",
            "Content-Type: multipart/related; boundary=\"related\"",
            "",
            "--related",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p><img src=\"cid:logo\" alt=\"Logo\"><img src=\"cid:badge\" alt=\"Badge\">Welcome</p>",
            "--related",
            "Content-Type: multipart/alternative; boundary=\"logo\"",
            "",
            "--logo",
            "Content-Type: image/svg+xml",
            "Content-ID: <logo>",
            "",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "--logo",
            "Content-Type: image/png",
            "Content-ID: <logo>",
            "Content-Transfer-Encoding: base64",
            "",
            Base64.getEncoder().encodeToString(LOGO),
            "--logo--",
            "--related",
            "Content-Type: image/svg+xml",
            "Content-ID: <badge>",
            "",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
            "--related--",
            "");
    run.testMail(
        SIGNUP,
        "sendsLogoForms",
        mail.getBytes(StandardCharsets.UTF_8),
        "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    JsonNode detail = json("/api/messages/" + message("Logo forms").path("id").asText());

    assertThat(detail.path("htmlBody").asText())
        .contains(
            "src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(LOGO) + "\"",
            "src=\"cid:badge\"");
    JsonNode cidImages = detail.path("cidImages");
    assertThat(cidImages.path("resolvedCount").asInt()).isEqualTo(1);
    assertThat(cidImages.path("missingContentIds")).isEmpty();
    assertThat(cidImages.path("notShownImages")).hasSize(1);
    JsonNode badge = cidImages.path("notShownImages").get(0);
    assertThat(badge.path("contentId").asText()).isEqualTo("badge");
    assertThat(badge.path("contentType").asText()).isEqualTo("image/svg+xml");
    assertThat(badge.path("reason").asText()).isEqualTo("unsupported");
  }

  @Test
  void showsAnImageWithContentRatherThanAnEmptyOne() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Empty logo",
            "MIME-Version: 1.0",
            "Content-Type: multipart/related; boundary=\"related\"",
            "",
            "--related",
            "Content-Type: text/html; charset=UTF-8",
            "",
            "<p><img src=\"cid:logo\" alt=\"Logo\"><img src=\"cid:spacer\" alt=\"\">Welcome</p>",
            "--related",
            "Content-Type: image/png",
            "Content-ID: <logo>",
            "Content-Transfer-Encoding: base64",
            "",
            "",
            "--related",
            "Content-Type: image/png",
            "Content-ID: <logo>",
            "Content-Transfer-Encoding: base64",
            "",
            Base64.getEncoder().encodeToString(LOGO),
            "--related",
            "Content-Type: image/png",
            "Content-ID: <spacer>",
            "Content-Transfer-Encoding: base64",
            "",
            "",
            "--related--",
            "");
    run.testMail(
        SIGNUP,
        "sendsEmptyLogo",
        mail.getBytes(StandardCharsets.UTF_8),
        "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    JsonNode detail = json("/api/messages/" + message("Empty logo").path("id").asText());

    assertThat(detail.path("htmlBody").asText())
        .contains(
            "src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(LOGO) + "\"",
            "src=\"cid:spacer\"");
    JsonNode cidImages = detail.path("cidImages");
    assertThat(cidImages.path("resolvedCount").asInt()).isEqualTo(1);
    assertThat(cidImages.path("missingContentIds")).isEmpty();
    assertThat(cidImages.path("notShownImages")).hasSize(1);
    JsonNode spacer = cidImages.path("notShownImages").get(0);
    assertThat(spacer.path("contentId").asText()).isEqualTo("spacer");
    assertThat(spacer.path("reason").asText()).isEqualTo("empty");
    JsonNode empty = detail.path("attachments").get(0);
    assertThat(empty.path("size").asLong()).isZero();
    assertThat(empty.path("previewAvailable").asBoolean()).isFalse();
    assertThat(empty.path("inlineUrl").isNull()).isTrue();
    String download = empty.path("downloadUrl").asText();
    HttpResponse<byte[]> inline = get(download + "/inline");
    assertThat(inline.statusCode()).isEqualTo(422);
    assertThat(new String(inline.body(), StandardCharsets.UTF_8)).contains("This image is empty.");
    assertThat(get(download).statusCode()).isEqualTo(200);
    JsonNode logo = detail.path("attachments").get(1);
    assertThat(get(logo.path("inlineUrl").asText()).statusCode()).isEqualTo(200);
  }

  @Test
  void readsBodiesInUnknownTransferEncodingsAsTheyAre() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Your code",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: utf-8",
            "",
            "Your code is 1234.",
            "--part",
            "Content-Type: text/csv; name=\"codes.csv\"",
            "Content-Disposition: attachment; filename=\"codes.csv\"",
            "Content-Transfer-Encoding: 8-bit",
            "",
            "code,1234",
            "--part--",
            "");
    run.testMail(
        SIGNUP, "sendsCode", mail.getBytes(StandardCharsets.UTF_8), "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    JsonNode listed = message("Your code");
    assertThat(listed.path("preview").asText()).isEqualTo("Your code is 1234.");
    assertThat(subjects("/api/messages?q=1234")).containsExactly("Your code");
    JsonNode detail = json("/api/messages/" + listed.path("id").asText());
    assertThat(detail.path("textBody").asText()).isEqualTo("Your code is 1234.");
    JsonNode attachment = detail.path("attachments").get(0);
    assertThat(attachment.path("decodeError").isNull()).isTrue();
    HttpResponse<byte[]> download = get(attachment.path("downloadUrl").asText());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(new String(download.body(), StandardCharsets.UTF_8)).isEqualTo("code,1234");
  }

  @Test
  void theDetailHasTheBodiesAttachmentsAndTheTest() throws Exception {
    String id = message(INVOICE_SUBJECT).path("id").asText();

    JsonNode detail = json("/api/messages/" + id);

    assertThat(detail.path("subject").asText()).isEqualTo(INVOICE_SUBJECT);
    assertThat(detail.path("replyTo")).isEmpty();
    assertThat(detail.path("textBody").asText()).isEqualTo("Invoice text body");
    String html = detail.path("htmlBody").asText();
    assertThat(html)
        .contains("src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(LOGO) + "\"")
        .contains("src=\"https://tracker.example.com/t.png\"")
        .doesNotContain("<script");
    assertThat(detail.path("htmlSource").asText()).contains("<script>");
    JsonNode link = detail.path("links").get(0);
    assertThat(link.path("url").asText()).isEqualTo("https://example.com/invoices/1");
    assertThat(link.path("text").asText()).isEqualTo("Open the invoice");
    assertThat(link.path("inHtml").asBoolean()).isTrue();
    assertThat(detail.path("links")).hasSize(1);
    assertThat(html).contains("target=\"_blank\"");
    assertThat(detail.has("hasBlockedExternalImages")).isFalse();
    assertThat(detail.path("cidImages").path("resolvedCount").asInt()).isEqualTo(1);
    JsonNode attachments = detail.path("attachments");
    assertThat(attachments)
        .extracting(node -> node.path("fileName").asText())
        .containsExactly("logo.png", "見積書.txt", "report.pdf");
    assertThat(attachments.get(0).path("inlineImage").asBoolean()).isTrue();
    assertThat(attachments.get(0).path("inlineUrl").asText()).endsWith("/attachments/1/inline");
    assertThat(attachments.get(1).path("previewKind").asText()).isEqualTo("text");
    assertThat(attachments.get(2).path("previewKind").asText()).isEqualTo("none");
    assertThat(detail.path("rawUrl").asText()).isEqualTo("/api/messages/" + id + "/raw");
    assertThat(attachments.get(1).path("downloadUrl").asText())
        .isEqualTo("/api/messages/" + id + "/attachments/2");
    assertThat(attachments.get(1).path("textUrl").asText())
        .isEqualTo("/api/messages/" + id + "/attachments/2/text");
    assertThat(attachments.get(0).path("textUrl").isNull()).isTrue();
    assertThat(attachments.get(2).path("textUrl").isNull()).isTrue();
    assertThat(attachments.get(2).path("inlineUrl").isNull()).isTrue();
    assertThat(attachments.get(2).path("previewAvailable").asBoolean()).isFalse();
    assertThat(detail.path("phase").asText()).isEqualTo("test");
    assertThat(detail.path("testClass").asText()).isEqualTo(SIGNUP);
    assertThat(detail.path("testMethod").asText()).isEqualTo("sendsInvoice");
    assertThat(detail.path("deliveredTo").get(0).asText()).isEqualTo("receiver@example.com");
    assertThat(detail.path("run").path("name").asText()).isEqualTo("run1");
    assertThat(detail.path("source").asText()).contains("Subject:");
    assertThat(detail.path("partTree").path("mediaType").asText()).isEqualTo("multipart/mixed");
    assertThat(detail.path("headers"))
        .anyMatch(header -> header.path("name").asText().equals("From"));
  }

  @Test
  void aClassThatAnAssumptionAbortedIsNotCountedAsAFailureOfTheRun() throws Exception {
    RunFiles run =
        RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
            .run("run2", "2026-11-01T11:00:00.000Z");
    run.classAborted(SIGNUP, classId(SIGNUP), "beforeAll", "2026-11-01T11:00:01.000Z");
    run.classFailure(SIGNUP, classId(SIGNUP), "afterAll", "2026-11-01T11:00:02.000Z");
    store.refresh(false);

    JsonNode runs = json("/api/runs").path("modules").get(0).path("runs");
    assertThat(runs)
        .filteredOn(each -> each.path("name").asText().equals("run2"))
        .extracting(each -> each.path("failureCount").asInt() + " " + each.has("classFailureCount"))
        .containsExactly("1 false");
  }

  @Test
  void theListAndTheDetailHaveTheResultOfTheTestInTheRunOfTheMail() throws Exception {
    String passedId = message("Welcome").path("id").asText();
    JsonNode outside = message("Outside");
    assertThat(outside.path("testResult").isNull()).isTrue();
    RunFiles failed =
        RecordFiles.create(project.resolve("app/build/greenmail-test-viewer"))
            .run("run2", "2026-11-01T11:00:00.000Z");
    failed.testMail(SIGNUP, "sendsWelcome", "Welcome again", "2026-11-01T11:00:01.000Z");
    failed.test(SIGNUP, "sendsWelcome", "failed", "2026-11-01T11:00:02.000Z");
    store.refresh(false);

    JsonNode failedSummary = message("Welcome again");
    assertThat(failedSummary.path("testResult").asText()).isEqualTo("failed");
    JsonNode failedDetail = json("/api/messages/" + failedSummary.path("id").asText());
    assertThat(failedDetail.path("testResult").asText()).isEqualTo("failed");
    JsonNode tree = json("/api/tree");
    assertThat(tree.path("failureCount").asInt()).isEqualTo(1);
    assertThat(tree.path("modules").get(0).path("failureCount").asInt()).isEqualTo(1);
    assertThat(tree.path("modules").get(0).path("classes").get(0).path("failureCount").asInt())
        .isEqualTo(1);
    assertThat(json("/api/runs").path("modules").get(0).path("runs"))
        .extracting(run -> run.path("name").asText() + "=" + run.path("failureCount").asInt())
        .containsExactly("run2=1", "run1=0");
    assertThat(failedDetail.path("testError").asText())
        .isEqualTo("java.lang.AssertionError: sendsWelcome failed");
    JsonNode passedDetail = json("/api/messages/" + passedId);
    assertThat(passedDetail.path("testResult").asText()).isEqualTo("passed");
    assertThat(passedDetail.path("testError").isNull()).isTrue();
    JsonNode outsideDetail = json("/api/messages/" + outside.path("id").asText());
    assertThat(outsideDetail.path("testResult").isNull()).isTrue();
  }

  @Test
  void theRawMailIsTheEmlFileForDownloading() throws Exception {
    String id = message(INVOICE_SUBJECT).path("id").asText();

    HttpResponse<byte[]> raw = get("/api/messages/" + id + "/raw");

    assertThat(raw.statusCode()).isEqualTo(200);
    assertThat(raw.headers().firstValue("Content-Type")).hasValue("message/rfc822");
    assertThat(raw.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment;")
        .contains("filename*=UTF-8''" + UrlPaths.encodeSegment(INVOICE_SUBJECT + ".eml"));
    assertThat(raw.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
    assertThat(raw.body())
        .isEqualTo(Files.readAllBytes(run.directory().resolve("messages/000002.eml")));
  }

  @Test
  void theRawMailIsNamedAfterTheWholeSubject() throws Exception {
    run.testMail(SIGNUP, "sendsSale", "【11/16】セールのお知らせ", "2026-11-01T10:00:05.500Z");
    run.testMail(SIGNUP, "sendsPart", "Invoice 1\\2", "2026-11-01T10:00:05.600Z");
    store.refresh(false);

    assertThat(rawFileName("【11/16】セールのお知らせ")).isEqualTo("【11_16】セールのお知らせ.eml");
    assertThat(rawFileName("Invoice 1\\2")).isEqualTo("Invoice 1_2.eml");
  }

  private String rawFileName(String subject) throws Exception {
    HttpResponse<byte[]> raw =
        get("/api/messages/" + message(subject).path("id").asText() + "/raw");
    assertThat(raw.statusCode()).isEqualTo(200);
    String disposition = raw.headers().firstValue("Content-Disposition").orElseThrow();
    return URLDecoder.decode(
        disposition.substring(disposition.indexOf("filename*=UTF-8''") + 17),
        StandardCharsets.UTF_8);
  }

  @Test
  void attachmentsCanBeDownloadedAndShownAsImagesOrText() throws Exception {
    String id = message(INVOICE_SUBJECT).path("id").asText();
    String base = "/api/messages/" + id + "/attachments/";
    JsonNode attachments = json("/api/messages/" + id).path("attachments");

    HttpResponse<byte[]> download = get(attachments.get(1).path("downloadUrl").asText());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(new String(download.body(), StandardCharsets.UTF_8)).isEqualTo("見積金額: 1,000円");
    assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment;")
        .contains(UrlPaths.encodeSegment("見積書.txt"));
    assertThat(download.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");

    HttpResponse<byte[]> image = get(attachments.get(0).path("inlineUrl").asText());
    assertThat(image.statusCode()).isEqualTo(200);
    assertThat(image.headers().firstValue("Content-Type")).hasValue("image/png");
    assertThat(image.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("inline;");
    assertThat(image.body()).isEqualTo(LOGO);
    assertThat(get(base + "2/inline").statusCode()).isEqualTo(415);

    assertThat(json(attachments.get(1).path("textUrl").asText()).path("text").asText())
        .isEqualTo("見積金額: 1,000円");
    assertThat(get(base + "3/text").statusCode()).isEqualTo(415);
    assertThat(get(base + "9").statusCode()).isEqualTo(404);
  }

  @Test
  void aFileWithoutANameCanBeDownloaded() throws Exception {
    String mail =
        String.join(
            "\r\n",
            "From: shop@example.com",
            "To: customer@example.com",
            "Subject: Unnamed invoice",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"part\"",
            "",
            "--part",
            "Content-Type: text/plain; charset=UTF-8",
            "",
            "See the invoice.",
            "--part",
            "Content-Type: application/pdf",
            "Content-Transfer-Encoding: base64",
            "",
            Base64.getEncoder().encodeToString("%PDF-1.4".getBytes(StandardCharsets.US_ASCII)),
            "--part--",
            "");
    run.testMail(
        SIGNUP, "sendsUnnamed", mail.getBytes(StandardCharsets.UTF_8), "2026-11-01T10:00:05.500Z");
    store.refresh(false);

    JsonNode listed = message("Unnamed invoice");
    assertThat(listed.path("attachmentCount").asInt()).isEqualTo(1);
    JsonNode attachment =
        json("/api/messages/" + listed.path("id").asText()).path("attachments").get(0);
    assertThat(attachment.path("fileName").isNull()).isTrue();
    assertThat(attachment.path("contentType").asText()).isEqualTo("application/pdf");

    HttpResponse<byte[]> download = get(attachment.path("downloadUrl").asText());
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.headers().firstValue("Content-Type")).hasValue("application/pdf");
    assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment;")
        .contains("attachment-1");
    assertThat(new String(download.body(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.4");
  }

  @Test
  void offersOnlyThePreviewsThatTheApiGives() throws Exception {
    run.testMail(SIGNUP, "sendsLargeFiles", largeFilesMail(), "2026-11-01T10:00:07.000Z");
    store.refresh(false);

    JsonNode attachments =
        json("/api/messages/" + message("Large files").path("id").asText()).path("attachments");

    JsonNode large = attachments.get(0);
    assertThat(large.path("previewKind").asText()).isEqualTo("text");
    assertThat(large.path("previewAvailable").asBoolean()).isFalse();
    assertThat(large.path("textUrl").isNull()).isTrue();
    assertThat(get(large.path("downloadUrl").asText() + "/text").statusCode()).isEqualTo(413);
    JsonNode svg = attachments.get(1);
    assertThat(svg.path("previewKind").asText()).isEqualTo("image");
    assertThat(svg.path("previewAvailable").asBoolean()).isFalse();
    assertThat(svg.path("inlineUrl").isNull()).isTrue();
    assertThat(get(svg.path("downloadUrl").asText() + "/inline").statusCode()).isEqualTo(415);
    assertThat(get(svg.path("downloadUrl").asText()).statusCode()).isEqualTo(200);
  }

  @Test
  void mailsThatCannotBeReadAnswerWithTheReason() throws Exception {
    String id = message(INVOICE_SUBJECT).path("id").asText();

    Path eml = run.directory().resolve("messages/000002.eml");
    Files.delete(eml);
    Files.createDirectory(eml);
    for (String path : List.of("", "/raw", "/attachments/2")) {
      HttpResponse<byte[]> response = get("/api/messages/" + id + path);
      assertThat(response.statusCode()).as(path).isEqualTo(503);
      assertThat(MAPPER.readTree(response.body()).path("error").asText())
          .as(path)
          .startsWith("This message could not be read: ");
    }
  }

  @Test
  void aRequestThatRunsOutOfMemoryAnswersWithTheReason() throws Exception {
    String id = message("Welcome").path("id").asText();
    JsonViews views =
        new JsonViews() {
          @Override
          Map<String, Object> mailDetail(
              MailRecord mail, MimeMessage message, Source source, Run run) {
            throw new OutOfMemoryError("Java heap space (thrown by the test)");
          }
        };
    HttpServer failing = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    failing.createContext("/api/", new ApiHandler(store, List.of(project), views));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    failing.setExecutor(executor);
    failing.start();
    try {
      HttpResponse<byte[]> response =
          send(
              HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + failing.getAddress().getPort()
                          + "/api/messages/"
                          + id)));

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(MAPPER.readTree(response.body()).path("error").asText())
          .startsWith(ApiHandler.NOT_ENOUGH_MEMORY);
    } finally {
      failing.stop(0);
      executor.shutdownNow();
    }
  }

  @Test
  void aFileThatIsNotAMailThatCanBeReadIsNotAskedForAgain() {
    IOException notAMail =
        new IOException("Could not read the email", new MessagingException("broken"));
    assertThat(ApiHandler.cannotReadStatus(notAMail)).isEqualTo(500);
    assertThat(ApiHandler.cannotReadStatus(new FileSystemException("in use"))).isEqualTo(503);
  }

  @Test
  void deletedMailsAreNotFound() throws Exception {
    String id = message(INVOICE_SUBJECT).path("id").asText();

    Files.delete(run.directory().resolve("messages/000002.eml"));
    for (String path : List.of("", "/raw", "/attachments/2", "/attachments/1/inline")) {
      HttpResponse<byte[]> response = get("/api/messages/" + id + path);
      assertThat(response.statusCode()).as(path).isEqualTo(404);
      assertThat(MAPPER.readTree(response.body()).path("error").asText())
          .isEqualTo(ApiHandler.MESSAGE_GONE);
    }

    RecordStoreTest.deleteRecursively(run.directory());
    store.refresh(false);
    assertThat(get("/api/messages/" + id).statusCode()).isEqualTo(404);
    assertThat(json("/api/tree").path("modules").get(0).path("classes")).isEmpty();
    assertThat(json("/api/messages").path("messages")).isEmpty();
  }

  @Test
  void rejectsRequestsForOtherHostNames() throws Exception {
    assertThat(statusLine(server, "evil.example")).contains(" 403 ");
    assertThat(statusLine(server, "evil.example:" + server.port())).contains(" 403 ");
    assertThat(statusLine(server, "localhost:" + server.port())).contains(" 200 ");
    assertThat(statusLine(server, "127.0.0.1:" + server.port())).contains(" 200 ");
    assertThat(statusLine(server, "192.168.1.10:" + server.port())).contains(" 200 ");
  }

  @Test
  void answersTheHostNamesThatAreAllowedByOption() throws Exception {
    try (ViewerServer named =
        ViewerServer.start(options("--allowed-host", "Build-Server.example"), store)) {
      assertThat(statusLine(named, "build-server.example:" + named.port())).contains(" 200 ");
      assertThat(statusLine(named, "evil.example")).contains(" 403 ");
    }
  }

  @Test
  void servesTheScreen() throws Exception {
    HttpResponse<byte[]> index = get("/");

    assertThat(index.statusCode()).isEqualTo(200);
    assertThat(index.headers().firstValue("Content-Type")).hasValue("text/html; charset=utf-8");
    assertThat(new String(index.body(), StandardCharsets.UTF_8)).contains("GreenMail Test Viewer");
    assertThat(index.headers().firstValue("Content-Security-Policy"))
        .hasValue(StaticFileHandler.contentSecurityPolicy(false));
    assertThat(index.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
    HttpResponse<byte[]> script = get("/js/main.js");
    assertThat(script.statusCode()).isEqualTo(200);
    assertThat(script.headers().firstValue("Content-Type"))
        .hasValue("text/javascript; charset=utf-8");
    HttpResponse<byte[]> icon = get("/favicon.svg");
    assertThat(icon.statusCode()).isEqualTo(200);
    assertThat(icon.headers().firstValue("Content-Type")).hasValue("image/svg+xml");
    assertThat(get("/missing.js").statusCode()).isEqualTo(404);
    assertThat(get("/%2e%2e/build.gradle").statusCode()).isEqualTo(404);
    assertThat(get("/js").statusCode()).isEqualTo(404);
  }

  @Test
  void servesTheScreenFromTheStaticDirectory() throws Exception {
    Path screen = Files.createDirectories(project.resolve("screen"));
    Files.writeString(screen.resolve("index.html"), "<p>screen in development</p>");
    Files.writeString(project.resolve("secret.txt"), "secret");
    try (ViewerServer development =
        ViewerServer.start(options("--static-dir", screen.toString()), store)) {
      String base = "http://127.0.0.1:" + development.port();

      assertThat(body(base + "/")).isEqualTo("<p>screen in development</p>");
      assertThat(send(HttpRequest.newBuilder(URI.create(base + "/%2e%2e/secret.txt"))).statusCode())
          .isEqualTo(404);
    }
  }

  @Test
  void answersNotFoundForANameThatTheFileSystemDoesNotTake() throws Exception {
    Path screen = Files.createDirectories(project.resolve("screen"));
    Files.writeString(screen.resolve("index.html"), "<p>screen in development</p>");
    try (ViewerServer development =
        ViewerServer.start(options("--static-dir", screen.toString()), store)) {
      String base = "http://127.0.0.1:" + development.port();

      assertThat(send(HttpRequest.newBuilder(URI.create(base + "/a%00b.js"))).statusCode())
          .isEqualTo(404);
      assertThat(body(base + "/")).isEqualTo("<p>screen in development</p>");
    }
  }

  @Test
  void doesNotServeFilesThatALinkInTheStaticDirectoryLeadsTo() throws Exception {
    Path screen = Files.createDirectories(project.resolve("screen"));
    Files.writeString(screen.resolve("index.html"), "<p>screen in development</p>");
    Path outside = Files.createDirectories(project.resolve("outside"));
    Files.writeString(outside.resolve("secret.txt"), "secret");
    TestFiles.link(screen.resolve("linked"), outside);
    try (ViewerServer development =
        ViewerServer.start(options("--static-dir", screen.toString()), store)) {
      String base = "http://127.0.0.1:" + development.port();

      assertThat(send(HttpRequest.newBuilder(URI.create(base + "/linked/secret.txt"))).statusCode())
          .isEqualTo(404);
      assertThat(body(base + "/")).isEqualTo("<p>screen in development</p>");
    } finally {
      Files.delete(screen.resolve("linked"));
    }
  }

  @Test
  void onlyAllowsReading() throws Exception {
    HttpResponse<byte[]> response =
        send(
            HttpRequest.newBuilder(url("/api/status"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

    assertThat(response.statusCode()).isEqualTo(405);
  }

  private static byte[] largeFilesMail() throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress("shop@example.com"));
    message.setRecipients(RecipientType.TO, "customer@example.com");
    message.setSubject("Large files");
    MimeBodyPart body = new MimeBodyPart();
    body.setText("See the files.");
    MimeBodyPart large = new MimeBodyPart();
    large.setText("a".repeat((int) AttachmentPreviewSupport.TEXT_PREVIEW_MAX_BYTES + 1));
    large.setFileName("large.txt");
    large.setDisposition(Part.ATTACHMENT);
    MimeBodyPart svg = new MimeBodyPart();
    svg.setDataHandler(
        new DataHandler(
            new ByteArrayDataSource(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8),
                "image/svg+xml")));
    svg.setFileName("drawing.svg");
    svg.setDisposition(Part.ATTACHMENT);
    message.setContent(new MimeMultipart("mixed", body, large, svg));
    message.saveChanges();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    message.writeTo(bytes);
    return bytes.toByteArray();
  }

  private static byte[] invoiceMail() throws Exception {
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
    message.setFrom(new InternetAddress("shop@example.com", "サンプル商店", "UTF-8"));
    message.setRecipients(RecipientType.TO, "customer@example.com");
    message.setSubject(INVOICE_SUBJECT, "UTF-8");

    MimeBodyPart text = new MimeBodyPart();
    text.setText("Invoice text body", "UTF-8");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(
        "<p>Invoice <b>html</b></p><img src=\"cid:logo\">"
            + "<a href=\"https://example.com/invoices/1\">Open the invoice</a>"
            + "<script>alert(1)</script><img src=\"https://tracker.example.com/t.png\">",
        "text/html; charset=UTF-8");
    MimeBodyPart logo = new MimeBodyPart();
    logo.setDataHandler(new DataHandler(new ByteArrayDataSource(LOGO, "image/png")));
    logo.setFileName("logo.png");
    logo.setDisposition(Part.INLINE);
    logo.setHeader("Content-ID", "<logo>");
    MimeBodyPart related = new MimeBodyPart();
    related.setContent(new MimeMultipart("related", html, logo));
    MimeBodyPart alternative = new MimeBodyPart();
    alternative.setContent(new MimeMultipart("alternative", text, related));

    MimeBodyPart quotation = new MimeBodyPart();
    quotation.setText("見積金額: 1,000円", "UTF-8");
    quotation.setFileName("見積書.txt");
    quotation.setDisposition(Part.ATTACHMENT);
    MimeBodyPart report = new MimeBodyPart();
    report.setDataHandler(
        new DataHandler(
            new ByteArrayDataSource(
                "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII), "application/pdf")));
    report.setFileName("report.pdf");
    report.setDisposition(Part.ATTACHMENT);

    message.setContent(new MimeMultipart("mixed", alternative, quotation, report));
    message.saveChanges();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    message.writeTo(bytes);
    return bytes.toByteArray();
  }

  private JsonNode message(String subject) throws Exception {
    for (JsonNode message : json("/api/messages").path("messages")) {
      if (subject.equals(message.path("subject").asText())) {
        return message;
      }
    }
    throw new AssertionError("no message " + subject);
  }

  private List<String> subjects(String path) throws Exception {
    List<String> subjects = new ArrayList<>();
    for (JsonNode message : json(path).path("messages")) {
      subjects.add(message.path("subject").asText());
    }
    return subjects;
  }

  private JsonNode json(String path) throws Exception {
    HttpResponse<byte[]> response = get(path);
    assertThat(response.statusCode()).as(path).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValue("application/json; charset=utf-8");
    return MAPPER.readTree(response.body());
  }

  private HttpResponse<byte[]> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(url(path)));
  }

  private String body(String url) throws Exception {
    return new String(send(HttpRequest.newBuilder(URI.create(url))).body(), StandardCharsets.UTF_8);
  }

  private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private URI url(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String statusLine(ViewerServer server, String host) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      OutputStream output = socket.getOutputStream();
      output.write(
          ("GET /api/status HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      output.flush();
      return new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }
}
