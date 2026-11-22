// Creates the elements of the page. Mail content is not trusted: text only goes into text nodes and
// is never parsed as HTML.

// Creates an element. Props: className, text, dataset, on<Event> listeners and other attributes
// (true sets an empty one; null, undefined and false are left out).
export function h(tag, props, ...children) {
  const element = document.createElement(tag);
  for (const [name, value] of Object.entries(props ?? {})) {
    if (value == null || value === false) {
      continue;
    }
    if (name === "className") {
      element.className = value;
    } else if (name === "text") {
      element.textContent = String(value);
    } else if (name === "dataset") {
      Object.assign(element.dataset, value);
    } else if (/^on[A-Z]/.test(name)) {
      if (typeof value !== "function") {
        throw new TypeError(`${name} must be a function`);
      }
      element.addEventListener(name.slice(2).toLowerCase(), value);
    } else if (name.toLowerCase().startsWith("on") || name === "srcdoc") {
      throw new TypeError(`${name} cannot be set with h()`);
    } else {
      element.setAttribute(name, value === true ? "" : String(value));
    }
  }
  append(element, children);
  return element;
}

export function append(parent, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) {
      continue;
    }
    parent.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function replace(element, ...children) {
  element.replaceChildren();
  append(element, children);
}

// A frame that runs nothing and has no access to the viewer; its links may open in a new tab.
// The server sanitizes the HTML first.
export function sandboxedFrame(html, title) {
  const frame = h("iframe", {
    className: "html-preview",
    title,
    sandbox: "allow-popups allow-popups-to-escape-sandbox",
    referrerpolicy: "no-referrer",
  });
  frame.srcdoc = html;
  return frame;
}

// Keeps the scroll position of an element while its content is replaced.
export function keepingScroll(element, render) {
  const top = element.scrollTop;
  const left = element.scrollLeft;
  render();
  element.scrollTop = top;
  element.scrollLeft = left;
}
