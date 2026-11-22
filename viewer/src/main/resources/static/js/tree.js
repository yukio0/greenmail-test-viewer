// The tree of modules, classes and tests, with tree view keyboard navigation.
import { h, keepingScroll, replace } from "./dom.js";
import { className, formatDateTime, isFailure, resultMarker, testName } from "./format.js";

export const ALL = { key: "all", title: "All messages" };

const GROUPS = [
  ["outsideClasses", "(Outside test classes)"],
  ["ambiguous", "(Ambiguous)"],
];

export function renderTree(container, tree, view, handlers) {
  const focused = container.querySelector('[role="treeitem"]:focus');
  const focusedKey = focused?.dataset.key ?? null;
  const selections = new Map();
  keepingScroll(container, () => {
    const modules = tree?.modules ?? [];
    if (modules.length === 0) {
      replace(container, h("div", { className: "empty-tree" }, h("p", { text: "No records found yet." })));
      return;
    }
    const total = modules.reduce((sum, module) => sum + module.mailCount, 0);
    const items = [node(ALL, { kind: "all", label: "All messages", mailCount: total }, view, handlers, selections)];
    if (modules.length === 1) {
      items.push(...moduleChildren(modules[0], view, handlers, selections));
    } else {
      for (const module of modules) {
        const failures = module.failureCount;
        if (view.failedOnly && failures === 0) {
          continue;
        }
        items.push(
          node(
            moduleSelection(module),
            {
              kind: "module",
              label: module.name,
              mailCount: module.mailCount,
              markers: failures > 0 ? [failureMarker(failures)] : [],
              description: failures > 0 ? failureText(failures) : null,
              tooltip: module.path,
              children: moduleChildren(module, view, handlers, selections),
            },
            view,
            handlers,
            selections,
          ),
        );
      }
    }
    if (view.failedOnly && failureCount(tree) === 0) {
      items.push(h("li", { className: "tree-note", role: "none", text: "No failed tests." }));
    }
    replace(
      container,
      h(
        "ul",
        {
          className: "tree",
          role: "tree",
          "aria-label": "Tests",
          onKeydown: (event) => onKeydown(event, handlers, selections),
        },
        items,
      ),
    );
  });
  // One node is in the tab order: the focused one, or else the selected one.
  const items = [...container.querySelectorAll('[role="treeitem"]')];
  const current =
    items.find((item) => item.dataset.key === focusedKey) ??
    items.find((item) => item.dataset.key === view.selectionKey) ??
    items[0];
  if (current != null) {
    current.tabIndex = 0;
    if (focusedKey != null) {
      current.focus({ preventScroll: true });
    }
  }
}

export function findNode(tree, selection) {
  if (selection.group != null) {
    return null;
  }
  const module = (tree?.modules ?? []).find((found) => found.id === selection.source);
  for (const testClass of module?.classes ?? []) {
    if (selection.test != null) {
      const test = testClass.tests.find((found) => found.uniqueId === selection.test);
      if (test != null) {
        return { kind: "test", test };
      }
    } else if (selection.class != null && testClass.uniqueId === selection.class) {
      return { kind: "class", testClass };
    }
  }
  return null;
}

// The link to a failed test does not name the module, so the test whose run started last is chosen.
export function findTest(tree, uniqueId) {
  let found = null;
  let foundStartedAt = Number.NEGATIVE_INFINITY;
  for (const module of tree?.modules ?? []) {
    for (const testClass of module.classes) {
      for (const test of testClass.tests) {
        const startedAt = Date.parse(test.run?.startedAt ?? "");
        if (test.uniqueId === uniqueId && (found == null || startedAt > foundStartedAt)) {
          found = testSelection(module, test);
          foundStartedAt = startedAt;
        }
      }
    }
  }
  return found;
}

export function findSelection(tree, key) {
  if (key === ALL.key) {
    return ALL;
  }
  for (const module of tree?.modules ?? []) {
    const selections = [moduleSelection(module)];
    for (const testClass of module.classes) {
      selections.push(classSelection(module, testClass), outsideTestsSelection(module, testClass));
      for (const test of testClass.tests) {
        selections.push(testSelection(module, test));
      }
    }
    for (const [group, label] of GROUPS) {
      selections.push(groupSelection(module, group, label));
    }
    const found = selections.find((selection) => selection.key === key);
    if (found != null) {
      return found;
    }
  }
  return null;
}

