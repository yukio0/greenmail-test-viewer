// Formatting that does not depend on the rest of the UI.
const RESULT_LABELS = {
  passed: "Passed",
  failed: "Failed",
  aborted: "Aborted",
  disabled: "Disabled",
  classSetupFailed: "Class setup failed",
};

const RESULT_ICONS = {
  passed: "\u2713",
  failed: "\u2715",
  aborted: "!",
  disabled: "\u2212",
  classSetupFailed: "\u2715",
};

export function resultMarker(result) {
  if (result == null) {
    return { label: "No result", className: "no-result", icon: "\u25cb" };
  }
  const label = RESULT_LABELS[result] ?? result;
  const icon = RESULT_ICONS[result] ?? "?";
  return { label, className: result === "classSetupFailed" ? "class-setup-failed" : result, icon };
}

export function isFailure(result) {
  return result === "failed" || result === "classSetupFailed";
}

export function formatRecipients(recipients, emptyValue = "(none)") {
  return recipients != null && recipients.length > 0 ? recipients.join(", ") : emptyValue;
}

// Created once, because creating a formatter is slow.
const DATE_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "medium",
});

const TIME_FORMAT = new Intl.DateTimeFormat(undefined, { timeStyle: "medium" });
const DAY_AND_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});
const DATE_FORMAT = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });

function formatRecordedDate(value, format) {
  if (value == null || value.length === 0) {
    return "(unknown)";
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : format(date);
}

export function formatDateTime(value) {
  return formatRecordedDate(value, (date) => DATE_TIME_FORMAT.format(date));
}

// For the list: the time for today, the day for this year, and otherwise the full date.
export function formatShortDateTime(value, now = new Date()) {
  return formatRecordedDate(value, (date) => {
    if (date.toDateString() === now.toDateString()) {
      return TIME_FORMAT.format(date);
    }
    if (date.getFullYear() === now.getFullYear()) {
      return DAY_AND_TIME_FORMAT.format(date);
    }
    return DATE_FORMAT.format(date);
  });
}

export function formatBytes(value) {
  if (value == null) {
    return "unknown size";
  }
  if (value < 1024) {
    return `${value} B`;
  }
  const units = ["KB", "MB", "GB"];
  let nextValue = value / 1024;
  let unitIndex = 0;
  // By the value as shown, so that 1,048,575 bytes are 1.0 MB rather than 1024.0 KB.
  while (Number(nextValue.toFixed(1)) >= 1024 && unitIndex < units.length - 1) {
    nextValue /= 1024;
    unitIndex += 1;
  }
  return `${nextValue.toFixed(1)} ${units[unitIndex]}`;
}

export function toErrorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

// Without the package, and followed by the invocation of a class template, such as
// "Signup [1] en".
export function className(testClass, classDisplayName) {
  if (testClass == null) {
    return "(unknown class)";
  }
  const name = testClass.slice(testClass.lastIndexOf(".") + 1).split("$").join(" \u203a ");
  return classDisplayName ? `${name} ${classDisplayName}` : name;
}

export function testName(test) {
  return test.displayName ?? test.testMethod ?? test.uniqueId ?? "(unknown test)";
}

export function qualifiedTestName(test) {
  return `${className(test.testClass ?? null, test.classDisplayName)} \u203a ${testName(test)}`;
}
