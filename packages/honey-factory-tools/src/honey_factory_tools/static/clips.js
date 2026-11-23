"use strict";

// The clip player (M12-2): a clip's frames play like a GIF, the mask overlay and box follow
// each frame, and a timeline shows the honey's state, its bearing and the teacher's mode
// across the clip, so a label that disagrees with its frame stands out. Uses api(),
// element(), facts(), number(), humane() and showBanner() from app.js;
// everything else stays inside this block, so its names never replace app.js's.
{

const FPS = 60;
const STATUS_COLOURS = {
  fully_visible: "#2e9d5f",
  partially_visible: "#8bc34a",
  fully_occluded: "#c0392b",
  subpixel: "#e67e22",
  out_of_view: "#7f8c8d",
  absent: "#34495e",
};
const MODE_COLOURS = { track: "#e0a100", reacquire: "#8e44ad", search: "#2980b9" };
// Ticks the teacher did not drive alone are marked under the mode lane.
const DRIVER_COLOURS = { script: "#555c63", teacher_drift: "#d35400" };
const DRIVER_NAMES = { teacher: "teacher", teacher_drift: "teacher + drift", script: "script" };
const MASK_TINT = [24, 200, 240];
const DETAILS = [0.25, 0.5, 1];
const MIN_ZOOM = 0.05;
const MAX_ZOOM = 16;
const clip = {
  list: [],
  id: null,
  record: null,
  frames: [],
  rgb: [],
  decoding: 0,
  masks: [],
  index: 0,
  detail: 0,
  // The frame's place on the stage: CSS pixels per full-resolution frame pixel, and its
  // offset. While "fit" is set it is refitted whenever the stage changes size.
  view: { scale: 1, x: 0, y: 0, fit: true },
  playing: false,
  clock: 0,
  carry: 0,
  load: 0,
};

// Other workspaces open clips through this, without going through the search box.
window.viewerOpenClip = (id) => openClip(id).catch(report);

async function startClips(dataset) {
  document.body.classList.add("clips");
  $("filter-panel").hidden = true;
  $("view").hidden = true;
  $("details").hidden = true;
  $("find-id").placeholder = "Go to s000123-c02";
  const form = $("clip-filters");
  $("clip-filter-panel").hidden = false;
  fill(form.split, dataset.options.splits);
  fill(form.type, ["approach", "pass_by", "scan", "occlusion", "detour", "search", "no_honey"]);
  $("timeline-legend").replaceChildren(
    ...Object.entries(STATUS_COLOURS).map(([name, colour]) => legend(humane(name), colour)),
    ...Object.entries(MODE_COLOURS).map(([name, colour]) => legend(name, colour)),
    ...Object.entries(DRIVER_COLOURS).map(([name, colour]) => legend(`driven by ${DRIVER_NAMES[name]}`, colour)),
  );
  // ?clip=s000123-c02 opens that clip first, so a link can point at one.
  const wanted = new URLSearchParams(location.search).get("clip");
  await listClips(!wanted);
  if (wanted) await openClip(wanted).catch(() => listClips(true));
}

function legend(name, colour) {
  const item = element("span", name, "key");
  item.style.setProperty("--key", colour);
  return item;
}

async function listClips(openFirst) {
  const params = new URLSearchParams();
  for (const [name, value] of new FormData($("clip-filters"))) if (String(value).trim() !== "") params.set(name, value);
  const active = [...params.entries()].map(([name, value]) => (name === "scene_id" ? value : humane(value)));
  $("clip-filter-summary").textContent = active.length ? active.join(", ") : "all clips";
  const listing = await api(`/api/clips?${params}`);
  clip.list = listing.clips;
  $("result-count").textContent = `${listing.total} clips`;
  const items = listing.clips.map((entry) => {
    const button = element("button", null, "result");
    button.dataset.clip = entry.clip_id;
    const reached = entry.summary.reached === null ? "" : entry.summary.reached ? " · reached" : " · not reached";
    button.append(element("strong", entry.clip_id), chip(entry.clip_type, "type"), element("span", `${entry.tick_count} ticks · ${entry.split}${reached}`, "muted"));
    button.addEventListener("click", () => openClip(entry.clip_id).catch(report));
    const item = element("li");
    item.append(button);
    return item;
  });
  $("results").replaceChildren(...items);
  if (openFirst && listing.clips.length > 0) await openClip(listing.clips[0].clip_id);
}

async function openClip(id) {
  const load = ++clip.load;
  pause();
  const body = await api(`/api/clips/${id}`);
  if (load !== clip.load) return;
  clip.id = id;
  clip.record = body.clip;
  clip.scene = body.scene;
  clip.frames = body.frames;
  clip.rgb = [];
  clip.masks = [];
  clip.index = 0;
  $("clip-view").hidden = false;
  $("clip-details").hidden = false;
  $("clip-title").textContent = `${id} · ${humane(body.clip.clip_type)}`;
  $("prev-clip").disabled = !body.previous;
  $("next-clip").disabled = !body.next;
  $("prev-clip").onclick = () => openClip(body.previous).catch(report);
  $("next-clip").onclick = () => openClip(body.next).catch(report);
  for (const button of document.querySelectorAll("#results .result")) button.classList.toggle("current", button.dataset.clip === id);
  const scrub = $("clip-scrub");
  scrub.max = String(body.clip.frame_count - 1);
  scrub.value = "0";
  const errors = body.frames.length === body.clip.frame_count ? [] : [`${body.clip.frame_count} frames but ${body.frames.length} frame records`];
  $("clip-errors").replaceChildren(...errors.map((line) => element("div", line)));
  $("clip-errors").hidden = errors.length === 0;
  const record = body.clip;
  const requested = record.requested_type === record.clip_type ? humane(record.clip_type) : `${humane(record.clip_type)} (asked for ${humane(record.requested_type)})`;
  facts($("clip-facts"), [
    ["Type", requested],
    ["Scene", record.scene_id],
    ["Split", record.split],
    ["Ticks", `${record.tick_count}, ${record.frames_per_tick} frames each`],
    ["Frames", record.frame_count],
    ["Ended", humane(record.end_reason)],
    ["Reached honey", record.summary.reached === null ? "—" : record.summary.reached ? "yes" : `no (${humane(record.summary.miss_reason || "")})`],
    ["Drifting ticks", record.summary.drift_ticks],
    ["Edge-guarded ticks", record.summary.guarded_ticks],
    ["Seen at the end", `${number(record.summary.coverage_final * 100, 1)}% of reachable cells`],
    ["Teacher ticks", Object.entries(record.summary.ticks_by_mode).map(([mode, ticks]) => `${mode} ${ticks}`).join(", ")],
    ["Driven by", Object.entries(record.summary.ticks_by_driver).filter(([, ticks]) => ticks > 0).map(([driver, ticks]) => `${DRIVER_NAMES[driver]} ${ticks}`).join(", ")],
    ["Codec", record.codec.name + (record.codec.crf === undefined ? "" : ` crf ${record.codec.crf}`)],
    ["Attempt", record.attempt],
  ]);
  clip.detail = 0;
  clip.view.fit = true;
  fitView();
  drawTimeline();
  await decode(load, wantedDetail());
}

/**
 * Fetches every frame and mask decoded at {@code detail} (a fraction of full resolution).
 * The frames shown so far stay on screen until the new ones have all arrived.
 */
async function decode(load, detail) {
  const count = clip.record.frame_count;
  clip.decoding = detail;
  $("clip-loading").hidden = false;
  try {
    const fetchAll = (kind) => Promise.all(Array.from({ length: count }, (_, n) => api(`/api/clips/${clip.id}/${kind}/${n}.png?scale=${detail}`).then((blob) => createImageBitmap(blob))));
    const [rgb, masks] = await Promise.all([fetchAll("frames"), fetchAll("masks")]);
    if (load !== clip.load || clip.decoding !== detail) return;
    clip.rgb = rgb;
    clip.masks = masks.map(tint);
    clip.detail = detail;
  } finally {
    if (load === clip.load && clip.decoding === detail) {
      $("clip-loading").hidden = true;
      clip.decoding = 0;
    }
  }
  show(clip.index);
}

/** The decode resolution the view needs: the chosen one, or on Auto the least that keeps one decoded pixel per screen pixel. */
function wantedDetail() {
  const chosen = $("clip-detail").value;
  if (chosen !== "auto") return Number(chosen);
  const needed = clip.view.scale * devicePixelRatio;
  return DETAILS.find((detail) => detail >= needed) || 1;
}

let refine = 0;
/** On Auto, decodes sharper frames once zooming in has outgrown the ones shown. */
function refineDetail() {
  clearTimeout(refine);
  refine = setTimeout(() => {
    const wanted = wantedDetail();
    const chosen = $("clip-detail").value !== "auto";
    if (clip.record && (chosen ? wanted !== clip.detail : wanted > clip.detail) && clip.decoding !== wanted) decode(clip.load, wanted).catch(report);
  }, 250);
}

function stageSize() {
  const box = $("clip-stage").getBoundingClientRect();
  return { width: Math.max(1, box.width), height: Math.max(1, box.height) };
}

function fitView() {
  if (!clip.record) return;
  const { width, height } = stageSize();
  const frame = clip.record.files.rgb;
  const scale = Math.min(width / frame.width, height / frame.height);
  Object.assign(clip.view, { scale, x: (width - frame.width * scale) / 2, y: (height - frame.height * scale) / 2, fit: true });
  placed();
}

function zoomAt(factor, cx, cy) {
  const view = clip.view;
  const scale = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, view.scale * factor));
  view.x = cx - ((cx - view.x) * scale) / view.scale;
  view.y = cy - ((cy - view.y) * scale) / view.scale;
  view.scale = scale;
  view.fit = false;
  placed();
}