export function failureCount(tree) {
  return tree?.failureCount ?? 0;
}

function moduleChildren(module, view, handlers, selections) {
  const items = [];
  for (const testClass of module.classes) {
    // A class still shows why its nested classes failed, even when only they show its failed setup.
    const setupFailed = testClass.setupFailure != null && testClass.setupFailure.result !== "aborted";
    if (!view.failedOnly || testClass.failureCount > 0 || setupFailed) {
      items.push(classNode(module, testClass, view, handlers, selections));
    }
  }
  if (view.failedOnly) {
    return items;
  }
  for (const [group, label] of GROUPS) {
    const count = module[group].mailCount;
    if (count > 0) {
      items.push(
        node(groupSelection(module, group, label), { kind: "group", label, mailCount: count }, view, handlers, selections),
      );
    }
  }
  return items;
}

function classNode(module, testClass, view, handlers, selections) {
  const failures = testClass.failureCount;
  const markers = [];
  const descriptions = [];
  if (failures > 0) {
    markers.push(failureMarker(failures));
    descriptions.push(failureText(failures));
  }
  if (testClass.setupFailure != null) {
    const aborted = testClass.setupFailure.result === "aborted";
    markers.push(
      marker(
        aborted ? "Setup aborted" : "Setup failed",
        aborted ? "aborted" : "class-setup-failed",
        testClass.setupFailure.error,
      ),
    );
    descriptions.push(aborted ? "class setup aborted" : "class setup failed");
  }
  if (testClass.teardownFailure != null) {
    const aborted = testClass.teardownFailure.result === "aborted";
    markers.push(
      marker(
        aborted ? "Teardown aborted" : "Teardown failed",
        aborted ? "aborted" : "class-teardown-failed",
        testClass.teardownFailure.error,
      ),
    );
    descriptions.push(aborted ? "class teardown aborted" : "class teardown failed");
  }
  if (testClass.fromEarlierRun) {
    markers.push(earlierMarker(testClass.run));
    descriptions.push(earlierText(testClass.run));
  }

  const children = [];
  if (testClass.outsideTests.mailCount > 0) {
    children.push(
      node(
        outsideTestsSelection(module, testClass),
        { kind: "group", label: "(Outside tests)", mailCount: testClass.outsideTests.mailCount },
        view,
        handlers,
        selections,
      ),
    );
  }
  for (const test of testClass.tests) {
    if (view.failedOnly && !isFailure(test.result)) {
      continue;
    }
    const result = resultMarker(test.result);
    const testDescriptions = [result.label];
    const testMarkers = [];
    if (test.fromEarlierRun) {
      if (!testClass.fromEarlierRun) {
        testMarkers.push(earlierMarker(test.run));
      }
      testDescriptions.push(earlierText(test.run));
    }
    children.push(
      node(
        testSelection(module, test),
        {
          kind: "test",
          label: testName(test),
          mailCount: test.mailCount,
          icon: h("span", {
            className: `result-icon ${result.className}`,
            "aria-hidden": "true",
            text: result.icon,
          }),
          markers: testMarkers,
          description: testDescriptions.join(", "),
          tooltip: [
            `${test.testClass ?? ""}#${test.testMethod ?? ""}`,
            result.label,
            test.error,
          ]
            .filter((line) => line != null && line !== "")
            .join("\n"),
        },
        view,
        handlers,
        selections,
      ),
    );
  }

  return node(
    classSelection(module, testClass),
    {
      kind: "class",
      label: className(testClass.testClass, testClass.classDisplayName),
      mailCount: testClass.mailCount,
      markers,
      description: descriptions.join(", "),
      tooltip: testClass.testClass,
      children,
    },
    view,
    handlers,
    selections,
  );
}

