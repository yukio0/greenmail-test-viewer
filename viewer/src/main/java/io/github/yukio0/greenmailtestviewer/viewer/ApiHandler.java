package io.github.yukio0.greenmailtestviewer.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.AttachmentSummary;
import io.github.yukio0.greenmailtestviewer.viewer.ParsedMail.ParsedAttachment;
import io.github.yukio0.greenmailtestviewer.viewer.RecordStore.Snapshot;
import io.github.yukio0.greenmailtestviewer.viewer.Records.MailRecord;
import io.github.yukio0.greenmailtestviewer.viewer.Records.Run;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The JSON API under {@code /api/}: status, runs, tree, the mail list and each mail with its raw
 * source and attachments. Responses come from the current {@link Snapshot}, and the {@code .eml}
 * file is read again for each request, so the API returns 404 for an email that has been deleted.
 */
final class ApiHandler implements HttpHandler {

  static final String MESSAGE_GONE = "This message is no longer available.";

  static final String NOT_ENOUGH_MEMORY =
      "The viewer does not have enough memory to answer this request. Start it with more memory,"
          + " for example with java -Xmx1g -jar, or by setting maxHeapSize on the Gradle task";

  private static final Logger LOGGER = System.getLogger(ApiHandler.class.getName());

  private static final Pattern MEDIA_TYPE =
      Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

  private final RecordStore store;
  private final List<Path> directories;
  // The revision restarts with the viewer, so the UI compares this too.
  private final String instance = UUID.randomUUID().toString();
  private final MimeMessageParser parser = new MimeMessageParser();
  private final JsonViews views;
  private final AttachmentFilenameSanitizer fileNames = new AttachmentFilenameSanitizer();
  // Building a view reads all records, and the UI makes several requests on the same records.
  private volatile CachedView cachedView;
  private volatile CachedRuns cachedRuns;

  ApiHandler(RecordStore store, List<Path> directories) {
    this(store, directories, new JsonViews());
  }

