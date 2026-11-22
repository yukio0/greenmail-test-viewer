// The detail pane: the selected mail, or else the selected test or class.
import { ApiError, MESSAGE_GONE, UnreachableError, api, download } from "./api.js";
import { h, replace, sandboxedFrame } from "./dom.js";
import {
  className,
  formatBytes,
  formatDateTime,
  formatRecipients,
  isFailure,
  qualifiedTestName,
  resultMarker,
  testName,
  toErrorMessage,
} from "./format.js";

export const TABS = [
  ["html", "HTML"],
  ["htmlSource", "HTML Source"],
  ["text", "Text"],
  ["headers", "Headers"],
  ["raw", "Raw"],
  ["links", "Links"],
];

const COPIED_MESSAGE_MS = 2000;

const PANEL_ID = "detail-panel";

const PREVIEW_MODES = [
  ["desktop", "Desktop", "100%"],
  ["tablet", "Tablet", "768px"],
  ["mobile", "Mobile", "390px"],
];

export function renderDetail(container, detail, view, handlers) {
  const tabFocused = container.querySelector('[role="tab"]:focus') != null;
  switch (detail.status) {
    case "none":
      if (view.searched != null) {
        replace(container, noRecordsView(view.searched));
      } else {
        replace(
          container,
          view.node != null
            ? nodeView(view.node, handlers)
            : empty("Select a message", "Choose a message to see its content, headers and attachments."),
        );
      }
      return;
    case "loading":
      replace(container, empty("Loading\u2026"));
      return;
    case "gone":
      replace(
        container,
        empty(
          MESSAGE_GONE,
          "Its records were deleted, for example when old runs were cleaned up or the build was cleaned. Choose another message.",
        ),
      );
      return;
    case "error":
      replace(container, empty("This message could not be loaded.", detail.error));
      return;
    default:
      replace(container, messageView(container, detail.message, view, handlers));
      // The tabs are redrawn, so the focus moves to the new one.
      if (tabFocused) {
        container.querySelector('[role="tab"][aria-selected="true"]')?.focus();
      }
  }
}

// Redraws only the information about the mail, keeping its body, tab and scroll positions.
export function renderDetailMeta(container, message, view, handlers) {
  container.querySelector(".detail-info")?.replaceWith(info(container, message, view, handlers));
}

// The chosen tab if the mail has content for it, or else a body that the mail has.
export function availableTab(message, tab) {
  if (hasContent(message, tab)) {
    return tab;
  }
  if (message.htmlBody != null) {
    return "html";
  }
  if (message.textBody != null) {
    return "text";
  }
  return tab;
}

function hasContent(message, tab) {
  switch (tab) {
    case "html":
      return message.htmlBody != null;
    case "htmlSource":
      return message.htmlSource != null;
    case "text":
      return message.textBody != null;
    case "links":
      return message.links.length > 0;
    default:
      return true;
  }
}

function messageView(container, message, view, handlers) {
  return h(
    "article",
    { className: "message-detail" },
    info(container, message, view, handlers),
    tabs(message, view, handlers),
    h(
      "div",
      {
        className: "detail-body",
        role: "tabpanel",
        id: PANEL_ID,
        "aria-labelledby": tabId(view.tab),
      },
      tabBody(message, view, handlers),
    ),
  );
}

