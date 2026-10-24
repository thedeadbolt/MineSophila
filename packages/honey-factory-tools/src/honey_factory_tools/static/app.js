"use strict";

// The dataset inspector page. Every request carries the session token; images are drawn
// at native resolution on a canvas and scaled with nearest-neighbour sampling.

const token = document.querySelector('meta[name="inspector-token"]').content;
const $ = (id) => document.getElementById(id);
const state = { dataset: null, selection: null, sample: null, images: {}, results: [] };
const OVERLAY = [24, 200, 240];
// The image's place on the stage: its scale and offset in CSS pixels. While "fit" is set
// it is refitted whenever the stage or image size changes.
const view = { scale: 1, x: 0, y: 0, fit: true };
const MIN_SCALE = 0.05;
const MAX_SCALE = 64;

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { "X-Inspector-Token": token, ...(options.headers || {}) } });
  const body = response.headers.get("Content-Type") === "image/png" ? await response.blob() : await response.json();
  if (!response.ok) {
    const error = new Error(body.error || (body.errors || []).join("\n") || response.statusText);
    error.body = body;
    throw error;
  }
  return body;
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = String(text);
  if (className) node.className = className;
  return node;
}

const humane = (value) => String(value).replaceAll("_", " ");

function number(value, digits = 3) {
  if (value === null || value === undefined) return "—";
  return Number.isInteger(value) ? value.toLocaleString("en") : Number(value).toFixed(digits).replace(/\.?0+$/, "");
}

function vector(values, digits = 2) {
  return values ? values.map((v) => number(v, digits)).join(", ") : "—";
}

function facts(list, entries) {
  list.replaceChildren();
  for (const [name, value] of entries) {
    if (value === undefined) continue;
    const cell = element("dd");
    if (value instanceof Node) cell.append(value);
    else cell.textContent = value === null || value === "" ? "—" : String(value);
    list.append(element("dt", name), cell);
  }
}

const SHORT = { fully_visible: "full", partially_visible: "partial", fully_occluded: "occluded", out_of_view: "out of view", edge_or_seam: "edge or seam", low_pixel_count: "few pixels", not_visible: "not visible" };

function chip(text, className) {
  const node = element("span", SHORT[text] || humane(text), `chip ${className || ""}`);
  node.title = humane(text);
  return node;
}

function fill(select, values) {
  for (const value of values) select.append(new Option(humane(value).replace(/^./, (c) => c.toUpperCase()), value));
}

function showBanner(lines, kind) {
  const banner = $("banner");
  banner.replaceChildren(...lines.map((line) => element("div", line)));
  banner.className = kind;
  banner.hidden = lines.length === 0;
}

async function loadDataset() {
  const dataset = await api("/api/dataset");
  state.dataset = dataset;
  $("dataset-id").textContent = dataset.dataset_id;
  document.title = `${dataset.dataset_id} – inspector`;
  const capture = dataset.capture;
  const mode = capture.mode === "PANORAMA_310" ? "310° panorama" : "90° perspective";
  $("dataset-facts").textContent = `Level ${dataset.difficulty}, ${mode} at ${capture.width}×${capture.height}. ${dataset.scene_count} scenes, ${dataset.sample_count} samples, ${dataset.lifecycle}.`;
  const form = $("filters");
  if (form.split.options.length === 1) {
    fill(form.split, dataset.options.splits);
    fill(form.status, dataset.options.statuses);
    fill(form.mode, dataset.options.modes);
    fill($("reasons"), dataset.options.reasons);
    for (const outcome of dataset.options.outcomes) {
      const label = element("label");
      const input = Object.assign(document.createElement("input"), { type: "radio", name: "outcome", value: outcome, checked: outcome === "pass" });
      label.append(input, element("span", outcome.replace(/^./, (c) => c.toUpperCase())));
      $("outcomes").append(label);
    }
  }
  const lines = [];
  if (dataset.lifecycle !== "complete") lines.push(`This dataset is ${dataset.lifecycle}. It is shown read-only and is not accepted for training.`);
  for (const error of dataset.index_errors) lines.push(`Index error: ${error}`);
  const validation = dataset.validation;
  if (validation.state !== "done") lines.push("Checking every file in the background. Samples are checked as you open them.");
  else if (validation.error_count > 0) lines.push(`The background check found ${validation.error_count} errors. Affected samples are marked.`, ...validation.dataset_errors);
  if (!dataset.reviews) lines.push("Reviews are off. Start the inspector with --reviews DIR to record them.");
  showBanner(lines, validation.error_count > 0 || dataset.index_errors.length > 0 ? "error" : "info");
  document.querySelectorAll("#review button").forEach((button) => (button.disabled = !dataset.reviews));
  if (validation.state !== "done") setTimeout(() => loadDataset().then(() => applyFilters(false)), 2000);
}

