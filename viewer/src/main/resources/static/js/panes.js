// Lets the panes be resized by dragging their edges, or with the arrow keys. A double click
// restores the size of the layout. The sizes are kept in this browser.
const STORAGE_KEY = "greenmail-test-viewer.pane-sizes";
const KEY_STEP = 16;
const MIN_REST = { x: 320, y: 200 };

const PANES = {
  tree: { variable: "--tree-width", axis: "x", min: 180, max: 640 },
  list: { variable: "--list-width", axis: "x", min: 220, max: 720 },
  top: { variable: "--top-height", axis: "y", min: 120, max: 1200 },
};

export function setUpPaneResizers(layout) {
  const sizes = load();
  for (const [name, pane] of Object.entries(PANES)) {
    if (typeof sizes[name] === "number") {
      layout.style.setProperty(pane.variable, `${sizes[name]}px`);
    }
  }
  for (const handle of layout.querySelectorAll("[data-resize]")) {
    const name = handle.dataset.resize ?? "";
    const pane = PANES[name];
    const element = handle.parentElement;
    handle.setAttribute("aria-valuemin", String(pane.min));
    handle.setAttribute("aria-valuemax", String(pane.max));

    const current = () => {
      const box = element.getBoundingClientRect();
      return pane.axis === "x" ? box.width : box.top - layout.getBoundingClientRect().top;
    };
    const resize = (size) => {
      const max =
        pane.axis === "x"
          ? Math.min(pane.max, layout.clientWidth - otherWidths(layout, name) - MIN_REST.x)
          : Math.min(pane.max, layout.clientHeight - MIN_REST.y);
      const clamped = Math.round(Math.max(pane.min, Math.min(max, size)));
      layout.style.setProperty(pane.variable, `${clamped}px`);
      handle.setAttribute("aria-valuenow", String(clamped));
      sizes[name] = clamped;
    };

    handle.addEventListener("pointerdown", (event) => {
      if (event.button !== 0) {
        return;
      }
      event.preventDefault();
      handle.setPointerCapture(event.pointerId);
      const start = pane.axis === "x" ? event.clientX : event.clientY;
      const startSize = current();
      layout.classList.add("resizing", pane.axis === "x" ? "resizing-x" : "resizing-y");
      const onMove = (move) =>
        resize(startSize + (pane.axis === "x" ? move.clientX : move.clientY) - start);
      const onEnd = () => {
        handle.removeEventListener("pointermove", onMove);
        handle.removeEventListener("pointerup", onEnd);
        handle.removeEventListener("pointercancel", onEnd);
        layout.classList.remove("resizing", "resizing-x", "resizing-y");
        save(sizes);
      };
      handle.addEventListener("pointermove", onMove);
      handle.addEventListener("pointerup", onEnd);
      handle.addEventListener("pointercancel", onEnd);
    });
    handle.addEventListener("keydown", (event) => {
      const [smaller, larger] = pane.axis === "x" ? ["ArrowLeft", "ArrowRight"] : ["ArrowUp", "ArrowDown"];
      const targets = {
        [smaller]: current() - KEY_STEP,
        [larger]: current() + KEY_STEP,
        Home: pane.min,
        End: pane.max,
      };
      if (!(event.key in targets)) {
        return;
      }
      event.preventDefault();
      resize(targets[event.key]);
      save(sizes);
    });
    handle.addEventListener("dblclick", () => {
      layout.style.removeProperty(pane.variable);
      delete sizes[name];
      handle.removeAttribute("aria-valuenow");
      save(sizes);
    });
  }
}

function otherWidths(layout, name) {
  let sum = 0;
  for (const [other, pane] of Object.entries(PANES)) {
    const handle = layout.querySelector(`[data-resize="${other}"]`);
    if (other !== name && pane.axis === "x" && handle != null && handle.offsetParent != null) {
      sum += handle.parentElement.getBoundingClientRect().width;
    }
  }
  return sum;
}

function load() {
  try {
    const sizes = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "{}");
    return sizes != null && typeof sizes === "object" ? sizes : {};
  } catch {
    return {};
  }
}

function save(sizes) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sizes));
  } catch {
  }
}