function zoomCentre(factor) {
  const { width, height } = stageSize();
  zoomAt(factor, width / 2, height / 2);
}

function actualSize() {
  const { width, height } = stageSize();
  zoomAt(1 / devicePixelRatio / clip.view.scale, width / 2, height / 2);
}

function placed() {
  $("clip-zoom-level").textContent = `${Math.round(clip.view.scale * devicePixelRatio * 100)}%`;
  $("clip-zoom-fit").classList.toggle("on", clip.view.fit);
  drawFrame();
  refineDetail();
}

/** A mask bitmap as a translucent overlay in the mask colour. */
function tint(mask) {
  const canvas = new OffscreenCanvas(mask.width, mask.height);
  const context = canvas.getContext("2d");
  context.drawImage(mask, 0, 0);
  const pixels = context.getImageData(0, 0, mask.width, mask.height);
  for (let p = 0; p < pixels.data.length; p += 4) {
    const on = pixels.data[p] > 127;
    pixels.data[p] = MASK_TINT[0];
    pixels.data[p + 1] = MASK_TINT[1];
    pixels.data[p + 2] = MASK_TINT[2];
    pixels.data[p + 3] = on ? 150 : 0;
  }
  context.putImageData(pixels, 0, 0);
  return canvas;
}

function show(index) {
  const count = clip.record ? clip.record.frame_count : 0;
  if (count === 0) return;
  clip.index = ((index % count) + count) % count;
  $("clip-scrub").value = String(clip.index);
  drawFrame();
  drawTimeline();
  describe(clip.frames[clip.index]);
  drawMap();
  document.dispatchEvent(new CustomEvent("viewer:frame", { detail: {
    clipId: clip.id, index: clip.index, frame: clip.frames[clip.index], frames: clip.frames,
    record: clip.record, scene: clip.scene, image: clip.rgb[clip.index] || null, playing: clip.playing,
    mask: clip.masks[clip.index] || null, maskTinted: true,
    box: clip.frames[clip.index] ? clip.frames[clip.index].target.visible_bbox_xywh : null,
    size: [clip.record.files.rgb.width, clip.record.files.rgb.height],
  } }));
  if (clip.compose) clip.compose();
}

