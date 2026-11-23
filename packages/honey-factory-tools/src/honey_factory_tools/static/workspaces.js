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
