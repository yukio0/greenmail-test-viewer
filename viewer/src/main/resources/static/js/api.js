// Calls the JSON API of the viewer.
export const MESSAGE_GONE = "This message is no longer available.";

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

// The viewer did not respond, such as when it has been stopped. Browsers word their own errors
// differently.
export class UnreachableError extends Error {
  constructor() {
    super("The viewer cannot be reached. It may have stopped. This page keeps retrying.");
  }
}

export const api = {
  status: () => getJson("/api/status"),
  runs: () => getJson("/api/runs"),
  tree: (run) => getJson("/api/tree" + query({ run })),
  messages: (params) => getJson("/api/messages" + query(params)),
  message: (id) => getJson(messageUrl(id)),
  attachmentText: (url) => getJson(url),
};

function messageUrl(id) {
  return `/api/messages/${encodeURIComponent(id)}`;
}

// Fetched first, so that a deleted mail gives an error rather than a saved error page.
export async function download(url, fallbackName) {
  const response = await reach(url);
  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response));
  }
  const blob = await readBody(() => response.blob());
  const fileName =
    fileNameFromContentDisposition(response.headers.get("content-disposition")) ?? fallbackName;
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = objectUrl;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  // Some browsers start the download only after the click was handled.
  setTimeout(() => URL.revokeObjectURL(objectUrl), 10_000);
}

async function getJson(path) {
  const response = await reach(path, { headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response));
  }
  return readBody(() => response.json());
}

async function reach(url, init) {
  try {
    return await fetch(url, init);
  } catch {
    throw new UnreachableError();
  }
}

// A body that does not arrive in full counts as an unreachable viewer, which the UI retries.
async function readBody(read) {
  try {
    return await read();
  } catch {
    throw new UnreachableError();
  }
}

async function errorMessage(response) {
  try {
    const body = await response.json();
    if (body != null && typeof body.error === "string") {
      return body.error;
    }
  } catch {
  }
  return `${response.status} ${response.statusText}`;
}

function query(params) {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value != null && value !== "") {
      search.set(name, value);
    }
  }
  const text = search.toString();
  return text === "" ? "" : `?${text}`;
}

function fileNameFromContentDisposition(value) {
  if (value == null) {
    return null;
  }
  const utf8 = value.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (utf8 != null) {
    try {
      return decodeURIComponent(utf8);
    } catch {
    }
  }
  return value.match(/filename="?([^";]+)"?/i)?.[1] ?? null;
}
