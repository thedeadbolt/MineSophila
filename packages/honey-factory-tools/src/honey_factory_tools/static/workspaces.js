"use strict";

// Workspaces (ADR 0029): Scene, Eye and Brain share one selection. The scene scripts
// announce it with "viewer:dataset", "viewer:sample" and "viewer:frame" events; this file
// keeps the latest of each in `selection`, switches the rail, and moves the active
// workspace's panel into whichever viewer is showing, under its toolbar.
// Uses $ and element() from app.js.

const selection = { dataset: null, sample: null, frame: null };
const workspaces = {
  current: "scene",
  handlers: {},
  /** Registers a workspace's show(selection) and hide() callbacks. */
  register(name, handler) {
    this.handlers[name] = handler;
  },
};

function activeViewer() {
  return !$("clip-view").hidden || document.body.classList.contains("clips") ? $("clip-view") : $("view");
}

function placePanels() {
  const viewer = activeViewer();
  const after = viewer.querySelector(".stage");
  for (const name of ["eye", "brain"]) {
    const panel = $(`${name}-panel`);
    if (panel.parentElement !== viewer) after.after(panel);
  }
}

function setWorkspace(name) {
  const link = document.querySelector(`.workspace-link[data-workspace="${name}"]`);
  if (!link || link.getAttribute("aria-disabled") === "true") name = "scene";
  const previous = workspaces.current;
  workspaces.current = name;
  document.body.dataset.workspace = name;
  for (const item of document.querySelectorAll(".workspace-link")) {
    if (item.dataset.workspace === name) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  }
  placePanels();
  for (const panel of ["eye", "brain"]) {
    $(`${panel}-panel`).hidden = panel !== name;
    $(`${panel}-details`).hidden = panel !== name;
  }
  if (previous !== name && workspaces.handlers[previous]) workspaces.handlers[previous].hide();
  if (workspaces.handlers[name]) workspaces.handlers[name].show(selection);
  // The scene canvases refit when their stage comes back.
  window.dispatchEvent(new Event("resize"));
}

function routeFromHash() {
  // #eye, #brain, or #brain-circuit style: a workspace and, optionally, one of its views.
  const name = location.hash.replace(/^#/, "").split("-")[0];
  setWorkspace(["eye", "brain"].includes(name) ? name : "scene");
}

for (const link of document.querySelectorAll(".workspace-link")) {
  link.addEventListener("click", (event) => {
    event.preventDefault();
    if (link.getAttribute("aria-disabled") === "true") return;
    const name = link.dataset.workspace;
    location.hash = name === "scene" ? "" : name;
    setWorkspace(name);
  });
}
window.addEventListener("hashchange", routeFromHash);

document.addEventListener("viewer:dataset", (event) => {
  const first = selection.dataset === null;
  selection.dataset = event.detail.dataset;
  const available = selection.dataset.workspaces || {};
  const eye = document.querySelector('.workspace-link[data-workspace="eye"]');
  const eyeInfo = available.eye || { available: false, reason: "This inspector has no eye view." };
  eye.setAttribute("aria-disabled", String(!eyeInfo.available));
  eye.title = eyeInfo.available ? "Eye: the frame through fly-vision's compound eye" : `Eye: ${eyeInfo.reason}`;
  const brain = document.querySelector('.workspace-link[data-workspace="brain"]');
  brain.setAttribute("aria-disabled", "false");
  brain.title = "Brain: fly-brain runs and clip traces";
  if (first) routeFromHash();
});

document.addEventListener("viewer:sample", (event) => {
  selection.sample = event.detail;
  selection.frame = null;
  placePanels();
  const handler = workspaces.handlers[workspaces.current];
  if (handler && handler.sample) handler.sample(selection);
});

document.addEventListener("viewer:frame", (event) => {
  selection.frame = event.detail;
  selection.sample = null;
  placePanels();
  const handler = workspaces.handlers[workspaces.current];
  if (handler && handler.frame) handler.frame(selection);
});

/** Colour stops from black to bright, per receptor channel (as fly-vision's plots). */
const CHANNEL_STOPS = {
  R1_6: [[0, 0, 0], [255, 255, 255]],
  R7: [[0, 0, 0], [122, 47, 224], [239, 228, 255]],
  R8: [[0, 0, 0], [30, 158, 60], [227, 247, 218]],
  honey: [[0, 0, 0], [240, 180, 41], [255, 236, 170]],
};

function ramp(stops, t) {
  const x = Math.max(0, Math.min(1, t)) * (stops.length - 1);
  const i = Math.min(stops.length - 2, Math.floor(x));
  const f = x - i;
  const a = stops[i];
  const b = stops[i + 1];
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f];
}