function info(container, message, view, handlers) {
  const status = h("p", { className: "action-status", role: "status" });
  const attachments = message.attachments.length;
  const more = h(
    "details",
    {
      className: "detail-more",
      open: view.moreDetails,
      onToggle: () => handlers.onMoreDetails(more.open),
    },
    h("summary", { text: "More details" }),
    meta(message),
  );
  return h(
    "div",
    { className: "detail-info" },
    h(
      "div",
      { className: "detail-header" },
      h(
        "div",
        { className: "detail-heading" },
        h("h2", { text: message.subject ?? "(no subject)" }),
        h("p", {
          className: "detail-summary",
          text: `${message.from ?? "(unknown sender)"} \u2192 ${formatRecipients(message.to)}`,
        }),
        h(
          "p",
          { className: "detail-summary" },
          testSummary(message),
          h("span", { className: "detail-date", text: formatDateTime(message.sentAt ?? message.capturedAt) }),
        ),
      ),
      h(
        "div",
        { className: "detail-header-actions" },
        h(
          "div",
          { className: "detail-header-buttons" },
          attachments > 0
            ? h("button", {
                type: "button",
                className: "button button-secondary button-small",
                text: attachments === 1 ? "1 attachment" : `${attachments} attachments`,
                title: "Show the attachments",
                onClick: () => showAttachments(container, message, view, handlers),
              })
            : null,
          downloadButton("Download .eml", message.rawUrl, "message.eml", status),
        ),
        status,
      ),
    ),
    failure(message.testResult, message.testError),
    more,
  );
}

function testSummary(message) {
  switch (message.phase) {
    case "test": {
      const result = resultMarker(message.testResult);
      return h(
        "span",
        { className: "detail-test" },
        h("span", {
          className: `marker ${result.className}`,
          title: message.testResult == null ? "The test has not finished, or its result was not recorded." : null,
          text: result.label,
        }),
        qualifiedTestName(message),
      );
    }
    case "outsideTest":
      return h("span", { className: "detail-test", text: `(Outside tests) \u00b7 ${className(message.testClass, message.classDisplayName)}` });
    case "ambiguous":
      return h("span", { className: "detail-test", text: "(Ambiguous)" });
    default:
      return h("span", { className: "detail-test", text: "(Outside test classes)" });
  }
}

function failure(result, error) {
  if (!isFailure(result) && result !== "aborted") {
    return null;
  }
  const title =
    result === "failed"
      ? "The test failed"
      : result === "aborted"
        ? "The test was aborted"
        : "The setup of the test class failed";
  return failureBox(title, resultMarker(result).className, error);
}

function classFailureBox(stage, record) {
  const aborted = record.result === "aborted";
  return failureBox(
    `The ${stage} of the test class ${aborted ? "was aborted" : "failed"}`,
    aborted ? "aborted" : `class-${stage}-failed`,
    record.error,
  );
}

function failureBox(title, kind, error) {
  return h(
    "section",
    { className: `detail-failure ${kind}`, "aria-label": title },
    h("h3", { text: title }),
    error != null ? h("pre", { className: "detail-failure-error", text: error }) : null,
  );
}

function nodeView(node, handlers) {
  if (node.kind === "test") {
    const test = node.test;
    const result = resultMarker(test.result);
    return nodeArticle(
      "Test",
      qualifiedTestName(test),
      `${test.testClass ?? ""}#${test.testMethod ?? ""}`,
      [
        h("span", {
          className: `marker ${result.className}`,
          title: test.result == null ? "The test has not finished, or its result was not recorded." : null,
          text: result.label,
        }),
        runText(test.run, test.fromEarlierRun),
      ],
      [failure(test.result, test.error)],
      test.mailCount === 0
        ? "This test sent no messages."
        : `This test sent ${messages(test.mailCount)}. Choose one in the list to see it.`,
    );
  }
  const testClass = node.testClass;
  return nodeArticle(
    "Test class",
    className(testClass.testClass, testClass.classDisplayName),
    testClass.testClass ?? "",
    [
      testClass.tests.length === 1 ? "1 test" : `${testClass.tests.length} tests`,
      testClass.failureCount > 0
        ? h("span", { className: "marker failed", text: `${testClass.failureCount} failed` })
        : null,
      runText(testClass.run, testClass.fromEarlierRun),
    ],
    [
      testClass.setupFailure != null ? classFailureBox("setup", testClass.setupFailure) : null,
      testClass.teardownFailure != null ? classFailureBox("teardown", testClass.teardownFailure) : null,
      testsNotPassed(testClass, handlers),
    ],
    testClass.mailCount === 0
      ? "This class sent no messages."
      : `This class sent ${messages(testClass.mailCount)}. Choose one in the list to see it.`,
  );
}