function query() {
  const params = new URLSearchParams();
  for (const [name, value] of new FormData($("filters"))) if (String(value).trim() !== "") params.set(name, value);
  return params;
}

function summarizeFilters(params) {
  const active = [...params.keys()].filter((key) => !["mode", "seed", "low_pixels"].includes(key) || (key === "mode" && params.get(key) !== "random"));
  const mode = params.get("mode");
  const order = mode === "scene" ? "by scene" : mode === "stratified" ? `stratified, seed ${params.get("seed") || 0}` : `random, seed ${params.get("seed") || 0}`;
  const filters = active.filter((key) => key !== "mode" && key !== "count");
  return `${filters.length ? `${filters.length} filter${filters.length > 1 ? "s" : ""}, ` : ""}${order}${params.get("count") ? `, ${params.get("count")} taken` : ""}`;
}

async function applyFilters(openFirst) {
  const params = query();
  $("filter-summary").textContent = summarizeFilters(params);
  params.set("limit", "500");
  const page = await api(`/api/samples?${params}`);
  state.selection = page.selection;
  state.results = page.samples;
  const shown = page.total > page.samples.length ? `, first ${page.samples.length} shown` : "";
  $("result-count").textContent = `${page.total} samples${shown}. Selection ${page.selection.sha256.slice(0, 10)}.`;
  const list = $("results");
  list.replaceChildren();
  for (const sample of page.samples) {
    const item = element("li");
    item.dataset.sample = sample.sample_id;
    const button = element("button");
    button.type = "button";
    const marks = element("span", null, "marks");
    const status = chip(sample.target_status, `status-${sample.target_status}`);
    if (sample.visibility_ratio !== null && sample.target_status === "partially_visible") status.textContent += ` ${number(sample.visibility_ratio, 2)}`;
    marks.append(status);
    if (sample.stratum !== sample.target_status) marks.append(chip(sample.stratum));
    if (sample.integrity === "error") marks.append(chip("file error", "bad"));
    if (sample.review) marks.append(chip(sample.review, sample.review));
    button.append(
      element("span", sample.sample_id, "id"),
      marks,
      element("span", sample.visible_pixels ? `${number(sample.visible_pixels)} px` : "", "num"),
    );
    button.addEventListener("click", () => openSample(sample.sample_id));
    item.append(button);
    if (state.sample && state.sample.sample.sample_id === sample.sample_id) item.className = "current";
    list.append(item);
  }
  if (list.children.length === 0) list.append(element("li", "No sample matches. Clear a filter to widen the selection.", "num"));
  if (openFirst && page.samples.length > 0) openSample(page.samples[0].sample_id);
}

async function openScene(sceneId) {
  const scene = await api(`/api/scenes/${encodeURIComponent(sceneId)}`);
  if (scene.samples.length === 0) throw new Error(`Scene ${sceneId} has no samples.`);
  await openSample(scene.samples[0].sample_id);
}

