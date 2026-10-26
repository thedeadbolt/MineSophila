"use strict";

// The clip player (M12-2): a clip's frames play like a GIF, the mask overlay and box follow
// each frame, and a timeline shows the honey's state, its bearing and the teacher's mode
// across the clip, so a label that disagrees with its frame stands out. Uses api(),
// element(), facts(), number(), humane() and showBanner() from app.js.

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
const MASK_TINT = [24, 200, 240];
const clip = {
  list: [],
  id: null,
  record: null,
  frames: [],
  rgb: [],
  masks: [],
  index: 0,
  playing: false,
  clock: 0,
  carry: 0,
  load: 0,
};

async function startClips(dataset) {
  document.body.classList.add("clips");
  $("filter-panel").hidden = true;
  $("view").hidden = true;
  $("details").hidden = true;
  $("find-id").placeholder = "Go to s000123-c02";
  const form = $("clip-filters");
  form.hidden = false;
  fill(form.split, dataset.options.splits);
  fill(form.type, ["approach", "pass_by", "scan", "occlusion", "detour", "no_honey"]);
  $("timeline-legend").replaceChildren(
    ...Object.entries(STATUS_COLOURS).map(([name, colour]) => legend(humane(name), colour)),
    ...Object.entries(MODE_COLOURS).map(([name, colour]) => legend(name, colour)),
  );
  await listClips(true);
}

function legend(name, colour) {
  const item = element("span", name, "key");
  item.style.setProperty("--key", colour);
  return item;
}

async function listClips(openFirst) {
  const params = new URLSearchParams();
  for (const [name, value] of new FormData($("clip-filters"))) if (String(value).trim() !== "") params.set(name, value);
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
  clip.frames = body.frames;
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
    ["Reached honey", record.summary.reached === null ? "—" : record.summary.reached ? "yes" : "no"],
    ["Drifting ticks", record.summary.drift_ticks],
    ["Seen at the end", `${number(record.summary.coverage_final * 100, 1)}% of reachable cells`],
    ["Teacher ticks", Object.entries(record.summary.ticks_by_mode).map(([mode, ticks]) => `${mode} ${ticks}`).join(", ")],
    ["Codec", record.codec.name + (record.codec.crf === undefined ? "" : ` crf ${record.codec.crf}`)],
    ["Attempt", record.attempt],
  ]);
  drawTimeline();
  await decode(load);
}

/** Fetches every frame and mask at the chosen size before playing. */
async function decode(load) {
  const scale = $("clip-scale").value;
  const count = clip.record.frame_count;
  $("clip-loading").hidden = false;
  clip.rgb = [];
  clip.masks = [];
  try {
    const fetchAll = (kind) => Promise.all(Array.from({ length: count }, (_, n) => api(`/api/clips/${clip.id}/${kind}/${n}.png?scale=${scale}`).then((blob) => createImageBitmap(blob))));
    const [rgb, masks] = await Promise.all([fetchAll("frames"), fetchAll("masks")]);
    if (load !== clip.load) return;
    clip.rgb = rgb;
    clip.masks = masks.map(tint);
  } finally {
    if (load === clip.load) $("clip-loading").hidden = true;
  }
  show(clip.index);
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
}

function drawFrame() {
  const canvas = $("clip-canvas");
  const image = clip.rgb[clip.index];
  if (!image) return;
  if (canvas.width !== image.width || canvas.height !== image.height) {
    canvas.width = image.width;
    canvas.height = image.height;
  }
  const context = canvas.getContext("2d");
  context.imageSmoothingEnabled = false;
  context.drawImage(image, 0, 0);
  if ($("clip-overlay").checked && clip.masks[clip.index]) context.drawImage(clip.masks[clip.index], 0, 0);
  const frame = clip.frames[clip.index];
  const box = frame && frame.target.visible_bbox_xywh;
  if ($("clip-box").checked && box) {
    const scale = image.width / clip.record.files.rgb.width;
    context.strokeStyle = "#e0a100";
    context.lineWidth = Math.max(1, 2 * scale * 4);
    context.strokeRect(box[0] * scale, box[1] * scale, Math.max(1, box[2] * scale), Math.max(1, box[3] * scale));
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
  const lane = Math.round(14 * devicePixelRatio);
  const bearing = Math.round(60 * devicePixelRatio);
  canvas.width = width;
  canvas.height = lane * 2 + bearing + Math.round(12 * devicePixelRatio);
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  const frames = clip.frames;
  if (frames.length === 0) return;
  const step = width / frames.length;
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
  });
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
    ["Teacher believes", belief ? (belief.position ? `${humane(belief.source)} at ${belief.position.join(", ")}` : humane(belief.source)) : "—"],
    ["Teacher command", command(labels.teacher)],
    ["Executed", command(labels.executed)],
    ["Drifting", labels.drifting ? "yes" : "no"],
    ["Seen so far", `${number(labels.coverage * 100, 1)}%`],
    ["Speed", `${number(labels.senses.forward_speed, 3)} blocks/tick`],
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
}

function advance(now) {
  if (!clip.playing) return;
  clip.carry += ((now - clip.clock) / 1000) * FPS * Number($("clip-speed").value);
  clip.clock = now;
  if (clip.carry >= 1) {
    const steps = Math.floor(clip.carry);
    clip.carry -= steps;
    show(clip.index + steps);
  }
  requestAnimationFrame(advance);
}

$("clip-play").addEventListener("click", () => (clip.playing ? pause() : play()));
$("clip-back").addEventListener("click", () => { pause(); show(clip.index - 1); });
$("clip-forward").addEventListener("click", () => { pause(); show(clip.index + 1); });
$("clip-scrub").addEventListener("input", (event) => { pause(); show(Number(event.target.value)); });
$("clip-overlay").addEventListener("input", drawFrame);
$("clip-box").addEventListener("input", drawFrame);
$("clip-scale").addEventListener("input", () => clip.id && decode(clip.load).catch(report));
$("clip-filters").addEventListener("submit", (event) => {
  event.preventDefault();
  listClips(true).catch(report);
});
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
