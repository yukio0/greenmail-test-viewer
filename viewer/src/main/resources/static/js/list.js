// The mail list of the selected node of the tree.
import { h, replace } from "./dom.js";
import {
  className,
  formatDateTime,
  formatRecipients,
  formatShortDateTime,
  isFailure,
  qualifiedTestName,
  resultMarker,
} from "./format.js";

export function renderList(container, messages, view, handlers) {
  const focused = container.querySelector(".message-card:focus");
  const focusedId = focused?.dataset.id ?? null;
  const terms = view.query.toLowerCase().split(/\s+/).filter((term) => term !== "");
  const scrollTop = container.scrollTop;
  const anchor = topCard(container);
  if (messages.length === 0) {
    replace(
      container,
      view.searched != null
        ? noRecords()
        : h(
            "div",
            { className: "empty-list" },
            h("p", { text: "No messages" }),
            view.query !== "" ? h("p", { text: "Try other search terms." }) : null,
          ),
    );
  } else {
    replace(
      container,
      h(
        "ul",
        {
          className: "message-list",
          onKeydown: (event) => onKeydown(event, handlers),
        },
        messages.map((message) =>
          h(
            "li",
            null,
            card(message, message.id === view.messageId, view.newIds.has(message.id), terms, handlers),
          ),
        ),
      ),
      messages.length < view.total
        ? h(
            "div",
            { className: "list-more" },
            h("p", { text: `Showing ${messages.length} of ${view.total}` }),
            h("button", {
              type: "button",
              className: "button button-secondary button-small",
              text: `Show ${view.more} more`,
              onClick: handlers.onMore,
            }),
          )
        : null,
    );
  }
  const cards = [...container.querySelectorAll(".message-card")];
  container.scrollTop = scrollTop;
  const anchorCard = anchor == null ? null : cards.find((card) => card.dataset.id === anchor.id);
  if (anchor != null && anchorCard != null) {
    container.scrollTop += offsetOf(anchorCard, container) - anchor.offset;
  }
  const selected = cards.find((card) => card.dataset.id === view.messageId);
  const current = cards.find((card) => card.dataset.id === focusedId) ?? selected ?? cards[0];
  if (current != null) {
    current.tabIndex = 0;
    if (focusedId != null) {
      current.focus({ preventScroll: true });
    }
  }
  if (view.reveal) {
    selected?.scrollIntoView({ block: "nearest" });
  }
}

// Moves the focus to a mail without scrolling.
export function focusCard(container, index) {
  const cards = [...container.querySelectorAll(".message-card")];
  const card = cards[index];
  if (card == null) {
    return;
  }
  for (const other of cards) {
    other.tabIndex = -1;
  }
  card.tabIndex = 0;
  card.focus({ preventScroll: true });
}

export function scrollToNew(container) {
  container.querySelector(".message-card.new")?.scrollIntoView({ block: "nearest" });
}

function topCard(container) {
  if (container.scrollTop === 0) {
    return null;
  }
  for (const card of container.querySelectorAll(".message-card")) {
    const offset = offsetOf(card, container);
    if (offset + card.offsetHeight > 0) {
      return { id: card.dataset.id ?? "", offset };
    }
  }
  return null;
}

function offsetOf(card, container) {
  return card.getBoundingClientRect().top - container.getBoundingClientRect().top;
}

function noRecords() {
  return h("div", { className: "empty-list" }, h("p", { text: "No records found yet." }));
}

function onKeydown(event, handlers) {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return;
  }
  const cards = [...event.currentTarget.querySelectorAll(".message-card")];
  const index = cards.indexOf(event.target);
  if (index < 0) {
    return;
  }
  const moves = { ArrowDown: index + 1, ArrowUp: index - 1, Home: 0, End: cards.length - 1 };
  if (!(event.key in moves)) {
    return;
  }
  event.preventDefault();
  const next = cards[moves[event.key]];
  if (next == null || next === cards[index]) {
    return;
  }
  next.focus();
  handlers.onSelect(next.dataset.id ?? "", true);
}

function card(message, selected, isNew, terms, handlers) {
  const attachments = message.attachmentCount;
  // Passing is expected, so only the other results are marked.
  const result =
    isFailure(message.testResult) || message.testResult === "aborted"
      ? resultMarker(message.testResult)
      : null;
  return h(
    "button",
    {
      type: "button",
      className: ["message-card", selected ? "selected" : null, isNew ? "new" : null]
        .filter((name) => name != null)
        .join(" "),
      tabindex: "-1",
      "aria-current": selected ? "true" : null,
      dataset: { id: message.id },
      onClick: () => handlers.onSelect(message.id, false),
    },
    h(
      "span",
      { className: "message-subject" },
      isNew ? h("span", { className: "new-marker", text: "New" }) : null,
      marked(message.subject ?? "(no subject)", terms),
    ),
    h(
      "span",
      { className: "message-address" },
      marked(`${message.from ?? "(unknown sender)"} \u2192 ${formatRecipients(message.to)}`, terms),
    ),
    h(
      "span",
      { className: "message-test" },
      result != null ? h("span", { className: `marker ${result.className}`, text: result.label }) : null,
      marked(sentBy(message), terms),
    ),
    message.preview != null
      ? h("span", { className: "message-preview" }, marked(message.preview, terms))
      : null,
    h(
      "span",
      { className: "message-meta" },
      h("span", {
        className: "message-date",
        text: formatShortDateTime(message.sentAt ?? message.capturedAt),
        title: formatDateTime(message.sentAt ?? message.capturedAt),
      }),
      attachments > 0 ? attachmentCount(attachments) : null,
    ),
  );
}

function attachmentCount(count) {
  const words = count === 1 ? "1 attachment" : `${count} attachments`;
  return h(
    "span",
    { className: "message-attachments", title: words },
    h("span", { className: "visually-hidden", text: words }),
    h("span", { "aria-hidden": "true", text: count }),
  );
}

function marked(text, terms) {
  if (terms.length === 0) {
    return text;
  }
  const lower = text.toLowerCase();
  // Some characters, such as U+0130, become longer in lower case, which shifts the positions after
  // them.
  const places = lower.length === text.length ? null : placesInText(text);
  const ranges = [];
  for (const term of terms) {
    for (let index = lower.indexOf(term); index >= 0; index = lower.indexOf(term, index + term.length)) {
      const end = index + term.length;
      ranges.push(places == null ? [index, end] : [places.start[index], places.end[end - 1]]);
    }
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const parts = [];
  let position = 0;
  for (const [start, end] of ranges) {
    if (end <= position) {
      continue;
    }
    const from = Math.max(start, position);
    parts.push(text.slice(position, from), h("mark", { text: text.slice(from, end) }));
    position = end;
  }
  parts.push(text.slice(position));
  return parts;
}

function placesInText(text) {
  const start = [];
  const end = [];
  let position = 0;
  for (const character of text) {
    for (let i = 0; i < character.toLowerCase().length; i++) {
      start.push(position);
      end.push(position + character.length);
    }
    position += character.length;
  }
  return { start, end };
}

export function sentBy(message) {
  switch (message.phase) {
    case "test":
      return qualifiedTestName(message);
    case "outsideTest":
      return `(Outside tests) \u00b7 ${className(message.testClass, message.classDisplayName)}`;
    case "ambiguous":
      return `(Ambiguous) Possible tests: ${message.candidates.map(qualifiedTestName).join(", ")}`;
    default:
      return "(Outside test classes)";
  }
}
