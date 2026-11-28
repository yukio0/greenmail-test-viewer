// Starts the UI: holds the state, loads the data from the API and redraws each pane when its data
// changes. The records are checked every few seconds, and the selection is kept in the hash of the
// URL.
import { ApiError, UnreachableError, api } from "./api.js";
import { availableTab, renderDetail, renderDetailMeta } from "./detail.js";
import { h, replace } from "./dom.js";
import { formatDateTime, toErrorMessage } from "./format.js";
import { focusCard, renderList, scrollToNew } from "./list.js";
import { setUpPaneResizers } from "./panes.js";
import { ALL, failureCount, findNode, findSelection, findTest, renderTree } from "./tree.js";

const POLL_INTERVAL_MS = 3000;
const TITLE = document.title;
const SEARCH_DELAY_MS = 250;
const ORDER_STORAGE_KEY = "greenmail-test-viewer.list-order";
// Small enough to draw at once, even when a list has thousands of mails.
const LIST_PAGE_SIZE = 200;

const state = {
  // The revision restarts with the viewer, so both are compared.
  instance: null,
  revision: null,
  directories: [],
  problems: { count: 0, messages: [] },
  problemsOpen: false,
  runInfoOpen: false,
  runs: null,
  run: "",
  tree: null,
  selection: ALL,
  pendingNode: null,
  // The test from a link that the extension logs, looked up at each refresh until it is found.
  pendingTest: null,
  awaitingTree: false,
  revealNode: false,
  collapsed: new Set(),
  failedOnly: false,
  query: "",
  messages: [],
  total: 0,
  // How many mails the list asks for: one page, and one more for each "Show more". Never more, so
  // that the list stays fast however many mails arrive.
  listLimit: LIST_PAGE_SIZE,
  newestCapturedAt: null,
  newestFirst: loadNewestFirst(),
  newIds: new Set(),
  listContext: null,
  messageId: null,
  // The selected mail if the last list has it, even beyond the mails that it shows.
  listedSelection: null,
  revealMessage: false,
  checkSelection: false,
  detail: { status: "none" },
  preferredTab: "html",
  tab: "html",
  previewMode: "desktop",
  moreDetails: false,
};

const elements = {
  runSelect: byId("run-select"),
  liveUpdate: byId("live-update"),
  liveUpdateText: byId("live-update-text"),
  errorBanner: byId("error-banner"),
  problemsButton: byId("problems-button"),
  problemsPanel: byId("problems-panel"),
  sidePanels: byId("side-panels"),
  runInfoButton: byId("run-info-button"),
  runInfoPanel: byId("run-info-panel"),
  layout: byId("layout"),
  failedOnly: byId("failed-only"),
  failedCount: byId("failed-count"),
  tree: byId("tree"),
  listTitle: byId("list-title"),
  listCount: byId("list-count"),
  listNew: byId("list-new"),
  listOrder: byId("list-order"),
  search: byId("search"),
  list: byId("list"),
  listStatus: byId("list-status"),
  detail: byId("detail"),
};

const drawn = { runs: "", tree: "", messages: "", problems: "", runInfo: "", searched: "" };
// Responses to requests that newer ones replaced are dropped.
const requests = { refresh: 0, messages: 0, detail: 0 };
let listError = null;
// The list that could not be loaded, which the next check of the records loads again.
let failedList = null;
// The page that "Show more" asked for, until a list with it is shown.
let morePending = null;

function byId(id) {
  const element = document.getElementById(id);
  if (element == null) {
    throw new Error(`Missing element #${id}`);
  }
  return element;
}

// Loads the runs, the tree and the list, and checks the selected mail. Returns false if a newer
// refresh started, which renders instead.
async function refresh() {
  const request = ++requests.refresh;
  requests.messages++;
  try {
    return await refreshFor(request);
  } catch (error) {
    if (request !== requests.refresh) {
      return false;
    }
    state.revision = null;
    throw error;
  }
}

async function refreshFor(request) {
  const [runs, shown] = await Promise.all([api.runs(), loadTree(state.run)]);
  if (request !== requests.refresh) {
    return false;
  }
  hideError();
  if (shown.run !== state.run) {
    state.run = shown.run;
    showError("The selected run is no longer available. The latest result of each test is shown.");
    saveUrl(false);
  }
  state.runs = runs;
  state.tree = shown.tree;
  resolveSelection();
  drawRuns();
  drawRunInfo();
  drawTree();
  await loadListAndDetail({ list: true, detail: () => true });
  if (request !== requests.refresh) {
    return false;
  }
  keepSelectionInList();
  return true;
}