function testsNotPassed(testClass, handlers) {
  const tests = testClass.tests.filter(
    (test) => isFailure(test.result) || test.result === "aborted",
  );
  if (tests.length === 0) {
    return null;
  }
  return h(
    "section",
    { className: "node-tests", "aria-label": "Tests that did not pass" },
    h("h3", { text: "Tests that did not pass" }),
    h(
      "ul",
      null,
      tests.map((test) => {
        const result = resultMarker(test.result);
        const firstLine = test.result === "classSetupFailed" ? null : errorSummary(test.error);
        return h(
          "li",
          null,
          h(
            "button",
            {
              type: "button",
              className: "node-test",
              title: `Show ${testName(test)}`,
              onClick: () => handlers.onSelectTest(test.uniqueId),
            },
            h("span", { className: `marker ${result.className}`, text: result.label }),
            h("span", { className: "node-test-name", text: testName(test) }),
            firstLine ? h("span", { className: "node-test-error", text: firstLine }) : null,
          ),
        );
      }),
    ),
  );
}

// The first line of an error, and the next line when the message starts there, as AssertJ and
// opentest4j format it.
function errorSummary(error) {
  const lines = (error ?? "")
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "");
  if (lines.length === 0) {
    return null;
  }
  return lines[0].endsWith(":") && lines.length > 1 ? `${lines[0]} ${lines[1]}` : lines[0];
}

function nodeArticle(label, title, note, summary, failures, mails) {
  return h(
    "article",
    { className: "node-detail" },
    h(
      "div",
      { className: "detail-header" },
      h(
        "div",
        { className: "detail-heading" },
        h("p", { className: "node-detail-label", text: label }),
        h("h2", { text: title }),
        h("p", { className: "detail-meta-note", text: note }),
        h("p", { className: "detail-summary" }, summary),
      ),
    ),
    failures,
    h("p", { className: "node-detail-mails", text: mails }),
  );
}

function runText(run, fromEarlierRun) {
  const startedAt = formatDateTime(run?.startedAt);
  return h("span", {
    className: "detail-date",
    text: fromEarlierRun ? `From an earlier run (${startedAt})` : `Run started ${startedAt}`,
  });
}

function messages(count) {
  return count === 1 ? "1 message" : `${count} messages`;
}

function showAttachments(container, message, view, handlers) {
  if (view.tab !== "html" && view.tab !== "text") {
    handlers.onTab(availableTab(message, "html"));
  }
  const panel = container.querySelector(".attachments-panel");
  panel?.scrollIntoView({ block: "start", behavior: "smooth" });
  panel?.querySelector("h3")?.focus({ preventScroll: true });
}

function meta(message) {
  const items = [
    item("Sent by test", sentBy(message), "test", true),
    message.phase === "ambiguous"
      ? item(
          "Possible tests",
          message.candidates.map(qualifiedTestName).join(", "),
          "test",
          true,
        )
      : null,
    item("From", message.from ?? "(unknown)"),
    item("To", formatRecipients(message.to)),
    message.cc.length > 0 ? item("Cc", formatRecipients(message.cc)) : null,
    message.bcc.length > 0 ? item("Bcc", formatRecipients(message.bcc)) : null,
    message.replyTo.length > 0 ? item("Reply-To", formatRecipients(message.replyTo)) : null,
    item("Delivered to", formatRecipients(message.deliveredTo)),
    item("Date", formatDateTime(message.sentAt)),
    item("Captured", formatDateTime(message.capturedAt)),
    item("Module", message.module.name),
    item(
      "Run",
      `${formatDateTime(message.run.startedAt)}${message.run.finished ? "" : " (not finished)"}`,
    ),
  ];
  return h("dl", { className: "detail-meta" }, items);
}