/** A canvas sized to its box at device resolution; returns its 2D context. */
function fitCanvas(canvas) {
  const ratio = devicePixelRatio;
  const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
  const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const context = canvas.getContext("2d");
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.clearRect(0, 0, width, height);
  return context;
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

// Comfort: panes, timeline, collapsible sections, maximised cards and shortcuts. Each choice
// is remembered in this browser where storage is available.

function remember(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(`viewer:${key}`);
    localStorage.setItem(`viewer:${key}`, value);
  } catch {
    // Without storage the choice lasts for this page only.
  }
  return null;
}

function togglePane(name, force) {
  // The clip player's wide mode hides both panes; opening either pane leaves it.
  if (document.body.classList.contains("wide") && name !== "timeline" && force !== true) {
    document.body.classList.remove("wide");
    $("clip-wide").setAttribute("aria-pressed", "false");
    remember("wide", "");
    try { localStorage.setItem("clip-wide", ""); } catch { /* storage may be unavailable */ }
    document.body.classList.remove(`hide-${name}`);
  } else {
    document.body.classList.toggle(`hide-${name}`, force);
  }
  const hidden = document.body.classList.contains(`hide-${name}`);
  $(`toggle-${name}`).setAttribute("aria-pressed", String(!hidden));
  remember(`hide-${name}`, hidden ? "1" : "0");
  labelHandles();
  window.dispatchEvent(new Event("resize"));
}

/** A handle at the top of each side pane: "Hide" while it is open, and the strip's only
    content, with the pane's name written down it, while it is folded. */
function addHandles() {
  const panes = [[document.querySelector(".results"), "list"], ...[...document.querySelectorAll(".workspace > .inspector")].map((p) => [p, "details"])];
  for (const [pane, name] of panes) {
    const handle = element("button", null, "pane-handle");
    handle.type = "button";
    handle.dataset.pane = name;
    handle.append(element("span", "", "chevron"), element("span", "", "label"));
    handle.addEventListener("click", () => togglePane(name));
    pane.prepend(handle);
  }
}

function labelHandles() {
  const wide = document.body.classList.contains("wide");
  for (const handle of document.querySelectorAll(".pane-handle")) {
    const name = handle.dataset.pane;
    const hidden = wide || document.body.classList.contains(`hide-${name}`);
    const list = name === "list";
    handle.querySelector(".chevron").textContent = hidden === list ? "»" : "«";
    handle.querySelector(".label").textContent = hidden ? (list ? "List" : "Details") : list ? "Hide list" : "Hide details";
    handle.title = `${hidden ? "Show" : "Hide"} the ${list ? "list" : "details"} (${list ? "[" : "]"})`;
    handle.setAttribute("aria-expanded", String(!hidden));
  }
}

addHandles();
for (const name of ["list", "details", "timeline"]) {
  $(`toggle-${name}`).addEventListener("click", () => togglePane(name));
  const stored = remember(`hide-${name}`);
  // On a narrower window the details start folded, until someone opens them.
  if (stored === "1" || (stored === null && name === "details" && innerWidth < 1300)) togglePane(name, true);
}
labelHandles();
$("clip-wide").addEventListener("click", () => setTimeout(labelHandles));