async function loadTree(run) {
  try {
    return { run, tree: await api.tree(run) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && run !== "") {
      return { run: "", tree: await api.tree("") };
    }
    throw error;
  }
}

function resolveSelection() {
  state.awaitingTree = false;
  if (state.pendingTest != null) {
    const test = findTest(state.tree, state.pendingTest);
    if (test == null) {
      return;
    }
    state.selection = test;
    state.pendingTest = null;
    state.pendingNode = null;
    state.revealNode = true;
    saveUrl(false);
    return;
  }
  // A selection that a refresh no longer finds is kept, such as while a module is cleaned and
  // tested again. One from the URL or from before another run was chosen falls back to all
  // messages.
  const key = state.pendingNode ?? state.selection.key;
  const found = findSelection(state.tree, key);
  if (found != null) {
    state.selection = found;
  } else if (state.pendingNode != null) {
    state.selection = ALL;
    state.pendingNode = null;
    saveUrl(false);
  }
  state.pendingNode = null;
}

async function loadMessages() {
  const request = ++requests.messages;
  const selection = state.selection;
  const context = currentListContext();
  const sameList = context === state.listContext;
  const selected = state.messageId;
  let limit = sameList ? state.listLimit : LIST_PAGE_SIZE;
  for (const asked of [failedList, morePending]) {
    if (asked != null && asked.context === context) {
      limit = Math.max(limit, asked.limit);
    }
  }
  let answer;
  try {
    answer = await api.messages({
      source: selection.source,
      class: selection.class,
      test: selection.test,
      group: selection.group,
      run: state.run,
      q: state.query,
      order: state.newestFirst ? "newest" : "oldest",
      limit: String(limit),
      selected,
    });
  } catch (error) {
    if (request !== requests.messages) {
      return false;
    }
    failedList = { context, limit };
    listError = error;
    throw error;
  }
  if (request !== requests.messages) {
    return false;
  }
  failedList = null;
  listError = null;
  hideError("list");
  if (!sameList) {
    state.newIds.clear();
    state.listContext = context;
    state.checkSelection = true;
  } else {
    const known = new Set(state.messages.map((message) => message.id));
    const newest = capturedTime({ capturedAt: state.newestCapturedAt });
    for (const message of answer.messages) {
      if (!known.has(message.id) && capturedTime(message) > newest) {
        state.newIds.add(message.id);
      }
    }
  }
  state.messages = answer.messages;
  state.total = answer.total;
  state.listLimit = limit;
  state.listedSelection = answer.selectedInList === true ? selected : null;
  state.newestCapturedAt = answer.newestCapturedAt ?? null;
  drawList();
  keepSelectionInList();
  const more = morePending;
  morePending = null;
  if (more != null && more.context === context) {
    more.shown();
  }
  return true;
}

async function loadListAndDetail(asked) {
  let listFailure = null;
  if (asked.list) {
    try {
      await loadMessages();
    } catch (error) {
      listFailure = error;
    }
  }
  if (asked.detail()) {
    await checkDetail();
  }
  if (listFailure != null && listFailure === listError) {
    throw listFailure;
  }
}

function currentListContext() {
  return JSON.stringify([state.run, state.selection.key, state.query, state.newestFirst]);
}

function capturedTime(message) {
  const time = Date.parse(message.capturedAt ?? "");
  return Number.isNaN(time) ? -Infinity : time;
}

async function showMore() {
  if (currentListContext() !== state.listContext) {
    return;
  }
  const last = state.messages[state.messages.length - 1]?.id;
  const shown = state.messages.length;
  morePending = {
    context: state.listContext,
    limit: state.listLimit + LIST_PAGE_SIZE,
    shown: () => {
      const focused = document.activeElement;
      if (focused == null || focused === document.body || elements.list.contains(focused)) {
        const index = state.messages.findIndex((message) => message.id === last);
        focusCard(elements.list, index >= 0 ? index + 1 : shown);
      }
      elements.listStatus.textContent = `Showing ${state.messages.length} of ${state.total}`;
    },
  };
  await loadMessages();
}

async function selectMessage(id, push) {
  state.messageId = id;
  state.newIds.delete(id);
  saveUrl(push);
  drawList(true);
  const request = ++requests.detail;
  state.detail = { status: "loading" };
  drawDetail();
  const detail = await loadDetail(id);
  if (request === requests.detail) {
    showDetail(detail);
  }
}