/** The clip seen from above, north up: the whole path, the path so far, the fly's 310°
    view (the blind 50° behind it left open) and the honey block. */
function drawMap() {
  const canvas = $("clip-map");
  if (!canvas || canvas.offsetParent === null) return;
  const ratio = devicePixelRatio;
  const width = Math.max(1, Math.round(canvas.clientWidth * ratio));
  const height = Math.max(1, Math.round(canvas.clientHeight * ratio));
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  const styles = getComputedStyle(document.documentElement);
  const colour = (name) => styles.getPropertyValue(name).trim();
  const points = clip.frames.map((frame) => [frame.pose.position[0], frame.pose.position[2]]);
  const target = clip.scene && clip.scene.target && clip.scene.target.present ? clip.scene.target.position : null;
  const honey = target ? [target[0] + 0.5, target[2] + 0.5] : null;
  const all = honey ? [...points, honey] : points;
  if (!all.length) return;
  const xs = all.map((p) => p[0]);
  const zs = all.map((p) => p[1]);
  const pad = 3;
  const [x0, x1, z0, z1] = [Math.min(...xs) - pad, Math.max(...xs) + pad, Math.min(...zs) - pad, Math.max(...zs) + pad];
  const scale = Math.min(width / (x1 - x0), height / (z1 - z0));
  const ox = (width - (x1 - x0) * scale) / 2;
  const oy = (height - (z1 - z0) * scale) / 2;
  const at = (p) => [ox + (p[0] - x0) * scale, oy + (p[1] - z0) * scale];
  context.fillStyle = colour("--bg");
  context.fillRect(0, 0, width, height);
  // One-block grid where it stays readable.
  if (scale >= 8 * ratio) {
    context.globalAlpha = 0.6;
    context.strokeStyle = colour("--rule");
    context.lineWidth = 1;
    context.beginPath();
    for (let x = Math.ceil(x0); x <= x1; x++) { const px = Math.round(at([x, 0])[0]) + 0.5; context.moveTo(px, 0); context.lineTo(px, height); }
    for (let z = Math.ceil(z0); z <= z1; z++) { const py = Math.round(at([0, z])[1]) + 0.5; context.moveTo(0, py); context.lineTo(width, py); }
    context.stroke();
    context.globalAlpha = 1;
  }
  const path = (list, style, lineWidth) => {
    context.strokeStyle = style;
    context.lineWidth = lineWidth * ratio;
    context.beginPath();
    list.forEach((p, k) => { const [px, py] = at(p); if (k) context.lineTo(px, py); else context.moveTo(px, py); });
    context.stroke();
  };
  path(points, colour("--faint"), 1);
  path(points.slice(0, clip.index + 1), colour("--ink"), 2);
  const frame = clip.frames[clip.index];
  const [fx, fy] = at(points[clip.index]);
  // Minecraft yaw: 0 faces south (+z), 90 faces west (-x).
  const yaw = (frame.pose.yaw_degrees * Math.PI) / 180;
  const heading = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
  const reach = Math.min(width, height) * 0.22;
  const blind = ((50 / 2) * Math.PI) / 180;
  context.fillStyle = colour("--honey-wash");
  context.beginPath();
  context.moveTo(fx, fy);
  context.arc(fx, fy, reach, heading + Math.PI + blind, heading + Math.PI - blind + 2 * Math.PI);
  context.closePath();
  context.fill();
  if (honey) {
    const [hx, hy] = at([honey[0] - 0.5, honey[1] - 0.5]);
    context.fillStyle = colour("--honey");
    context.fillRect(hx, hy, Math.max(4 * ratio, scale), Math.max(4 * ratio, scale));
  }
  context.save();
  context.translate(fx, fy);
  context.rotate(heading);
  context.fillStyle = colour("--ink");
  context.beginPath();
  context.moveTo(8 * ratio, 0);
  context.lineTo(-5 * ratio, 4.5 * ratio);
  context.lineTo(-5 * ratio, -4.5 * ratio);
  context.closePath();
  context.fill();
  context.restore();
  const span = Math.max(x1 - x0, z1 - z0);
  $("clip-map-note").textContent = `North is up. About ${Math.round(span)} blocks across${honey ? "; amber is the honey block." : "; no honey in this scene."}`;
}