function toggleHelp(force) {
  const sheet = $("shortcuts");
  sheet.hidden = force === undefined ? !sheet.hidden : !force;
  $("toggle-help").setAttribute("aria-expanded", String(!sheet.hidden));
}
$("toggle-help").addEventListener("click", () => toggleHelp());
document.addEventListener("click", (event) => {
  if (!$("shortcuts").hidden && !event.target.closest("#shortcuts, #toggle-help")) toggleHelp(false);
});

/** Section headings in the side panels fold their section away. */
function makeCollapsible(root) {
  for (const section of root.querySelectorAll(".inspector section")) {
    if (section.classList.contains("collapsible")) continue;
    const heading = section.querySelector(":scope > h2, :scope > .section-head h2");
    if (!heading) continue;
    section.classList.add("collapsible");
    heading.tabIndex = 0;
    heading.setAttribute("role", "button");
    const key = `collapsed:${section.closest(".inspector").id}:${heading.textContent.trim()}`;
    const set = (collapsed) => {
      section.classList.toggle("collapsed", collapsed);
      heading.setAttribute("aria-expanded", String(!collapsed));
    };
    set(Boolean(remember(key)));
    const flip = () => {
      const collapsed = !section.classList.contains("collapsed");
      set(collapsed);
      remember(key, collapsed ? "1" : "");
    };
    heading.addEventListener("click", flip);
    heading.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        flip();
      }
    });
  }
}
makeCollapsible(document);

// The viewpoint strip under an image folds away too.
for (const strip of document.querySelectorAll(".strip")) {
  const heading = strip.querySelector(":scope > h2");
  heading.tabIndex = 0;
  heading.setAttribute("role", "button");
  const set = (collapsed) => {
    strip.classList.toggle("collapsed", collapsed);
    heading.setAttribute("aria-expanded", String(!collapsed));
    window.dispatchEvent(new Event("resize"));
  };
  set(Boolean(remember("collapsed:strip")));
  const flip = () => {
    const collapsed = !strip.classList.contains("collapsed");
    set(collapsed);
    remember("collapsed:strip", collapsed ? "1" : "");
  };
  heading.addEventListener("click", flip);
  heading.addEventListener("keydown", (event) => (event.key === "Enter" || event.key === " ") && (event.preventDefault(), flip()));
}

/** Every card can fill its panel; Esc or the same button puts it back. */
function closeMaximised() {
  for (const card of document.querySelectorAll(".card.max")) {
    card.classList.remove("max");
    card.parentElement.classList.remove("has-max");
    card.querySelector(".max-card").textContent = "⤢";
  }
  window.dispatchEvent(new Event("resize"));
}
for (const card of document.querySelectorAll("figure.card")) {
  const button = element("button", "⤢", "max-card");
  button.type = "button";
  button.title = "Fill the panel with this view (Esc to return)";
  button.addEventListener("click", () => {
    const opening = !card.classList.contains("max");
    closeMaximised();
    if (opening) {
      card.classList.add("max");
      card.parentElement.classList.add("has-max");
      button.textContent = "⤡";
    }
    window.dispatchEvent(new Event("resize"));
  });
  card.querySelector("figcaption").append(button);
}

document.addEventListener("keydown", (event) => {
  if (event.target.closest("input, select, textarea") || event.metaKey || event.ctrlKey || event.altKey) return;
  if (event.key === "Escape") {
    toggleHelp(false);
    closeMaximised();
    return;
  }
  if (event.key === "?") {
    toggleHelp();
    return;
  }
  if (event.key === "[") togglePane("list");
  else if (event.key === "]") togglePane("details");
  else if (event.key === "t" && document.body.classList.contains("clips")) togglePane("timeline");
  else if (event.shiftKey && ["KeyS", "KeyE", "KeyB"].includes(event.code)) {
    const name = { KeyS: "scene", KeyE: "eye", KeyB: "brain" }[event.code];
    const link = document.querySelector(`.workspace-link[data-workspace="${name}"]`);
    if (link && link.getAttribute("aria-disabled") !== "true") link.click();
  } else return;
  event.preventDefault();
});