function item(label, value, valueClass, wide) {
  return h(
    "div",
    { className: wide ? "detail-meta-item wide" : "detail-meta-item" },
    h("dt", { text: label }),
    h("dd", { className: valueClass }, value),
  );
}

function sentBy(message) {
  switch (message.phase) {
    case "test":
      return [
        qualifiedTestName(message),
        h("small", {
          className: "detail-meta-note",
          text: `${message.testClass ?? ""}#${message.testMethod ?? ""}`,
        }),
      ];
    case "outsideTest":
      return [
        `(Outside tests) \u00b7 ${className(message.testClass, message.classDisplayName)}`,
        h("small", { className: "detail-meta-note", text: message.testClass ?? "" }),
      ];
    case "ambiguous":
      return "(Ambiguous)";
    default:
      return "(Outside test classes)";
  }
}

function tabId(tab) {
  return `detail-tab-${tab}`;
}

function tabs(message, view, handlers) {
  const keys = TABS.map(([tab]) => tab);
  const index = keys.indexOf(view.tab);
  return h(
    "div",
    {
      className: "detail-tabs",
      role: "tablist",
      "aria-label": "Message views",
      onKeydown: (event) => {
        const moves = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: keys.length - 1 };
        if (event.altKey || event.ctrlKey || event.metaKey || !(event.key in moves)) {
          return;
        }
        event.preventDefault();
        handlers.onTab(keys[(moves[event.key] + keys.length) % keys.length]);
      },
    },
    TABS.map(([tab, label]) => {
      const active = tab === view.tab;
      const classes = ["detail-tab", active ? "active" : null, hasContent(message, tab) ? null : "no-content"];
      return h(
        "button",
        {
          type: "button",
          role: "tab",
          id: tabId(tab),
          className: classes.filter((name) => name != null).join(" "),
          "aria-selected": String(active),
          "aria-controls": PANEL_ID,
          tabindex: active ? "0" : "-1",
          title: hasContent(message, tab) ? null : "This message has nothing to show here.",
          onClick: () => handlers.onTab(tab),
        },
        label,
        tab === "links" && message.links.length > 0
          ? h("span", { className: "tab-count", text: message.links.length })
          : null,
      );
    }),
  );
}

function tabBody(message, view, handlers) {
  switch (view.tab) {
    case "htmlSource":
      return message.htmlSource != null
        ? h("pre", { className: "source-view", text: message.htmlSource })
        : empty("No HTML body", "This message does not contain an HTML body.");
    case "text":
      return h(
        "div",
        { className: "body-with-attachments" },
        message.textBody != null
          ? h("pre", { className: "text-preview body-content" }, linkedText(message.textBody, message.links))
          : empty("No text body", "This message does not contain a text body."),
        attachmentsPanel(message),
      );
    case "headers":
      return headers(message);
    case "raw":
      return raw(message);
    case "links":
      return linksView(message);
    default:
      return h(
        "div",
        { className: "body-with-attachments" },
        message.htmlBody != null
          ? htmlPreview(message, view, handlers)
          : empty("No HTML body", "This message does not contain an HTML body."),
        attachmentsPanel(message),
      );
  }
}

function htmlPreview(message, view, handlers) {
  const shell = h("div", { className: `html-preview-frame-shell ${view.previewMode}` });
  shell.appendChild(sandboxedFrame(message.htmlBody, "HTML body of the message"));
  const viewport = h("div", { className: `html-preview-viewport ${view.previewMode}` }, shell);
  const buttons = PREVIEW_MODES.map(([mode, label, size]) =>
    h(
      "button",
      {
        type: "button",
        className:
          mode === view.previewMode ? "html-preview-mode-button active" : "html-preview-mode-button",
        "aria-pressed": String(mode === view.previewMode),
        dataset: { mode },
        onClick: () => {
          // Only the size changes, so the frame is kept.
          viewport.className = `html-preview-viewport ${mode}`;
          shell.className = `html-preview-frame-shell ${mode}`;
          for (const button of buttons) {
            const active = button.dataset.mode === mode;
            button.className = active ? "html-preview-mode-button active" : "html-preview-mode-button";
            button.setAttribute("aria-pressed", String(active));
          }
          handlers.onPreviewMode(mode);
        },
      },
      h("span", { text: label }),
      h("small", { text: size }),
    ),
  );
  return h(
    "div",
    { className: "html-preview-panel body-content body-content-html" },
    cidStatus(message.cidImages),
    h(
      "div",
      { className: "html-preview-toolbar" },
      h("span", { className: "html-preview-toolbar-label", text: "Preview size" }),
      h("div", { className: "html-preview-mode-buttons", role: "group" }, buttons),
    ),
    viewport,
  );
}