/** Draws the frame where the view puts it, at the canvas's device resolution. */
function drawFrame() {
  const canvas = $("clip-canvas");
  const { width, height } = stageSize();
  const ratio = devicePixelRatio;
  const pixelWidth = Math.round(width * ratio);
  const pixelHeight = Math.round(height * ratio);
  if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
    canvas.width = pixelWidth;
    canvas.height = pixelHeight;
  }
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  const image = clip.rgb[clip.index];
  if (!image || !clip.record) return;
  const full = clip.record.files.rgb;
  const { scale, x, y } = clip.view;
  const left = x * ratio;
  const top = y * ratio;
  const drawnWidth = full.width * scale * ratio;
  const drawnHeight = full.height * scale * ratio;
  // Smooth when shrinking a frame; show its pixels as squares when enlarging it.
  context.imageSmoothingEnabled = drawnWidth < image.width;
  context.imageSmoothingQuality = "high";
  context.drawImage(image, left, top, drawnWidth, drawnHeight);
  context.imageSmoothingEnabled = false;
  if ($("clip-overlay").checked && clip.masks[clip.index]) context.drawImage(clip.masks[clip.index], left, top, drawnWidth, drawnHeight);
  const frame = clip.frames[clip.index];
  const box = frame && frame.target.visible_bbox_xywh;
  if ($("clip-box").checked && box) {
    const unit = scale * ratio;
    context.strokeStyle = "#e0a100";
    context.lineWidth = 2 * ratio;
    context.strokeRect(left + box[0] * unit - ratio, top + box[1] * unit - ratio, Math.max(1, box[2] * unit) + 2 * ratio, Math.max(1, box[3] * unit) + 2 * ratio);
  }
}