async function openSample(sampleId) {
  const detail = await api(`/api/samples/${encodeURIComponent(sampleId)}`);
  state.sample = detail;
  state.images = {};
  $("view").hidden = false;
  $("details").hidden = false;
  const sample = detail.sample;
  $("view-title").textContent = `${sample.sample_id}`;
  for (const item of $("results").children) item.classList.toggle("current", item.dataset.sample === sample.sample_id);
  const current = document.querySelector("#results li.current");
  if (current) current.scrollIntoView({ block: "nearest" });

  const target = sample.target;
  const status = element("span");
  status.append(chip(target.target_status, `status-${target.target_status}`));
  if (detail.integrity === "error") status.append(" ", chip("file error", "bad"));
  facts($("target-facts"), [
    ["Status", status],
    ["Visible pixels", `${number(target.visible_pixels)} of ${number(target.unoccluded_pixels)} unoccluded`],
    ["Visibility", target.visibility_ratio === null ? "—" : `${(target.visibility_ratio * 100).toFixed(1)} %`],
    ["Mask box", target.visible_bbox_xywh ? `x ${target.visible_bbox_xywh[0]}, y ${target.visible_bbox_xywh[1]}, ${target.visible_bbox_xywh[2]} × ${target.visible_bbox_xywh[3]}` : "—"],
    ["Centroid", vector(target.visible_centroid_xy, 1)],
    ["Distance", target.distance_blocks === undefined ? "—" : `${number(target.distance_blocks, 2)} blocks`],
  ]);
  const camera = sample.camera;
  const conditions = sample.conditions;
  facts($("sample-facts"), [
    ["Split", humane(sample.split)],
    ["Viewpoint", sample.viewpoint_index],
    ["Eye", vector(camera.eye_position, 2)],
    ["Yaw, pitch", `${number(camera.yaw_degrees, 1)}°, ${number(camera.pitch_degrees, 1)}°`],
    ["Time, weather", `${conditions.time_of_day_ticks} ticks, ${humane(conditions.weather)}`],
    ["Biome", conditions.biome ? conditions.biome.replace("minecraft:", "") : "—"],
    ["Light", `sky ${conditions.sky_light ?? "—"}, block ${conditions.block_light ?? "—"}`],
    ["Luminance", number(conditions.rgb_mean_luminance, 3)],
  ]);
  const scene = detail.scene || {};
  facts($("scene-facts"), [
    ["Scene", scene.scene_id],
    ["Target", scene.target ? (scene.target.present ? vector(scene.target.position, 0) : "absent") : undefined],
    ["Template", scene.template ? `${scene.template.id} v${scene.template.version}` : undefined],
    ["Source patch", scene.source_patch ? `seed ${scene.source_patch.world_seed} at ${vector(scene.source_patch.bounds.min, 0)}` : undefined],
    ["Generation", scene.generation ? `index ${scene.generation.scene_index}, attempt ${scene.generation.attempt_index}` : undefined],
    ["Layout hash", scene.layout_sha256 ? `${scene.layout_sha256.slice(0, 16)}…` : undefined],
  ]);
  $("raw-record").textContent = JSON.stringify({ sample, scene }, null, 2);
  $("replay").textContent = detail.replay_command;
  history(detail.reviews);
  $("review-status").textContent = "";
  for (const [id, target] of [["prev-sample", detail.previous], ["next-sample", detail.next]]) {
    $(id).disabled = !target;
    $(id).onclick = () => openSample(target);
  }
  const errors = $("errors");
  errors.hidden = detail.errors.length === 0;
  errors.replaceChildren(element("strong", "These files are not valid, so their images are not shown."), ...detail.errors.map((error) => element("div", error)));
  $("canvas-frame").hidden = detail.errors.length > 0;
  await sceneGrid(sample.scene_id, sample.sample_id);
  if (detail.errors.length === 0) {
    const [rgb, mask] = await Promise.all(["rgb", "mask"].map((kind) => api(`/image/${encodeURIComponent(sample.sample_id)}/${kind}`).then(createImageBitmap)));
    if (state.sample !== detail) return;
    state.images = { rgb, mask };
  }
  draw();
}

function history(reviews) {
  $("review-history").replaceChildren(...reviews.slice().reverse().map((review) => {
    const when = review.reviewed_at_utc.replace("T", " ").slice(0, 16);
    return element("li", `${review.outcome}, ${humane(review.reason)}${review.note ? `: ${review.note}` : ""} (${review.subject.kind}, ${when}${review.reviewer ? `, ${review.reviewer}` : ""})`);
  }));
}