function cidStatus(cidImages) {
  const missing = cidImages.missingContentIds;
  const notShown = cidImages.notShownImages;
  if (cidImages.resolvedCount === 0 && missing.length === 0 && notShown.length === 0) {
    return null;
  }
  const warning = missing.length > 0 || notShown.length > 0;
  return h(
    "div",
    { className: warning ? "cid-images-status warning" : "cid-images-status", role: "note" },
    h("span", { text: `Resolved inline images: ${cidImages.resolvedCount}` }),
    missing.length > 0
      ? h(
          "span",
          null,
          "Missing inline images: ",
          missing.map((contentId, index) => [index > 0 ? ", " : null, h("code", { text: contentId })]),
        )
      : null,
    notShown.length > 0
      ? h(
          "span",
          null,
          "Inline images not shown: ",
          notShown.map((image, index) => [
            index > 0 ? ", " : null,
            h("code", { text: image.contentId }),
            ` (${notShownReason(image)})`,
          ]),
        )
      : null,
  );
}

function notShownReason(image) {
  switch (image.reason) {
    case "undecodable":
      return "could not be decoded";
    case "empty":
      return "empty";
    default:
      return image.contentType;
  }
}

function attachmentsPanel(message) {
  const attachments = message.attachments;
  if (attachments.length === 0) {
    return null;
  }
  const files = attachments.filter((attachment) => !attachment.inlineImage);
  const images = attachments.filter((attachment) => attachment.inlineImage);
  return h(
    "section",
    { className: "attachments-panel" },
    h(
      "div",
      { className: "attachments-panel-header" },
      h("h3", { tabindex: "-1", text: "Attachments" }),
      h("span", { className: "count", text: attachments.length }),
    ),
    h(
      "div",
      { className: "attachment-sections" },
      files.length > 0 ? attachmentSection("Files", files) : null,
      images.length > 0 ? attachmentSection("Inline images", images) : null,
    ),
  );
}

function attachmentSection(title, attachments) {
  return h(
    "div",
    { className: "attachment-section" },
    h("h4", { text: title }),
    h(
      "ul",
      { className: "attachment-list" },
      attachments.map((attachment) => attachmentItem(attachment)),
    ),
  );
}

function attachmentItem(attachment) {
  const name = attachment.fileName ?? `(unnamed attachment ${attachment.id})`;
  const status = h("p", { className: "action-status", role: "status" });
  const metaItems = [
    ["Type", mediaType(attachment.contentType), attachment.contentType],
    ["Size", formatBytes(attachment.size)],
    attachment.contentId != null
      ? ["Content-ID", attachment.contentId]
      : ["Disposition", attachment.disposition ?? "(none)"],
  ];
  return h(
    "li",
    { className: "attachment-item" },
    h(
      "div",
      { className: "attachment-info" },
      h("span", { className: "attachment-name", text: name }),
      h(
        "dl",
        { className: "attachment-meta-list" },
        metaItems.map(([label, value, title]) =>
          h("div", null, h("dt", { text: label }), h("dd", { title, text: value })),
        ),
      ),
      attachment.decodeError != null
        ? h("p", { className: "attachment-decode-error", text: attachment.decodeError })
        : null,
    ),
    h(
      "div",
      { className: "attachment-actions" },
      attachment.downloadUrl != null ? downloadButton("Download", attachment.downloadUrl, name, status) : null,
      status,
    ),
    attachment.previewAvailable ? preview(attachment, attachment.previewKind) : null,
  );
}