/** The tick labels in force at frame n: those on its tick's last frame, or the reset's. */
function tickLabelsAt(n) {
  const frames = clip.frames;
  const frame = frames[n];
  if (!frame) return null;
  if (frame.tick_labels) return frame.tick_labels;
  const end = Math.min(frames.length - 1, frame.tick * frame.frames_per_tick);
  return frames[end] ? frames[end].tick_labels || null : null;
}

function drawTimeline() {
  const canvas = $("clip-timeline");
  const width = Math.max(300, Math.round(canvas.clientWidth * devicePixelRatio));
  const height = Math.max(60, Math.round(canvas.clientHeight * devicePixelRatio));
  const lane = Math.round(height * 0.14);
  const bearing = height - 3 * lane - Math.round(10 * devicePixelRatio);
  const turnTop = lane + bearing + 4 + lane + Math.round(4 * devicePixelRatio);
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  const frames = clip.frames;
  if (frames.length === 0) return;
  const step = width / frames.length;
  // Lane bounds in device pixels, for reading the lane under the pointer.
  clip.lanes = {
    status: [0, lane],
    bearing: [lane, lane + bearing + 4],
    mode: [lane + bearing + 4, lane + bearing + 4 + lane],
    turn: [turnTop - Math.round(4 * devicePixelRatio), turnTop + lane],
  };
  frames.forEach((frame, n) => {
    const x = n * step;
    const labels = tickLabelsAt(n);
    if (labels && labels.drifting) {
      context.fillStyle = "rgba(224, 161, 0, 0.18)";
      context.fillRect(x, 0, Math.ceil(step), canvas.height);
    }
    context.fillStyle = STATUS_COLOURS[frame.target.target_status] || "#999";
    context.fillRect(x, 0, Math.ceil(step), lane - 2);
    const mode = labels && labels.teacher_mode;
    context.fillStyle = mode ? MODE_COLOURS[mode] : "transparent";
    context.fillRect(x, lane + bearing + 4, Math.ceil(step), lane - 2);
    const driver = labels && DRIVER_COLOURS[labels.driver];
    if (driver) {
      const mark = Math.max(2, Math.round((lane - 2) / 3));
      context.fillStyle = driver;
      context.fillRect(x, lane + bearing + 4 + lane - 2 - mark, Math.ceil(step), mark);
    }
  });
  // Teacher turn: one bar per frame, up for a right turn, down for a left one.
  const turns = frames.map((_, n) => {
    const labels = tickLabelsAt(n);
    return labels && labels.teacher ? labels.teacher.turn_rate : null;
  });
  const turnPeak = Math.max(1e-6, ...turns.map((t) => Math.abs(t || 0)));
  const middle = turnTop + (lane - 2) / 2;
  context.fillStyle = "rgba(24, 200, 240, 0.85)";
  turns.forEach((t, n) => {
    if (!t) return;
    const h = (t / turnPeak) * ((lane - 2) / 2);
    context.fillRect(n * step, h > 0 ? middle - h : middle, Math.ceil(step), Math.max(1, Math.abs(h)));
  });
  context.fillStyle = "rgba(127, 127, 127, 0.4)";
  context.fillRect(0, middle, width, 1);
  const top = lane;
  context.strokeStyle = "rgba(127, 127, 127, 0.5)";
  context.beginPath();
  context.moveTo(0, top + bearing / 2);
  context.lineTo(width, top + bearing / 2);
  context.stroke();
  context.strokeStyle = "#e0a100";
  context.lineWidth = 2 * devicePixelRatio;
  context.beginPath();
  let drawing = false;
  frames.forEach((frame, n) => {
    if (!frame.bearing) {
      drawing = false;
      return;
    }
    const azimuth = Math.max(-180, Math.min(180, frame.bearing.azimuth_degrees));
    const x = (n + 0.5) * step;
    const y = top + bearing / 2 - (azimuth / 180) * (bearing / 2 - 2);
    if (drawing) context.lineTo(x, y);
    else context.moveTo(x, y);
    drawing = true;
  });
  context.stroke();
  const cursor = (clip.index + 0.5) * step;
  context.strokeStyle = getComputedStyle(document.body).color;
  context.lineWidth = 1.5 * devicePixelRatio;
  context.beginPath();
  context.moveTo(cursor, 0);
  context.lineTo(cursor, canvas.height);
  context.stroke();
}