function clearMessage() {
  state.messageId = null;
  requests.detail++;
  state.detail = { status: "none" };
  saveUrl(false);
  drawList(true);
  drawDetail();
}

// Stops showing the selected mail if the list does not have it. Checked once the list changes or
// Back and Forward choose the mail, after the requested list and tree arrive.
function keepSelectionInList() {
  if (
    !state.checkSelection ||
    state.detail.status === "loading" ||
    state.awaitingTree ||
    state.listContext !== currentListContext()
  ) {
    return;
  }
  state.checkSelection = false;
  const listed =
    state.listedSelection === state.messageId ||
    state.messages.some((message) => message.id === state.messageId);
  if (state.messageId != null && !listed && state.detail.status !== "gone") {
    clearMessage();
  }
}

// A mail that cannot be loaded for now (unreachable or 503) is marked to be loaded again later
// (retry).
async function loadDetail(id) {
  try {
    return { status: "loaded", message: await api.message(id) };
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return { status: "gone" };
    }
    const retry = error instanceof UnreachableError || (error instanceof ApiError && error.status === 503);
    return { status: "error", error: toErrorMessage(error), retry };
  }
}

function showDetail(detail) {
  state.detail = detail;
  if (detail.status === "loaded") {
    state.tab = availableTab(detail.message, state.preferredTab);
  }
  drawDetail();
  keepSelectionInList();
}

async function checkDetail() {
  const id = state.messageId;
  if (id == null) {
    return;
  }
  const request = ++requests.detail;
  const detail = await loadDetail(id);
  if (request !== requests.detail) {
    return;
  }
  if (detail.status === "loaded" && state.detail.status === "loaded") {
    if (mailContent(detail.message) !== mailContent(state.detail.message)) {
      showDetail(detail);
    } else if (JSON.stringify(detail.message) !== JSON.stringify(state.detail.message)) {
      state.detail = detail;
      renderDetailMeta(elements.detail, detail.message, detailView(), detailHandlers);
    }
    return;
  }
  if (JSON.stringify(detail) === JSON.stringify(state.detail)) {
    return;
  }
  showDetail(detail);
}

// The content of the mail, to detect whether it changed; not its metadata, such as the result of
// its test.
function mailContent(message) {
  return JSON.stringify([
    message.headers,
    message.textBody,
    message.htmlBody,
    message.htmlSource,
    message.attachments,
    message.source,
    message.partTree,
  ]);
}

function applyUrl() {
  const params = new URLSearchParams(location.hash.slice(1));
  state.run = params.get("run") ?? "";
  state.pendingNode = params.get("node") ?? ALL.key;
  state.pendingTest = params.get("test");
  state.awaitingTree = true;
  state.failedOnly = params.get("failed") === "1";
  const messageId = params.get("message");
  if (messageId !== state.messageId) {
    state.messageId = messageId;
    state.revealMessage = true;
    state.checkSelection = true;
    requests.detail++;
    state.detail = { status: messageId == null ? "none" : "loading" };
    drawDetail();
  }
}

function saveUrl(push) {
  const params = new URLSearchParams();
  if (state.run !== "") {
    params.set("run", state.run);
  }
  const node = state.pendingNode ?? state.selection.key;
  if (state.pendingTest != null) {
    params.set("test", state.pendingTest);
  } else if (node !== ALL.key) {
    params.set("node", node);
  }
  if (state.messageId != null) {
    params.set("message", state.messageId);
  }
  if (state.failedOnly) {
    params.set("failed", "1");
  }
  const hash = params.toString();
  if (hash === location.hash.slice(1)) {
    return;
  }
  const url = hash === "" ? location.pathname + location.search : `#${hash}`;
  if (push) {
    history.pushState(null, "", url);
  } else {
    history.replaceState(null, "", url);
  }
}

function drawRuns() {
  const json = JSON.stringify([state.runs, state.run]);
  if (json === drawn.runs) {
    return;
  }
  drawn.runs = json;
  const modules = state.runs?.modules ?? [];
  const options = [h("option", { value: "", text: "Latest per test" })];
  for (const module of modules) {
    const runs = module.runs.map((run) =>
      h("option", {
        value: run.id,
        text: [
          `${formatDateTime(run.startedAt)}${run.finished ? "" : " (not finished)"}`,
          run.mailCount === 1 ? "1 message" : `${run.mailCount} messages`,
          run.failureCount > 0 ? `${run.failureCount} failed` : null,
        ]
          .filter((part) => part != null)
          .join(" \u00b7 "),
      }),
    );
    if (modules.length > 1) {
      options.push(h("optgroup", { label: module.name }, runs));
    } else {
      options.push(...runs);
    }
  }
  replace(elements.runSelect, options);
  elements.runSelect.value = state.run;
}