function mediaType(contentType) {
  const [type, ...parameters] = contentType.split(";").map((part) => part.trim());
  const charset = parameters.find((parameter) => /^charset=/i.test(parameter));
  return charset != null ? `${type}; ${charset}` : type;
}

function preview(attachment, kind) {
  const content = h("div");
  const details = h("details", { className: "attachment-preview" }, h("summary", { text: "Preview" }), content);
  let loaded = false;
  details.addEventListener("toggle", async () => {
    if (!details.open || loaded) {
      return;
    }
    loaded = true;
    if (kind === "image") {
      replace(
        content,
        h(
          "div",
          { className: "attachment-preview-image-wrap" },
          h("img", {
            className: "attachment-preview-image",
            src: attachment.inlineUrl,
            alt: attachment.fileName ?? "",
            onError: () => {
              loaded = false;
              replace(content, note("The image could not be loaded. Close and open the preview to try again.", true));
            },
          }),
        ),
      );
      return;
    }
    replace(content, note("Loading\u2026", false));
    try {
      const text = await api.attachmentText(attachment.textUrl);
      replace(content, h("pre", { className: "attachment-preview-text", text: text.text }));
    } catch (error) {
      loaded = false;
      replace(content, note(failureMessage(error), true));
    }
  });
  return details;
}

function downloadButton(label, url, fallbackName, status) {
  const button = h("button", {
    type: "button",
    className: "button button-secondary button-small",
    text: label,
    onClick: async () => {
      button.setAttribute("disabled", "");
      status.textContent = "";
      status.className = "action-status";
      try {
        await download(url, fallbackName);
      } catch (error) {
        status.textContent = failureMessage(error);
        status.className = "action-status error";
      } finally {
        button.removeAttribute("disabled");
      }
    },
  });
  return button;
}