function describe(frame) {
  if (!frame) return;
  const target = frame.target;
  const bearing = frame.bearing;
  $("clip-frame-label").textContent = `frame ${frame.frame} / ${clip.record.frame_count - 1} · tick ${frame.tick}${frame.sub_frame ? `.${frame.sub_frame}` : ""}`;
  facts($("frame-facts"), [
    ["Honey", humane(target.target_status)],
    ["Visible pixels", number(target.visible_pixels)],
    ["Unoccluded pixels", number(target.unoccluded_pixels)],
    ["At the frame edge", frame.touches_edge ? "yes" : "no"],
    ["Bearing", bearing ? `${number(bearing.azimuth_degrees, 1)}° right, ${number(bearing.elevation_degrees, 1)}° up` : "—"],
    ["Distance", bearing ? `${number(bearing.distance_blocks, 2)} blocks` : "—"],
    ["Yaw, pitch", `${number(frame.pose.yaw_degrees, 1)}°, ${number(frame.pose.pitch_degrees, 1)}°`],
    ["Partial tick", number(frame.partial_tick, 3)],
  ]);
  const labels = tickLabelsAt(frame.frame);
  const command = (value) => (value ? `forward ${number(value.forward, 2)}, turn ${number(value.turn_rate, 2)}°, look ${number(value.pitch_rate, 2)}°${value.jump ? ", jump" : ""}` : "—");
  const belief = labels && labels.belief;
  facts($("tick-facts"), labels ? [
    ["Teacher mode", labels.teacher_mode || "—"],
    ["Driven by", labels.driver ? DRIVER_NAMES[labels.driver] : "—"],
    ["Teacher believes", belief ? (belief.position ? `${humane(belief.source)} at ${belief.position.join(", ")}` : humane(belief.source)) : "—"],
    ["Teacher command", command(labels.teacher)],
    ["Executed", command(labels.executed)],
    ["Drifting", labels.drifting ? "yes" : "no"],
    ["Edge guard", labels.guarded ? "stopped the walk at a ledge" : "—"],
    ["Seen so far", `${number(labels.coverage * 100, 1)}%`],
    ["Speed", `${number(labels.senses.forward_speed + 0, 3)} blocks/tick`],
    ["On ground", labels.senses.on_ground ? "yes" : "no"],
  ] : []);
  $("frame-record").textContent = JSON.stringify(frame, null, 2);
}

function play() {
  if (clip.rgb.length === 0) return;
  clip.playing = true;
  clip.clock = performance.now();
  clip.carry = 0;
  $("clip-play").textContent = "Pause";
  requestAnimationFrame(advance);
}

function pause() {
  clip.playing = false;
  $("clip-play").textContent = "Play";
  if (clip.recording) finishRecording();
}

function advance(now) {
  if (!clip.playing) return;
  clip.carry += ((now - clip.clock) / 1000) * FPS * Number($("clip-speed").value);
  clip.clock = now;
  if (clip.carry >= 1) {
    const steps = Math.floor(clip.carry);
    clip.carry -= steps;
    if (clip.recording && clip.index + steps >= clip.record.frame_count - 1) {
      show(clip.record.frame_count - 1);
      finishRecording();
      return;
    }
    show(clip.index + steps);
  }
  requestAnimationFrame(advance);
}

/** Plays the clip once from the start while a canvas is recorded as WebM: the player by
    default, or another workspace's canvas, redrawn by ``compose`` after every frame. */
function startRecording(canvas = $("clip-canvas"), compose = null, name = `${clip.id}.webm`, button = $("clip-record")) {
  if (!canvas.captureStream || typeof MediaRecorder === "undefined" || clip.rgb.length === 0) return;
  pause();
  clip.compose = compose;
  show(0);
  const type = MediaRecorder.isTypeSupported("video/webm;codecs=vp9") ? "video/webm;codecs=vp9" : "video/webm";
  const recorder = new MediaRecorder(canvas.captureStream(FPS), { mimeType: type, videoBitsPerSecond: 12_000_000 });
  const chunks = [];
  recorder.addEventListener("dataavailable", (event) => event.data.size && chunks.push(event.data));
  recorder.addEventListener("stop", () => {
    const link = Object.assign(document.createElement("a"), { href: URL.createObjectURL(new Blob(chunks, { type: "video/webm" })), download: name });
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
  });
  clip.recording = recorder;
  clip.recordButton = button;
  clip.recordLabel = button.textContent;
  button.textContent = "Recording…";
  button.disabled = true;
  recorder.start();
  play();
}