const treeHandlers = {
  onSelect: (selection) => {
    state.selection = selection;
    state.pendingNode = null;
    state.pendingTest = null;
    saveUrl(true);
    drawTree();
    elements.list.scrollTop = 0;
    loadMessages().catch(showFailure);
  },
  onToggle: (key) => {
    if (state.collapsed.has(key)) {
      state.collapsed.delete(key);
    } else {
      state.collapsed.add(key);
    }
    drawTree();
  },
};

function drawTree() {
  const failures = failureCount(state.tree);
  elements.failedCount.textContent = String(failures);
  // Failures show in the browser tab too.
  document.title = failures > 0 ? `${failures} failed \u00b7 ${TITLE}` : TITLE;
  elements.failedOnly.setAttribute("aria-pressed", String(state.failedOnly));
  // Stays enabled while pressed, so that the filter can be turned off when the failures are gone.
  elements.failedOnly.disabled = failures === 0 && !state.failedOnly;
  const json = JSON.stringify([
    state.tree?.modules,
    state.selection.key,
    [...state.collapsed],
    state.failedOnly,
  ]);
  if (json === drawn.tree) {
    return;
  }
  drawn.tree = json;
  renderTree(
    elements.tree,
    state.tree,
    {
      selectionKey: state.selection.key,
      collapsed: state.collapsed,
      failedOnly: state.failedOnly,
    },
    treeHandlers,
  );
  if (state.revealNode) {
    state.revealNode = false;
    elements.tree.querySelector('[role="treeitem"][aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
  }
  if (state.detail.status === "none") {
    drawDetail();
  }
}

const listHandlers = {
  onSelect: (id, byKeyboard) => selectMessage(id, !byKeyboard).catch(showFailure),
  onMore: () => {
    showMore().catch(showFailure);
  },
};

function drawList(force = false) {
  elements.listTitle.textContent = state.selection.title;
  elements.listCount.textContent = String(state.total);
  elements.listOrder.textContent = state.newestFirst ? "Newest first" : "Oldest first";
  const messages = state.messages;
  const newIds = messages.filter((message) => state.newIds.has(message.id)).map((message) => message.id);
  elements.listNew.textContent = `${newIds.length} new`;
  elements.listNew.hidden = newIds.length === 0;
  const searched = searchedFolders();
  const json = JSON.stringify([
    messages,
    state.total,
    state.messageId,
    state.query,
    searched,
    newIds,
    new Date().toDateString(),
  ]);
  if (json === drawn.messages && !force) {
    return;
  }
  drawn.messages = json;
  renderList(
    elements.list,
    messages,
    {
      messageId: state.messageId,
      query: state.query,
      reveal: state.revealMessage,
      searched,
      newIds: new Set(newIds),
      total: state.total,
      more: Math.min(LIST_PAGE_SIZE, state.total - messages.length),
    },
    listHandlers,
  );
  state.revealMessage = false;
}

const detailHandlers = {
  onTab: (tab) => {
    state.preferredTab = tab;
    state.tab = tab;
    drawDetail();
  },
  onPreviewMode: (mode) => {
    state.previewMode = mode;
  },
  onMoreDetails: (open) => {
    state.moreDetails = open;
  },
  onSelectTest: (uniqueId) => {
    const selection = findSelection(state.tree, `t:${state.selection.source}:${uniqueId}`);
    if (selection == null) {
      return;
    }
    state.collapsed.delete(state.selection.key);
    state.revealNode = true;
    treeHandlers.onSelect(selection);
  },
};

function searchedFolders() {
  return state.tree != null && state.tree.modules.length === 0
    ? { directories: state.directories, problems: state.problems.count }
    : null;
}

function detailView() {
  const none = state.detail.status === "none";
  return {
    tab: state.tab,
    previewMode: state.previewMode,
    moreDetails: state.moreDetails,
    node: none ? findNode(state.tree, state.selection) : null,
    searched: none ? searchedFolders() : null,
  };
}

function drawDetail() {
  const view = detailView();
  elements.layout.classList.toggle(
    "has-detail",
    state.detail.status !== "none" || view.node != null || view.searched != null,
  );
  drawn.searched = JSON.stringify(view.searched);
  renderDetail(elements.detail, state.detail, view, detailHandlers);
}

// Storage may be blocked, and the order is then not kept.
function loadNewestFirst() {
  try {
    return localStorage.getItem(ORDER_STORAGE_KEY) !== "oldest";
  } catch {
    return true;
  }
}

function saveNewestFirst(newestFirst) {
  try {
    localStorage.setItem(ORDER_STORAGE_KEY, newestFirst ? "newest" : "oldest");
  } catch {
  }
}

function drawProblems() {
  const { count, messages } = state.problems;
  const open = state.problemsOpen && count > 0;
  const active = document.activeElement;
  const focused = elements.problemsButton === active || elements.problemsPanel.contains(active);
  elements.problemsButton.hidden = count === 0;
  elements.problemsButton.textContent = count === 1 ? "1 read problem" : `${count} read problems`;
  elements.problemsButton.setAttribute("aria-expanded", String(open));
  elements.problemsPanel.hidden = !open;
  // The last problem was removed together with its button and panel, so the focus moves to the next
  // button.
  if (focused && count === 0) {
    elements.runInfoButton.focus();
  }
  const json = JSON.stringify(state.problems);
  if (json === drawn.problems) {
    return;
  }
  drawn.problems = json;
  replacePanel(
    elements.problemsPanel,
    panelHeader("Some records could not be read", PANELS.problems),
    h("p", {
      text: "The viewer reads them again when they change. Its log has the same messages.",
    }),
    h("ul", { className: "problems-list" }, messages.map((message) => h("li", null, h("code", { text: message })))),
    count > messages.length
      ? h("p", { text: `And ${count - messages.length} more. See the viewer's log.` })
      : null,
  );
}

function drawRunInfo() {
  const open = state.runInfoOpen;
  elements.runInfoButton.setAttribute("aria-expanded", String(open));
  elements.runInfoPanel.hidden = !open;
  const json = JSON.stringify([state.runs, state.run]);
  if (!open || json === drawn.runInfo) {
    return;
  }
  drawn.runInfo = json;
  const modules = state.runs?.modules ?? [];
  const shown = [];
  for (const module of modules) {
    const run =
      state.run === "" ? module.runs[0] : module.runs.find((found) => found.id === state.run);
    if (run != null) {
      shown.push(runInfo(module, run, modules.length > 1));
    }
  }
  replacePanel(
    elements.runInfoPanel,
    panelHeader(state.run === "" ? "Latest runs" : "Run", PANELS.runInfo),
    shown.length > 0 ? shown : h("p", { text: "No runs yet." }),
  );
}

function runInfo(module, run, withModule) {
  const unknown = "(unknown)";
  const items = [
    ["Started", formatDateTime(run.startedAt)],
    ["Finished", run.finished ? formatDateTime(run.finishedAt) : "Not finished"],
    ["Tests", String(run.testCount)],
    ["Messages", String(run.mailCount)],
    ["Failures", String(run.failureCount)],
    ["Java", run.javaVersion ?? unknown],
    ["GreenMail", run.greenMailVersion ?? unknown],
    ["Process ID", run.pid != null ? String(run.pid) : unknown],
    ["Working directory", run.workingDirectory ?? unknown, "path"],
    ["Records", `${module.path}/runs/${run.name}`, "path"],
  ];
  return h(
    "section",
    { className: "run-info" },
    withModule ? h("h3", { text: module.name }) : null,
    h(
      "dl",
      null,
      items.map(([label, value, kind]) =>
        h("div", null, h("dt", { text: label }), h("dd", { className: kind, text: value })),
      ),
    ),
  );
}

function showError(message, source = "") {
  elements.errorBanner.textContent = message;
  elements.errorBanner.dataset.source = source;
  elements.errorBanner.hidden = false;
}

function hideError(source) {
  if (source == null || elements.errorBanner.dataset.source === source) {
    elements.errorBanner.hidden = true;
  }
}

function showFailure(error, source) {
  showError(
    `Could not load the data: ${toErrorMessage(error)}`,
    source ?? (error === listError ? "list" : ""),
  );
}

function setOnline(online) {
  const wasOffline = elements.liveUpdate.classList.contains("offline");
  elements.liveUpdate.classList.toggle("offline", !online);
  elements.liveUpdateText.textContent = online ? "Auto refresh" : "Offline, retrying";
  elements.liveUpdate.title = online
    ? "New records are shown automatically"
    : "The viewer cannot be reached. Retrying.";
  return wasOffline && online;
}

async function poll() {
  let checked = false;
  try {
    const status = await api.status();
    checked = true;
    const reconnected = setOnline(true);
    if (reconnected) {
      hideError();
    } else {
      // Clear the previous status error now that the status check has succeeded.
      hideError("status");
    }
    state.directories = status.directories ?? [];
    state.problems = status.problems ?? { count: 0, messages: [] };
    drawProblems();
    if (state.tree != null) {
      drawList();
      if (JSON.stringify(detailView().searched) !== drawn.searched) {
        drawDetail();
      }
    }
    if (status.instance !== state.instance || status.revision !== state.revision) {
      // Set only after a refresh that was rendered, so that a failed one is retried.
      if (await refresh()) {
        state.instance = status.instance;
        state.revision = status.revision;
      }
    } else {
      await loadListAndDetail({
        list: failedList != null,
        detail: () => state.detail.status === "error" && state.detail.retry === true,
      });
    }
  } catch (error) {
    if (error instanceof ApiError) {
      showFailure(error, checked ? undefined : "status");
    } else {
      setOnline(false);
    }
  } finally {
    setTimeout(poll, POLL_INTERVAL_MS);
  }
}

elements.runSelect.addEventListener("change", () => {
  state.run = elements.runSelect.value;
  state.pendingNode = state.selection.key;
  saveUrl(true);
  refresh().catch(showFailure);
});

const PANELS = {
  runInfo: {
    isOpen: () => state.runInfoOpen,
    setOpen: (open) => {
      state.runInfoOpen = open;
    },
    draw: drawRunInfo,
    button: elements.runInfoButton,
    element: elements.runInfoPanel,
  },
  problems: {
    isOpen: () => state.problemsOpen,
    setOpen: (open) => {
      state.problemsOpen = open;
    },
    draw: drawProblems,
    button: elements.problemsButton,
    element: elements.problemsPanel,
  },
};

// The page scrolls to an opened panel. A closed panel returns the focus to its button if the focus
// was inside it.
function setPanelOpen(panel, open, focusButton = false) {
  const active = document.activeElement;
  const focused = panel.element.contains(active) || panel.button === active;
  panel.setOpen(open);
  panel.draw();
  if (open) {
    const area = elements.sidePanels;
    area.scrollTop += panel.element.getBoundingClientRect().top - area.getBoundingClientRect().top;
  } else if (focused || focusButton) {
    panel.button.focus();
  }
}

function panelHeader(title, panel) {
  return h(
    "div",
    { className: "panel-header" },
    h("h2", { text: title }),
    h("button", {
      type: "button",
      className: "button button-secondary button-small",
      text: "Close",
      onClick: () => setPanelOpen(panel, false, true),
    }),
  );
}

// Redraws a panel, keeping the focus on its Close button if it was in the panel.
function replacePanel(element, ...children) {
  const focused = element.contains(document.activeElement);
  replace(element, ...children);
  if (focused) {
    element.querySelector(".panel-header button")?.focus({
      preventScroll: true,
    });
  }
}

for (const panel of Object.values(PANELS)) {
  panel.button.addEventListener("click", () => setPanelOpen(panel, !panel.isOpen()));
}

elements.failedOnly.addEventListener("click", () => {
  state.failedOnly = !state.failedOnly;
  saveUrl(false);
  drawTree();
});

elements.listOrder.addEventListener("click", () => {
  state.newestFirst = !state.newestFirst;
  saveNewestFirst(state.newestFirst);
  elements.listOrder.textContent = state.newestFirst ? "Newest first" : "Oldest first";
  elements.list.scrollTop = 0;
  loadMessages().catch(showFailure);
});

elements.listNew.addEventListener("click", () => scrollToNew(elements.list));

let searchTimer = 0;
elements.search.addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = window.setTimeout(() => {
    state.query = elements.search.value.trim();
    loadMessages().catch(showFailure);
  }, SEARCH_DELAY_MS);
});

document.addEventListener("keydown", (event) => {
  const target = event.target;
  if (
    event.key !== "/" ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey ||
    target.isContentEditable ||
    ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)
  ) {
    return;
  }
  event.preventDefault();
  elements.search.focus();
  elements.search.select();
});

document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape" || event.target === elements.search) {
    return;
  }
  for (const panel of Object.values(PANELS)) {
    if (panel.isOpen()) {
      setPanelOpen(panel, false);
    }
  }
});

window.addEventListener("popstate", () => {
  applyUrl();
  refresh().catch(showFailure);
});

setUpPaneResizers(elements.layout);
applyUrl();
drawDetail();
poll();