// The server finds the addresses (MailLinks), so that the text and the Links tab show the same
// links.
function linkedText(text, links) {
  const urls = links
    .filter((link) => link.inText)
    .map((link) => link.url)
    // Longer first, so that an address that is a prefix of another one does not replace it.
    .sort((a, b) => b.length - a.length);
  const ranges = [];
  for (const url of urls) {
    for (let index = text.indexOf(url); index >= 0; index = text.indexOf(url, index + url.length)) {
      const end = index + url.length;
      if (!ranges.some(([start, stop]) => index < stop && end > start)) {
        ranges.push([index, end]);
      }
    }
  }
  if (ranges.length === 0) {
    return text;
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const parts = [];
  let position = 0;
  for (const [start, end] of ranges) {
    const url = text.slice(start, end);
    parts.push(text.slice(position, start), h("a", { href: url, target: "_blank", rel: "noopener noreferrer", text: url }));
    position = end;
  }
  parts.push(text.slice(position));
  return parts;
}

function linksView(message) {
  if (message.links.length === 0) {
    return empty("No links", "This message does not contain links.");
  }
  return h("div", { className: "links-tab" }, h("ul", { className: "link-list" }, message.links.map(linkItem)));
}

function linkItem(link) {
  const status = h("span", { className: "action-status", role: "status" });
  return h(
    "li",
    { className: "link-item" },
    h(
      "div",
      { className: "link-info" },
      link.text != null ? h("span", { className: "link-text", text: link.text }) : null,
      h("code", { className: "link-url", text: link.url }),
      h(
        "span",
        { className: "link-sources" },
        link.inHtml ? h("span", { className: "link-source", text: "HTML" }) : null,
        link.inText ? h("span", { className: "link-source", text: "Text" }) : null,
      ),
    ),
    h(
      "div",
      { className: "link-actions" },
      h("button", {
        type: "button",
        className: "button button-secondary button-small",
        text: "Copy",
        onClick: () => copy(link.url, status),
      }),
      // The server lists only links that can be opened (HtmlMailSanitizer.isOpenableLink).
      h("a", {
        className: "button button-secondary button-small",
        href: link.url,
        target: "_blank",
        rel: "noopener noreferrer",
        text: "Open",
      }),
      status,
    ),
  );
}

async function copy(text, status) {
  let copied;
  try {
    await navigator.clipboard.writeText(text);
    copied = true;
  } catch {
    // The clipboard API needs a secure context, which the viewer is not when opened by a host name.
    const field = h("textarea", { className: "copy-field", readonly: true });
    field.value = text;
    document.body.appendChild(field);
    field.select();
    copied = document.execCommand("copy");
    field.remove();
  }
  status.textContent = copied ? "Copied" : "Could not copy. Select the address to copy it.";
  setTimeout(() => {
    status.textContent = "";
  }, COPIED_MESSAGE_MS);
}

function headers(message) {
  return h(
    "div",
    { className: "headers-tab" },
    h(
      "table",
      { className: "headers-table" },
      h("thead", null, h("tr", null, h("th", { text: "Name" }), h("th", { text: "Value" }))),
      h(
        "tbody",
        null,
        message.headers.map((header) =>
          h("tr", null, h("th", { text: header.name }), h("td", { text: header.value })),
        ),
      ),
    ),
  );
}

function raw(message) {
  return h(
    "div",
    { className: "source-panel" },
    message.partTree != null
      ? h(
          "details",
          { className: "source-metadata" },
          h("summary", { text: "MIME structure" }),
          h("div", { className: "source-part-tree-body" }, h("ul", { className: "mime-tree" }, mimePart(message.partTree))),
        )
      : null,
    h("pre", { className: "source-view", text: message.source }),
  );
}

function mimePart(part) {
  const details = [
    part.charset != null ? `charset=${part.charset}` : null,
    part.transferEncoding,
    part.disposition,
    part.fileName,
    part.contentId != null ? `<${part.contentId}>` : null,
    part.size != null ? formatBytes(part.size) : null,
  ].filter((value) => value != null);
  return h(
    "li",
    null,
    h(
      "div",
      { className: "mime-tree-row" },
      h("span", { className: "mime-tree-kind", text: part.mediaType }),
      details.map((value) => h("span", { className: "mime-tree-meta", text: value })),
    ),
    part.children.length > 0 ? h("ul", { className: "mime-tree" }, part.children.map(mimePart)) : null,
  );
}

function empty(title, description) {
  return h(
    "div",
    { className: "empty-detail" },
    h("h3", { text: title }),
    description != null ? h("p", { text: description }) : null,
  );
}

function noRecordsView(searched) {
  const directories = searched.directories;
  return h(
    "div",
    { className: "empty-detail no-records" },
    h("h3", { text: "No records found yet." }),
    searched.problems > 0
      ? h("p", {
          className: "empty-problems",
          text: "Some records could not be read. See the read problems at the top.",
        })
      : null,
    h(
      "ol",
      { className: "first-steps" },
      h("li", { text: "Add @GreenMailTestViewer to the test classes that use GreenMail." }),
      h("li", { text: "Run the tests. New records appear here automatically." }),
    ),
    directories.length > 0
      ? [
          h("p", { text: "Looking for records below:" }),
          h(
            "ul",
            { className: "searched-directories" },
            directories.map((directory) => h("li", null, h("code", { text: directory }))),
          ),
        ]
      : null,
  );
}

function note(text, error) {
  return h("p", { className: error ? "attachment-preview-note error" : "attachment-preview-note", text });
}

// Downloads and previews are not retried automatically, unlike the list and the detail.
function failureMessage(error) {
  if (error instanceof ApiError && error.status === 404) {
    return MESSAGE_GONE;
  }
  if (error instanceof UnreachableError) {
    return "The viewer cannot be reached. It may have stopped. Try again once it is running.";
  }
  return toErrorMessage(error);
}