function finishRecording() {
  const recorder = clip.recording;
  clip.recording = null;
  clip.compose = null;
  pause();
  clip.recordButton.textContent = clip.recordLabel;
  clip.recordButton.disabled = false;
  // Hold the last frame briefly so it lands in the video.
  setTimeout(() => recorder.state !== "inactive" && recorder.stop(), 200);
}

$("clip-play").addEventListener("click", () => (clip.playing ? pause() : play()));
$("clip-back").addEventListener("click", () => { pause(); show(clip.index - 1); });
$("clip-forward").addEventListener("click", () => { pause(); show(clip.index + 1); });
$("clip-scrub").addEventListener("input", (event) => { pause(); show(Number(event.target.value)); });
$("clip-overlay").addEventListener("input", drawFrame);
$("clip-record").addEventListener("click", () => startRecording());
window.viewerRecord = startRecording;
new ResizeObserver(() => clip.record && drawMap()).observe($("clip-map"));
$("clip-box").addEventListener("input", drawFrame);
$("clip-detail").addEventListener("input", refineDetail);
function toggleWide() {
  const wide = document.body.classList.toggle("wide");
  $("clip-wide").setAttribute("aria-pressed", String(wide));
  try {
    localStorage.setItem("clip-wide", wide ? "1" : "");
  } catch {
    // Storage may be unavailable; wide mode then lasts for this page only.
  }
}
$("clip-wide").addEventListener("click", toggleWide);
try {
  if (localStorage.getItem("clip-wide")) {
    document.body.classList.add("wide");
    $("clip-wide").setAttribute("aria-pressed", "true");
  }
} catch {
  // As above.
}
$("clip-zoom-in").addEventListener("click", () => zoomCentre(1.5));
$("clip-zoom-out").addEventListener("click", () => zoomCentre(1 / 1.5));
$("clip-zoom-fit").addEventListener("click", fitView);
$("clip-zoom-actual").addEventListener("click", actualSize);
{
  const stage = $("clip-stage");
  let drag = null;
  stage.addEventListener("wheel", (event) => {
    event.preventDefault();
    const box = stage.getBoundingClientRect();
    zoomAt(Math.exp(-event.deltaY * 0.0015), event.clientX - box.left, event.clientY - box.top);
  }, { passive: false });
  stage.addEventListener("pointerdown", (event) => {
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY };
    stage.setPointerCapture(event.pointerId);
    stage.classList.add("dragging");
  });
  stage.addEventListener("pointermove", (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    clip.view.x += event.clientX - drag.x;
    clip.view.y += event.clientY - drag.y;
    clip.view.fit = false;
    drag.x = event.clientX;
    drag.y = event.clientY;
    placed();
  });
  const release = () => {
    drag = null;
    stage.classList.remove("dragging");
  };
  stage.addEventListener("pointerup", release);
  stage.addEventListener("pointercancel", release);
  stage.addEventListener("dblclick", (event) => {
    if (clip.view.fit) {
      const box = stage.getBoundingClientRect();
      zoomAt(1 / devicePixelRatio / clip.view.scale, event.clientX - box.left, event.clientY - box.top);
    } else {
      fitView();
    }
  });
  new ResizeObserver(() => (clip.view.fit ? fitView() : drawFrame())).observe(stage);
}
// Filters apply as they change; typing in the scene box waits until it pauses.
{
  let typing = null;
  $("clip-filters").addEventListener("input", (event) => {
    clearTimeout(typing);
    typing = setTimeout(() => listClips(true).catch(report), event.target.tagName === "INPUT" ? 350 : 0);
  });
  $("clip-filters").addEventListener("reset", () => setTimeout(() => listClips(true).catch(report)));
}
$("clip-filters").addEventListener("submit", (event) => {
  event.preventDefault();
  listClips(true).catch(report);
});
/** What every lane says at one frame, for the timeline's hover tip. */
function describeTimeline(n, laneName) {
  const frame = clip.frames[n];
  const labels = tickLabelsAt(n);
  const swatch = (colour) => {
    const dot = element("i", null, "swatch");
    dot.style.background = colour;
    return dot;
  };
  const row = (lane, colour, name, value) => {
    const line = element("div", null, `tip-row${lane === laneName ? " here" : ""}`);
    line.append(colour ? swatch(colour) : element("i", null, "swatch none"), element("span", name, "tip-name"), element("span", value, "tip-value"));
    return line;
  };
  const target = frame.target;
  const status = target.target_status;
  const bearing = frame.bearing;
  const mode = labels && labels.teacher_mode;
  const driver = labels && labels.driver;
  const turn = labels && labels.teacher ? labels.teacher.turn_rate : null;
  const rows = [
    element("div", `Frame ${frame.frame} · tick ${frame.tick}${frame.sub_frame ? `.${frame.sub_frame}` : ""}`, "tip-head"),
    row("status", STATUS_COLOURS[status], "Honey", `${humane(status)}${target.visible_pixels ? `, ${number(target.visible_pixels)} px` : ""}`),
    row("bearing", "#e0a100", "Bearing", bearing ? `${number(bearing.azimuth_degrees, 1)}° ${bearing.azimuth_degrees >= 0 ? "right" : "left"}, ${number(bearing.distance_blocks, 1)} blocks` : "not in view"),
    row("mode", mode ? MODE_COLOURS[mode] : null, "Teacher", mode ? `${mode}${driver && driver !== "teacher" ? `, driven by ${DRIVER_NAMES[driver] || driver}` : ""}` : "no label"),
    row("turn", turn ? "rgba(24, 200, 240, 0.85)" : null, "Teacher turn", turn === null ? "—" : turn === 0 ? "straight" : `${number(Math.abs(turn), 1)}°/tick ${turn > 0 ? "right" : "left"}`),
  ];
  if (labels && labels.drifting) rows.push(row("drift", "rgba(224, 161, 0, 0.45)", "Drifting", "the amber band: drift added to the teacher's command"));
  return rows;
}