  ApiHandler(RecordStore store, List<Path> directories, JsonViews views) {
    this.store = store;
    this.directories = List.copyOf(directories);
    this.views = views;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      String method = exchange.getRequestMethod();
      if (!"GET".equals(method) && !"HEAD".equals(method)) {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        Http.sendError(exchange, 405, "Method not allowed: " + method);
        return;
      }
      route(exchange, pathSegments(exchange));
    } catch (RuntimeException | StackOverflowError e) {
      LOGGER.log(Level.WARNING, "Request failed: " + exchange.getRequestURI(), e);
      Http.sendError(exchange, 500, "Internal error: " + e);
    } catch (OutOfMemoryError e) {
      // Sends a response anyway; otherwise the UI would report that the viewer cannot be reached.
      LOGGER.log(Level.WARNING, "Request failed: " + exchange.getRequestURI(), e);
      Http.sendError(exchange, 500, NOT_ENOUGH_MEMORY + " (" + e + ")");
    } finally {
      exchange.close();
    }
  }

  private static List<String> pathSegments(HttpExchange exchange) {
    String path = exchange.getRequestURI().getRawPath().substring("/api/".length());
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/", -1)) {
      segments.add(UrlPaths.decodeSegment(segment));
    }
    if (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty()) {
      segments.remove(segments.size() - 1);
    }
    return segments;
  }

  private void route(HttpExchange exchange, List<String> path) throws IOException {
    Snapshot snapshot = store.snapshot();
    Map<String, String> query = Http.queryParameters(exchange);
    if (path.equals(List.of("status"))) {
      Http.sendJson(
          exchange, 200, JsonViews.status(instance, snapshot, directories, store.problems()));
    } else if (path.equals(List.of("runs"))) {
      Http.sendJson(exchange, 200, runs(snapshot));
    } else if (path.equals(List.of("tree"))) {
      tree(exchange, snapshot, query);
    } else if (path.equals(List.of("messages"))) {
      messages(exchange, snapshot, query);
    } else if (path.size() >= 2 && path.get(0).equals("messages")) {
      message(exchange, snapshot, path.subList(1, path.size()));
    } else {
      Http.sendError(exchange, 404, "Not found: /api/" + String.join("/", path));
    }
  }

  private void tree(HttpExchange exchange, Snapshot snapshot, Map<String, String> query)
      throws IOException {
    ResultView view = view(exchange, snapshot, query.get("run"));
    if (view == null) {
      return;
    }
    Http.sendJson(exchange, 200, JsonViews.tree(snapshot, view));
  }

  private void messages(HttpExchange exchange, Snapshot snapshot, Map<String, String> query)
      throws IOException {
    MailListQuery list;
    try {
      list = MailListQuery.parse(query);
    } catch (IllegalArgumentException e) {
      Http.sendError(exchange, 400, e.getMessage());
      return;
    }
    ResultView view = view(exchange, snapshot, query.get("run"));
    if (view == null) {
      return;
    }
    MailListQuery.Result result = list.select(view);
    Http.sendJson(
        exchange,
        200,
        JsonViews.messages(
            snapshot,
            result.shown(),
            result.total(),
            result.newestCapturedAt(),
            result.selectedInList(),
            list.terms()));
  }

  private ResultView view(HttpExchange exchange, Snapshot snapshot, String runId)
      throws IOException {
    String run = blankToNull(runId) == null ? "" : runId;
    CachedView cached = cachedView;
    if (cached != null && cached.snapshot == snapshot && cached.run.equals(run)) {
      return cached.view;
    }
    ResultView view;
    if (run.isEmpty()) {
      view = ResultView.latest(snapshot.sources());
    } else {
      Run found = snapshot.run(run);
      if (found == null) {
        Http.sendError(exchange, 404, "This run is no longer available: " + run);
        return null;
      }
      view = ResultView.ofRun(snapshot.sourceOfRun(run), found);
    }
    cachedView = new CachedView(snapshot, run, view);
    return view;
  }

  private Map<String, Object> runs(Snapshot snapshot) {
    CachedRuns cached = cachedRuns;
    if (cached != null && cached.snapshot == snapshot) {
      return cached.runs;
    }
    Map<String, Object> runs = JsonViews.runs(snapshot);
    cachedRuns = new CachedRuns(snapshot, runs);
    return runs;
  }

  private static final class CachedRuns {
    private final Snapshot snapshot;
    private final Map<String, Object> runs;

    CachedRuns(Snapshot snapshot, Map<String, Object> runs) {
      this.snapshot = snapshot;
      this.runs = runs;
    }
  }

  private static final class CachedView {
    private final Snapshot snapshot;
    private final String run;
    private final ResultView view;

    CachedView(Snapshot snapshot, String run, ResultView view) {
      this.snapshot = snapshot;
      this.run = run;
      this.view = view;
    }
  }

  private void message(HttpExchange exchange, Snapshot snapshot, List<String> path)
      throws IOException {
    MailRecord mail = snapshot.mail(path.get(0));
    if (mail == null) {
      Http.sendError(exchange, 404, MESSAGE_GONE);
      return;
    }
    byte[] eml;
    MimeMessage message;
    try {
      eml = Files.readAllBytes(mail.eml());
      message = MailReader.parse(eml, mail.eml());
    } catch (NoSuchFileException e) {
      Http.sendError(exchange, 404, MESSAGE_GONE);
      return;
    } catch (IOException e) {
      Http.sendError(exchange, cannotReadStatus(e), cannotRead(e));
      return;
    }
    Run run = snapshot.runOfMail(mail.id());
    if (path.size() == 1) {
      Http.sendJson(
          exchange, 200, views.mailDetail(mail, message, snapshot.sourceOfRun(run.id()), run));
    } else if (path.size() == 2 && path.get(1).equals("raw")) {
      raw(exchange, mail, eml, message);
    } else if (path.size() >= 3 && path.size() <= 4 && path.get(1).equals("attachments")) {
      attachment(exchange, mail, message, path.get(2), path.size() == 4 ? path.get(3) : null);
    } else {
      Http.sendError(exchange, 404, "Not found: /api/messages/" + String.join("/", path));
    }
  }

  private void raw(HttpExchange exchange, MailRecord mail, byte[] eml, MimeMessage message)
      throws IOException {
    String fileName = fileNames.sanitize(emlFileName(MailReader.subject(message), mail.id()), "");
    Http.sendFile(exchange, "message/rfc822", "attachment", fileName, eml);
  }

  private static String cannotRead(IOException e) {
    return "This message could not be read: " + e;
  }

  /**
   * 503 for a mail that may be read later, such as one held by another program, so that the UI
   * retries; 500 for a file that is not a readable mail.
   */
  static int cannotReadStatus(IOException e) {
    return e.getCause() instanceof MessagingException ? 500 : 503;
  }

  // The sanitizer would treat slashes, as in 11/16, as directory separators.
  static String emlFileName(String subject, String id) {
    String name =
        subject == null || subject.isBlank()
            ? "message-" + id
            : subject.trim().replace('/', '_').replace('\\', '_');
    return name.toLowerCase(Locale.ROOT).endsWith(".eml") ? name : name + ".eml";
  }

  private void attachment(
      HttpExchange exchange, MailRecord mail, MimeMessage message, String id, String view)
      throws IOException {
    if (view != null && !view.equals("inline") && !view.equals("text")) {
      Http.sendError(exchange, 404, "Not found: " + view);
      return;
    }
    ParsedAttachment attachment = parser.findAttachment(message, id);
    if (attachment == null) {
      Http.sendError(exchange, 404, "This attachment is no longer available.");
      return;
    }
    AttachmentSummary summary = attachment.summary();
    if (attachment.bytes() == null) {
      Http.sendError(exchange, 422, summary.decodeError());
      return;
    }
    String fileName = fileNames.sanitize(summary.fileName(), summary.id());
    String mediaType = MimeMessageParser.mediaType(summary.contentType());
    if (view == null) {
      Http.sendFile(
          exchange,
          MEDIA_TYPE.matcher(mediaType).matches() ? mediaType : "application/octet-stream",
          "attachment",
          fileName,
          attachment.bytes());
    } else if (view.equals("inline")) {
      String unavailable =
          AttachmentPreviewSupport.imageUnavailableReason(
              summary.contentType(), attachment.bytes().length);
      if (AttachmentPreviewSupport.UNAVAILABLE_EMPTY.equals(unavailable)) {
        Http.sendError(exchange, 422, "This image is empty.");
        return;
      }
      if (unavailable != null) {
        Http.sendError(exchange, 415, "Only images can be shown inline: " + mediaType);
        return;
      }
      Http.sendFile(exchange, mediaType, "inline", fileName, attachment.bytes());
    } else {
      textPreview(exchange, summary, attachment.bytes());
    }
  }

  private static void textPreview(HttpExchange exchange, AttachmentSummary summary, byte[] bytes)
      throws IOException {
    String unavailable =
        AttachmentPreviewSupport.textUnavailableReason(
            summary.contentType(), summary.fileName(), bytes.length);
    if (AttachmentPreviewSupport.UNAVAILABLE_TOO_LARGE.equals(unavailable)) {
      Http.sendError(exchange, 413, "This attachment is too large to be shown as text.");
      return;
    }
    if (unavailable != null) {
      Http.sendError(exchange, 415, "This attachment cannot be shown as text.");
      return;
    }
    Charset charset = AttachmentPreviewSupport.previewCharset(summary.contentType());
    Http.sendJson(exchange, 200, JsonViews.textPreview(bytes, charset));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