async function sceneGrid(sceneId, current) {
  const scene = await api(`/api/scenes/${encodeURIComponent(sceneId)}`);
  for (const [id, target] of [["prev-scene", scene.previous], ["next-scene", scene.next]]) {
    $(id).disabled = !target;
    $(id).onclick = () => openScene(target);
  }
  $("strip-title").textContent = `Viewpoints in scene ${sceneId}`;
  const grid = $("scene-grid");
  grid.replaceChildren();
  for (const sample of scene.samples) {
    const item = element("li");
    const button = element("button");
    button.type = "button";
    button.title = sample.sample_id;
    if (sample.sample_id === current) item.className = "current";
    if (sample.integrity === "error") {
      button.append(chip("file error", "bad"));
    } else {
      const image = element("img");
      image.alt = `Viewpoint ${sample.viewpoint_index}`;
      api(`/image/${encodeURIComponent(sample.sample_id)}/rgb`).then((blob) => (image.src = URL.createObjectURL(blob))).catch(() => button.append(chip("file error", "bad")));
      button.append(image);
    }
    const caption = element("span", null, "caption");
    caption.append(element("span", `View ${sample.viewpoint_index}`), element("span", sample.visible_pixels ? `${number(sample.visible_pixels)} px` : humane(sample.target_status)));
    button.append(caption);
    if (sample.review) button.append(chip(sample.review, sample.review));
    button.addEventListener("click", () => openSample(sample.sample_id));
    item.append(button);
    grid.append(item);
  }
}

function draw() {
  const canvas = $("canvas");
  const context = canvas.getContext("2d");
  const { rgb, mask } = state.images;
  if (!rgb || !mask) {
    canvas.width = 0;
    canvas.height = 0;
    return;
  }
  canvas.width = rgb.width;
  canvas.height = rgb.height;
  context.imageSmoothingEnabled = false;
  const layer = document.querySelector('input[name="layer"]:checked').value;
  context.drawImage(layer === "mask" ? mask : rgb, 0, 0);
  if (layer === "overlay") {
    const scratch = new OffscreenCanvas(mask.width, mask.height).getContext("2d");
    scratch.drawImage(mask, 0, 0);
    const pixels = scratch.getImageData(0, 0, mask.width, mask.height);
    const alpha = Math.round(($("opacity").value / 100) * 255);
    for (let i = 0; i < pixels.data.length; i += 4) {
      const positive = pixels.data[i] === 255;
      [pixels.data[i], pixels.data[i + 1], pixels.data[i + 2]] = OVERLAY;
      pixels.data[i + 3] = positive ? alpha : 0;
    }
    scratch.putImageData(pixels, 0, 0);
    context.drawImage(scratch.canvas, 0, 0);
  }
  const target = state.sample.sample.target;
  if ($("show-box").checked && target.visible_bbox_xywh) {
    const [x, y, w, h] = target.visible_bbox_xywh;
    context.strokeStyle = "#ffffff";
    context.lineWidth = 1;
    context.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
    const [cx, cy] = target.visible_centroid_xy;
    context.fillStyle = "#ffffff";
    context.fillRect(Math.floor(cx) - 3, Math.floor(cy), 7, 1);
    context.fillRect(Math.floor(cx), Math.floor(cy) - 3, 1, 7);
  }
  canvas.style.width = `${rgb.width}px`;
  canvas.style.height = `${rgb.height}px`;
  if (view.fit) fitView();
  else placeView();
}

function stageSize() {
  const frame = $("canvas-frame");
  return [frame.clientWidth, frame.clientHeight];
}

/** Scales the whole image to the stage, up or down, and centres it. */
function fitView() {
  const canvas = $("canvas");
  const [width, height] = stageSize();
  if (!canvas.width || !width || !height) return;
  view.scale = Math.min(width / canvas.width, height / canvas.height) * 0.98;
  view.x = (width - canvas.width * view.scale) / 2;
  view.y = (height - canvas.height * view.scale) / 2;
  view.fit = true;
  placeView();
}

function placeView() {
  $("canvas").style.transform = `translate(${view.x}px, ${view.y}px) scale(${view.scale})`;
  $("zoom-level").textContent = view.fit ? `Fit ${Math.round(view.scale * 100)}%` : `${Math.round(view.scale * 100)}%`;
}

/** Zooms by {@code factor} keeping the stage point (cx, cy) over the same image pixel. */
function zoomAt(factor, cx, cy) {
  const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, view.scale * factor));
  view.x = cx - ((cx - view.x) * scale) / view.scale;
  view.y = cy - ((cy - view.y) * scale) / view.scale;
  view.scale = scale;
  view.fit = false;
  placeView();
}

function zoomCentre(factor) {
  const [width, height] = stageSize();
  zoomAt(factor, width / 2, height / 2);
}

function actualSize() {
  zoomCentre(1 / view.scale);
}