function node(selection, options, view, handlers, selections) {
  selections.set(selection.key, selection);
  const children = options.children ?? [];
  const markers = options.markers ?? [];
  const collapsed = children.length > 0 && view.collapsed.has(selection.key);
  const selected = view.selectionKey === selection.key;
  const messages = options.mailCount === 1 ? "1 message" : `${options.mailCount} messages`;
  const toggle =
    children.length > 0
      ? h("span", {
          className: "tree-toggle",
          "aria-hidden": "true",
          text: collapsed ? "\u25b6" : "\u25bc",
          onClick: () => handlers.onToggle(selection.key),
        })
      : h("span", { className: "tree-spacer" });
  return h(
    "li",
    {
      role: "treeitem",
      tabindex: "-1",
      "aria-label": [options.label, options.description, messages]
        .filter((part) => part != null && part !== "")
        .join(", "),
      "aria-selected": String(selected),
      "aria-expanded": children.length > 0 ? String(!collapsed) : null,
      dataset: { key: selection.key },
    },
    h(
      "div",
      { className: "tree-row" },
      toggle,
      h(
        "div",
        {
          className: `tree-node ${options.kind}${selected ? " selected" : ""}`,
          title: options.tooltip ?? options.label,
          onClick: () => handlers.onSelect(selection),
        },
        options.icon ?? null,
        h("span", { className: "tree-label", text: options.label }),
        markers,
        h("span", {
          className: "mail-count",
          title: messages,
          text: options.mailCount,
        }),
      ),
    ),
    children.length > 0 && !collapsed ? h("ul", { role: "group" }, children) : null,
  );
}

function onKeydown(event, handlers, selections) {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return;
  }
  const tree = event.currentTarget;
  const item = event.target.closest('[role="treeitem"]');
  if (item == null) {
    return;
  }
  const items = [...tree.querySelectorAll('[role="treeitem"]')];
  const index = items.indexOf(item);
  const key = item.dataset.key ?? "";
  const expanded = item.getAttribute("aria-expanded");
  let next = null;
  switch (event.key) {
    case "ArrowDown":
      next = items[index + 1];
      break;
    case "ArrowUp":
      next = items[index - 1];
      break;
    case "Home":
      next = items[0];
      break;
    case "End":
      next = items[items.length - 1];
      break;
    case "ArrowRight":
      if (expanded === "false") {
        handlers.onToggle(key);
      } else if (expanded === "true") {
        next = items[index + 1];
      }
      break;
    case "ArrowLeft":
      if (expanded === "true") {
        handlers.onToggle(key);
      } else {
        next = item.parentElement?.closest('[role="treeitem"]');
      }
      break;
    case "Enter":
    case " ": {
      const selection = selections.get(key);
      if (selection != null) {
        handlers.onSelect(selection);
      }
      break;
    }
    default:
      return;
  }
  event.preventDefault();
  if (next != null) {
    item.tabIndex = -1;
    next.tabIndex = 0;
    next.focus();
  }
}

function moduleSelection(module) {
  return { key: `m:${module.id}`, title: module.name, source: module.id };
}

function classSelection(module, testClass) {
  return {
    key: `c:${module.id}:${testClass.uniqueId}`,
    title: className(testClass.testClass, testClass.classDisplayName),
    source: module.id,
    class: testClass.uniqueId,
  };
}

function outsideTestsSelection(module, testClass) {
  return {
    key: `g:${module.id}:outsideTests:${testClass.uniqueId}`,
    title: `(Outside tests) \u00b7 ${className(testClass.testClass, testClass.classDisplayName)}`,
    source: module.id,
    class: testClass.uniqueId,
    group: "outsideTests",
  };
}

function testSelection(module, test) {
  return {
    key: `t:${module.id}:${test.uniqueId}`,
    title: testName(test),
    source: module.id,
    test: test.uniqueId,
  };
}

function groupSelection(module, group, label) {
  return { key: `g:${module.id}:${group}`, title: label, source: module.id, group };
}

function marker(label, kind, details) {
  return h("span", { className: `marker ${kind}`, title: details ?? label, text: label });
}

function failureMarker(failures) {
  return marker(`${failures} failed`, "failed", failureText(failures));
}

function failureText(failures) {
  return failures === 1 ? "1 failure" : `${failures} failures`;
}

function earlierMarker(run) {
  return marker("Earlier run", "earlier", earlierText(run));
}

function earlierText(run) {
  return `From an earlier run (${formatDateTime(run?.startedAt)})`;
}