{
  const canvas = $("clip-timeline");
  const tip = $("timeline-tip");
  canvas.addEventListener("pointermove", (event) => {
    if (!clip.frames.length || !clip.lanes) return;
    const box = canvas.getBoundingClientRect();
    const fx = (event.clientX - box.left) / box.width;
    const py = (event.clientY - box.top) * devicePixelRatio;
    const n = Math.max(0, Math.min(clip.frames.length - 1, Math.floor(fx * clip.frames.length)));
    const laneName = Object.entries(clip.lanes).find(([, [a, b]]) => py >= a && py < b)?.[0] ?? null;
    tip.replaceChildren(...describeTimeline(n, laneName));
    tip.hidden = false;
    const wrap = canvas.parentElement.getBoundingClientRect();
    const left = event.clientX - wrap.left;
    tip.style.left = `${Math.min(wrap.width - tip.offsetWidth - 4, Math.max(4, left + 14))}px`;
    tip.style.bottom = `${wrap.height + 6}px`;
    canvas.style.setProperty("--hover", `${fx * 100}%`);
    canvas.parentElement.classList.add("hovering");
    canvas.parentElement.style.setProperty("--hover", `${left}px`);
  });
  canvas.addEventListener("pointerleave", () => {
    tip.hidden = true;
    canvas.parentElement.classList.remove("hovering");
  });
}

$("clip-timeline").addEventListener("click", (event) => {
  const box = event.target.getBoundingClientRect();
  if (clip.frames.length === 0) return;
  pause();
  show(Math.floor(((event.clientX - box.left) / box.width) * clip.frames.length));
});
new ResizeObserver(() => clip.record && drawTimeline()).observe($("clip-timeline"));
document.addEventListener("keydown", (event) => {
  if (!document.body.classList.contains("clips")) return;
  if (event.target.closest("input, select, textarea") || event.metaKey || event.ctrlKey || event.altKey) return;
  if (event.key === " ") {
    event.preventDefault();
    clip.playing ? pause() : play();
  } else if (event.key === "," || event.key === "ArrowLeft") {
    pause();
    show(clip.index - 1);
  } else if (event.key === "." || event.key === "ArrowRight") {
    pause();
    show(clip.index + 1);
  } else if (event.key === "+" || event.key === "=") {
    zoomCentre(1.5);
  } else if (event.key === "-") {
    zoomCentre(1 / 1.5);
  } else if (event.key === "0") {
    fitView();
  } else if (event.key === "w") {
    toggleWide();
  } else if (event.key === "o") {
    $("clip-overlay").checked = !$("clip-overlay").checked;
    drawFrame();
  }
});
$("find").addEventListener("submit", (event) => {
  if (!document.body.classList.contains("clips")) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  const id = $("find-id").value.trim();
  if (id) openClip(id).catch(report);
}, true);

window.startClips = startClips;
}