async function saveReview(kind) {
  const form = $("review");
  const sample = state.sample.sample;
  const fromSelection = state.results.some((item) => item.sample_id === sample.sample_id);
  const body = {
    kind,
    id: kind === "sample" ? sample.sample_id : sample.scene_id,
    outcome: form.querySelector('input[name="outcome"]:checked').value,
    reason: form.reason.value,
    note: form.note.value,
    ...(fromSelection && state.selection ? { selection: state.selection } : {}),
  };
  try {
    const review = await api("/api/reviews", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    $("review-status").textContent = `Saved: ${review.outcome} for ${review.subject.kind} ${review.subject.id}.`;
    form.note.value = "";
    const detail = await api(`/api/samples/${encodeURIComponent(sample.sample_id)}`);
    history(detail.reviews);
    await sceneGrid(sample.scene_id, sample.sample_id);
    await applyFilters(false);
  } catch (error) {
    $("review-status").textContent = `Not saved. ${error.message}`;
  }
}

function report(error) {
  showBanner([String(error.message || error)], "error");
}

$("filters").addEventListener("submit", (event) => {
  event.preventDefault();
  applyFilters(true).catch(report);
});
$("filters").addEventListener("reset", () => setTimeout(() => applyFilters(true).catch(report)));
$("find").addEventListener("submit", (event) => {
  event.preventDefault();
  const id = $("find-id").value.trim();
  (/^s\d{6}$/.test(id) ? openScene(id) : openSample(id)).catch(report);
});
$("review").addEventListener("submit", (event) => {
  event.preventDefault();
  saveReview(event.submitter.value);
});
$("copy-replay").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("replay").textContent);
  $("copy-replay").textContent = "Copied";
  setTimeout(() => ($("copy-replay").textContent = "Copy"), 1500);
});
for (const control of document.querySelectorAll('input[name="layer"], #opacity, #show-box')) control.addEventListener("input", draw);
$("zoom-in").addEventListener("click", () => zoomCentre(1.5));
$("zoom-out").addEventListener("click", () => zoomCentre(1 / 1.5));
$("zoom-fit").addEventListener("click", fitView);
$("zoom-actual").addEventListener("click", actualSize);
{
  const frame = $("canvas-frame");
  let drag = null;
  frame.addEventListener("wheel", (event) => {
    event.preventDefault();
    const box = frame.getBoundingClientRect();
    zoomAt(Math.exp(-event.deltaY * 0.0015), event.clientX - box.left, event.clientY - box.top);
  }, { passive: false });
  frame.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    drag = { x: event.clientX, y: event.clientY };
    frame.setPointerCapture(event.pointerId);
    frame.classList.add("dragging");
  });
  frame.addEventListener("pointermove", (event) => {
    if (!drag) return;
    view.x += event.clientX - drag.x;
    view.y += event.clientY - drag.y;
    drag = { x: event.clientX, y: event.clientY };
    view.fit = false;
    placeView();
  });
  const release = () => {
    drag = null;
    frame.classList.remove("dragging");
  };
  frame.addEventListener("pointerup", release);
  frame.addEventListener("pointercancel", release);
  frame.addEventListener("dblclick", (event) => {
    if (view.fit) {
      const box = frame.getBoundingClientRect();
      zoomAt(1 / view.scale, event.clientX - box.left, event.clientY - box.top);
    } else {
      fitView();
    }
  });
  new ResizeObserver(() => view.fit && fitView()).observe(frame);
}
document.addEventListener("keydown", (event) => {
  if (event.target.closest("input, select, textarea") || event.metaKey || event.ctrlKey || event.altKey) return;
  const layers = { 1: "rgb", 2: "mask", 3: "overlay" };
  if (layers[event.key]) {
    document.querySelector(`input[name="layer"][value="${layers[event.key]}"]`).checked = true;
    draw();
  } else if (event.key === "+" || event.key === "=") {
    zoomCentre(1.5);
  } else if (event.key === "-") {
    zoomCentre(1 / 1.5);
  } else if (event.key === "0") {
    fitView();
  } else if (event.key === "ArrowLeft" && !$("prev-sample").disabled) {
    $("prev-sample").click();
  } else if (event.key === "ArrowRight" && !$("next-sample").disabled) {
    $("next-sample").click();
  }
});

loadDataset().then(() => applyFilters(true)).catch(report);
