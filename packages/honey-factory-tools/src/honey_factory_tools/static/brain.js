"use strict";

// The Brain workspace (ADR 0029): a fly-brain model's activity played back against the
// clip, and the run reports it came from. Playback follows the clip player's frame; runs
// are listed from --runs and opened in the side panel.
// Uses api(), element(), facts(), number(), humane() from app.js and workspaces.js's helpers.
{

const STAGE_COLOURS = {
  photoreceptors: "#c9ccd1", lamina_relays: "#a7adb6", medulla_relays: "#858c96", visual_projection: "#b48cff", visual_kenyon: "#e05fb0",
  anterior_visual_inputs: "#3d8bfd", MeTu: "#3d8bfd", tubercle_bulb: "#18c8f0", TuBu: "#18c8f0", ring_neurons: "#5cc98a", ER: "#5cc98a",
  compass: "#f0b429", steering: "#e8743b", PFL: "#e8743b", other: "#6b717a",
};
const brain = {
  active: false,
  view: null,
  runs: null,
  sort: { key: "test", ascending: true },
  run: null,
  trace: null,
  traceClip: null,
  loadingClip: null,
  step: 0,
  rows: [],
};

function selectionClip(sel) {
  return sel.frame ? sel.frame.clipId : null;
}

async function show(sel) {
  brain.active = true;
  filterClipList(true);
  const dataset = sel.dataset || {};
  const traced = ((dataset.workspaces || {}).brain || {}).traced_clips || [];
  const asked = location.hash.match(/^#brain-(playback|circuit|network|brain3d|runs)$/);
  if (!brain.view) brain.view = asked ? asked[1] : dataset.kind === "clips" && traced.length ? "playback" : "runs";
  setView(brain.view);
  if (brain.view !== "runs" && brain.view !== "network") openFirstTraced(sel);
  if (brain.view === "runs") await loadRuns();
  else if (brain.view === "network") {
    await loadNetwork();
    await loadTrace(sel);
  } else if (brain.view === "brain3d") {
    const graph = await ensureGraph();
    if (!graph) $("brain3d-status").textContent = "No connectome graph. Build one with honey-factory-tools connectome-graph, then start the inspector with --connectome FILE.";
    else window.brain3d.show(graph);
    await loadTrace(sel);
  } else if (brain.view === "circuit") {
    await loadRuns();
    await drawCircuit();
    await loadTrace(sel);
  } else await loadTrace(sel);
}

function setView(view) {
  brain.view = view;
  for (const input of document.querySelectorAll('input[name="brain-view"]')) input.checked = input.value === view;
  $("brain-playback").hidden = view !== "playback";
  $("brain-circuit").hidden = view !== "circuit";
  $("brain-network").hidden = view !== "network";
  $("brain-3d").hidden = view !== "brain3d";
  $("brain-network-details").hidden = !["network", "brain3d", "circuit"].includes(view);
  if (view !== "brain3d" && window.brain3d) window.brain3d.hide();
  $("brain-runs").hidden = view !== "runs";
  $("brain-trace-details").hidden = view !== "playback";
  $("brain-run-details").hidden = view !== "runs";
  $("brain-circuit-run").hidden = view !== "circuit";
  $("brain-record").hidden = view !== "playback";
}

// Playback

/** One video frame of the model running: the fly's view, the compass, the activity map and
    the readout, side by side, with the clip and step written on it. */
function composeRecording(canvas) {
  const context = canvas.getContext("2d");
  context.fillStyle = "#131519";
  context.fillRect(0, 0, canvas.width, canvas.height);
  const place = (source, x, y, w, h) => {
    if (!source.width || !source.height) return;
    const scale = Math.min(w / source.width, h / source.height);
    context.drawImage(source, x + (w - source.width * scale) / 2, y + (h - source.height * scale) / 2, source.width * scale, source.height * scale);
  };
  place($("brain-view"), 20, 50, 1180, 610);
  place($("brain-compass"), 1220, 50, 680, 610);
  place($("brain-heatmap"), 20, 680, 1880, 250);
  place($("brain-decisions"), 20, 940, 1880, 130);
  context.fillStyle = "#e8e6e1";
  context.font = "600 26px monospace";
  context.fillText(`${brain.traceClip}  ·  step ${brain.step}  ·  frame ${brain.frame ? brain.frame.index : 0}`, 20, 34);
  if (brain.trace && brain.trace.header.synthetic) {
    context.fillStyle = "#f07f72";
    context.fillText("synthetic demo trace", 1560, 34);
  }
}

$("brain-record").addEventListener("click", () => {
  if (!brain.trace || !window.viewerRecord) return;
  const canvas = Object.assign(document.createElement("canvas"), { width: 1920, height: 1080 });
  window.viewerRecord(canvas, () => composeRecording(canvas), `${brain.traceClip}-model.webm`, $("brain-record"));
});

async function loadTrace(sel) {
  if (!brain.active || brain.view === "runs") return;
  const clipId = selectionClip(sel);
  const dataset = sel.dataset || {};
  const info = (dataset.workspaces || {}).brain || {};
  if (!clipId) {
    emptyPlayback(dataset.kind === "clips" ? "Open a clip to play back its trace." : "Playback needs a clip dataset. This one holds still images; see the runs instead.", []);
    return;
  }
  if (brain.traceClip === clipId && brain.trace) {
    follow(sel.frame);
    return;
  }
  if (brain.loadingClip === clipId) return;
  brain.loadingClip = clipId;
  try {
    const traced = info.traced_clips || [];
    if (!info.traces) {
      emptyPlayback("No trace directory. Start the inspector with --traces DIR, where fly-brain trace-clip writes one file per clip.", []);
      brain.trace = null;
      return;
    }
    if (!traced.includes(clipId)) {
      emptyPlayback(`No trace for ${clipId}. These clips have one:`, traced);
      brain.trace = null;
      brain.traceClip = clipId;
      return;
    }
    brain.trace = await api(`/api/brain/traces/${encodeURIComponent(clipId)}`);
    brain.traceClip = clipId;
    $("brain-empty").hidden = true;
    $("brain-trace").hidden = false;
    describeTrace();
    follow(sel.frame);
  } catch (error) {
    emptyPlayback(error.message, []);
  } finally {
    brain.loadingClip = null;
  }
}

function emptyPlayback(text, clips) {
  $("brain-trace").hidden = true;
  const box = $("brain-empty");
  box.hidden = false;
  const list = element("div", null, "clip-links");
  for (const id of clips.slice(0, 60)) {
    const button = element("button", id, "ghost");
    button.addEventListener("click", () => window.viewerOpenClip && window.viewerOpenClip(id));
    list.append(button);
  }
  box.replaceChildren(element("p", text), list);
  facts($("brain-trace-facts"), []);
}

/** The trace step taken from clip frame `index`, or the last one before it. */
function stepFor(index) {
  const frames = brain.trace.frames;
  let best = 0;
  for (let k = 0; k < frames.length; k++) if (frames[k] <= index) best = k;
  return best;
}

function follow(frame) {
  if (!brain.trace || !frame) return;
  brain.step = stepFor(frame.index);
  brain.frame = frame;
  if (brain.view === "circuit") {
    paintCircuitActivity();
    drawMostActive();
    return;
  }
  if (brain.view === "network") {
    paintNetworkActivity();
    drawMostActive();
    return;
  }
  if (brain.view === "brain3d") {
    window.brain3d.activity();
    drawMostActive();
    return;
  }
  drawView();
  drawHeatmap();
  drawCompass();
  drawDecisions();
  describeStep();
}

/** The fly's view at this frame, with the honey's box when it is visible. */
function drawView() {
  const canvas = $("brain-view");
  const context = fitCanvas(canvas);
  const frame = brain.frame;
  const image = frame && frame.image;
  const record = frame && frame.record;
  if (!image || !record) return;
  const width = record.files.rgb.width;
  const height = record.files.rgb.height;
  let w = canvas.width;
  let h = (w * height) / width;
  if (h > canvas.height) {
    h = canvas.height;
    w = (h * width) / height;
  }
  const x = (canvas.width - w) / 2;
  const y = (canvas.height - h) / 2;
  context.drawImage(image, x, y, w, h);
  const box = frame.frame && frame.frame.target ? frame.frame.target.visible_bbox_xywh : null;
  if (box) {
    context.strokeStyle = cssVar("--mask");
    context.lineWidth = 2 * devicePixelRatio;
    const s = w / width;
    context.strokeRect(x + box[0] * s - 3, y + box[1] * s - 3, box[2] * s + 6, box[3] * s + 6);
  }
}

/** In Brain, the clip list shows only clips the model has a trace for: the clips where
    the fly brain actually ran. Leaving Brain shows every clip again. */
function filterClipList(on) {
  const traced = new Set((((selection.dataset || {}).workspaces || {}).brain || {}).traced_clips || []);
  const items = document.querySelectorAll("#results li");
  let shown = 0;
  for (const item of items) {
    const button = item.querySelector(".result");
    if (!button) continue;
    const keep = !on || traced.has(button.dataset.clip);
    item.hidden = !keep;
    if (keep) shown += 1;
  }
  if (!items.length || !document.body.classList.contains("clips")) return;
  if (on) {
    brain.savedCount = brain.savedCount ?? $("result-count").textContent;
    $("result-count").textContent = traced.size ? `${shown} clips with a fly-brain trace` : "No clip has a fly-brain trace yet.";
  } else if (brain.savedCount) {
    $("result-count").textContent = brain.savedCount;
    brain.savedCount = null;
  }
}

/** Moves to the first traced clip when the open clip has no trace. Waits until a clip
    is open, so a ?clip= link still wins. */
function openFirstTraced(sel) {
  const traced = (((sel.dataset || {}).workspaces || {}).brain || {}).traced_clips || [];
  const current = selectionClip(sel);
  if (!traced.length || !current || traced.includes(current) || !window.viewerOpenClip) return false;
  window.viewerOpenClip(traced[0]);
  return true;
}

function describeTrace() {
  const trace = brain.trace;
  const header = trace.header;
  const synthetic = header.synthetic ? element("span", "synthetic demo", "chip bad") : null;
  facts($("brain-trace-facts"), [
    ["File", synthetic ? (() => { const s = element("span"); s.append(`${trace.file} `, synthetic); return s; })() : trace.file],
    ["Steps", `${trace.steps}, frames ${trace.frames[0]} to ${trace.frames[trace.frames.length - 1]}`],
    ["Neurons", number(trace.neurons)],
    ["Cell types", trace.groups.length],
    ["Compass neurons", trace.compass.angle.length],
    ["Checkpoint", header.checkpoint_sha256 ? `${String(header.checkpoint_sha256).slice(0, 12)}…` : "—"],
  ]);
  $("brain-stage-legend").replaceChildren(
    ...Object.entries(STAGE_COLOURS).filter(([name]) => trace.groups.some((g) => g.stage === name)).map(([name, colour]) => {
      const key = element("span", name, "key");
      key.style.setProperty("--key", colour);
      return key;
    }),
  );
}

/** Diverging colour for signed activity: blue below zero, honey above. */
function diverging(v, peak) {
  const t = Math.max(-1, Math.min(1, v / peak));
  if (t >= 0) return ramp([[18, 20, 24], [150, 100, 10], [255, 214, 120]], t);
  return ramp([[18, 20, 24], [20, 90, 160], [150, 215, 255]], -t);
}

function drawHeatmap() {
  const canvas = $("brain-heatmap");
  const context = fitCanvas(canvas);
  const trace = brain.trace;
  const ratio = devicePixelRatio;
  const gutter = 92 * ratio;
  const groups = trace.groups;
  const peak = Math.max(1e-6, ...groups.map((g) => Math.max(...g.mean.map((v) => Math.abs(v ?? 0)))));
  const rowHeight = (canvas.height - 4 * ratio) / groups.length;
  const stepWidth = (canvas.width - gutter) / trace.steps;
  context.font = `${Math.min(11, Math.max(8, rowHeight / ratio - 2)) * ratio}px ${cssVar("--mono")}`;
  context.textBaseline = "middle";
  brain.rows = [];
  groups.forEach((group, row) => {
    const y = row * rowHeight;
    context.fillStyle = STAGE_COLOURS[group.stage] || STAGE_COLOURS.other;
    context.fillRect(0, y + 1, 3 * ratio, rowHeight - 2);
    if (rowHeight >= 8 * ratio) {
      context.fillStyle = cssVar("--muted");
      context.fillText(group.cell_type.slice(0, 11), 7 * ratio, y + rowHeight / 2);
    }
    group.mean.forEach((v, k) => {
      const c = diverging(v ?? 0, peak);
      context.fillStyle = `rgb(${c[0]} ${c[1]} ${c[2]})`;
      context.fillRect(gutter + k * stepWidth, y, Math.ceil(stepWidth), Math.ceil(rowHeight));
    });
    brain.rows.push([y, y + rowHeight, group]);
  });
  const x = gutter + (brain.step + 0.5) * stepWidth;
  context.strokeStyle = cssVar("--ink");
  context.lineWidth = 1.5 * ratio;
  context.beginPath();
  context.moveTo(x, 0);
  context.lineTo(x, canvas.height);
  context.stroke();
  brain.heat = { gutter, stepWidth, peak };
  $("brain-heat-scale").textContent = `Mean activity per cell type; colour runs from −${number(peak, 3)} (blue) to +${number(peak, 3)} (amber).`;
}

function angleDifference(a, b) {
  return ((a - b + 540) % 360) - 180;
}

function drawCompass() {
  const canvas = $("brain-compass");
  const context = fitCanvas(canvas);
  const trace = brain.trace;
  const ratio = devicePixelRatio;
  const cx = canvas.width / 2;
  const cy = canvas.height / 2;
  const radius = Math.min(cx, cy) - 26 * ratio;
  context.strokeStyle = cssVar("--rule");
  context.lineWidth = ratio;
  context.beginPath();
  context.arc(cx, cy, radius, 0, 2 * Math.PI);
  context.stroke();
  // 0° is up, clockwise is to the right, as a heading.
  const at = (deg, r) => [cx + Math.sin((deg * Math.PI) / 180) * r, cy - Math.cos((deg * Math.PI) / 180) * r];
  context.fillStyle = cssVar("--faint");
  context.font = `${10 * ratio}px ${cssVar("--mono")}`;
  context.textAlign = "center";
  context.textBaseline = "middle";
  for (const [deg, label] of [[0, "0°"], [90, "90°"], [180, "180°"], [270, "−90°"]]) {
    const [x, y] = at(deg, radius + 14 * ratio);
    context.fillText(label, x, y);
  }
  const compass = trace.compass;
  const values = compass.activity.map((row) => row[brain.step] ?? 0);
  const peak = Math.max(1e-6, ...compass.activity.flat().map((v) => Math.abs(v ?? 0)));
  compass.angle.forEach((angle, i) => {
    const inner = compass.side[i] === "L" ? 0.78 : 0.9;
    const [x, y] = at(angle, radius * inner);
    const c = diverging(values[i], peak);
    context.fillStyle = `rgb(${c[0]} ${c[1]} ${c[2]})`;
    context.beginPath();
    context.arc(x, y, Math.max(2.5 * ratio, radius * 0.045), 0, 2 * Math.PI);
    context.fill();
  });
  const arrow = (deg, colour, length, width, dash) => {
    const [x, y] = at(deg, radius * length);
    context.strokeStyle = colour;
    context.lineWidth = width * ratio;
    context.setLineDash(dash ? [4 * ratio, 4 * ratio] : []);
    context.beginPath();
    context.moveTo(cx, cy);
    context.lineTo(x, y);
    context.stroke();
    context.setLineDash([]);
  };
  const series = trace.series;
  const heading = headingAt();
  if (heading !== null) arrow(heading, cssVar("--ink"), 0.62, 2, true);
  if (series.bump_phase) {
    const phase = series.bump_phase[brain.step];
    if (phase !== null && phase !== undefined) {
      const strength = series.bump_strength ? Math.min(1, Math.abs(series.bump_strength[brain.step] ?? 0) / Math.max(1e-6, ...series.bump_strength.map((v) => Math.abs(v ?? 0)))) : 1;
      arrow((phase * 180) / Math.PI, cssVar("--honey"), 0.3 + 0.4 * strength, 3, false);
    }
  }
  context.textAlign = "start";
}

/** The fly's heading at this frame, in degrees relative to the clip's first frame. */
function headingAt() {
  const frame = brain.frame;
  if (!frame || !frame.frames || !frame.frames.length) return null;
  const start = frame.frames[0].pose.yaw_degrees;
  return angleDifference(frame.frame.pose.yaw_degrees, start);
}

function drawDecisions() {
  const canvas = $("brain-decisions");
  const context = fitCanvas(canvas);
  const trace = brain.trace;
  const series = trace.series;
  const ratio = devicePixelRatio;
  const lanes = [];
  const frames = (brain.frame && brain.frame.frames) || [];
  const truthBearing = trace.frames.map((n) => (frames[n] && frames[n].bearing ? frames[n].bearing.azimuth_degrees : null));
  if (series.readout) {
    lanes.push({ name: "Presence", range: [0, 1], lines: [
      { values: series.presence || [], colour: cssVar("--muted"), label: "honey present", dash: true },
      { values: series.readout.map((r) => r[0]), colour: cssVar("--honey"), label: "model" },
    ] });
    lanes.push({ name: "Bearing", range: [-180, 180], lines: [
      { values: truthBearing, colour: cssVar("--muted"), label: "true", dash: true },
      { values: series.readout.map((r) => (r.length >= 3 ? (Math.atan2(r[2], r[1]) * 180) / Math.PI : null)), colour: cssVar("--honey"), label: "model" },
    ] });
  }
  if (series.teacher_actions) {
    lanes.push({ name: "Teacher turn", range: null, lines: [
      { values: series.teacher_actions.map((a) => a[1] ?? null), colour: cssVar("--mask"), label: "turn" },
    ] });
  }
  if (!lanes.length) {
    context.fillStyle = cssVar("--muted");
    context.font = `${12 * ratio}px ${cssVar("--sans")}`;
    context.fillText("This trace has no readout or teacher arrays.", 8 * ratio, 20 * ratio);
    return;
  }
  const gutter = 92 * ratio;
  const laneHeight = canvas.height / lanes.length;
  const stepWidth = (canvas.width - gutter) / trace.steps;
  lanes.forEach((lane, k) => {
    const top = k * laneHeight + 4 * ratio;
    const height = laneHeight - 10 * ratio;
    const all = lane.lines.flatMap((l) => l.values).filter((v) => v !== null && v !== undefined);
    const [lo, hi] = lane.range || [Math.min(...all, 0), Math.max(...all, 1e-6)];
    const y = (v) => top + (1 - (v - lo) / (hi - lo || 1)) * height;
    context.fillStyle = cssVar("--muted");
    context.font = `${11 * ratio}px ${cssVar("--sans")}`;
    context.textBaseline = "top";
    context.fillText(lane.name, 4 * ratio, top);
    context.strokeStyle = cssVar("--rule");
    context.lineWidth = ratio;
    context.strokeRect(gutter, top, canvas.width - gutter - 1, height);
    for (const line of lane.lines) {
      context.strokeStyle = line.colour;
      context.lineWidth = (line.dash ? 1.4 : 1.6) * ratio;
      context.setLineDash(line.dash ? [5 * ratio, 3 * ratio] : []);
      context.beginPath();
      let pen = false;
      line.values.forEach((v, s) => {
        if (v === null || v === undefined) {
          pen = false;
          return;
        }
        const px = gutter + (s + 0.5) * stepWidth;
        if (pen) context.lineTo(px, y(v));
        else context.moveTo(px, y(v));
        pen = true;
      });
      context.stroke();
      context.setLineDash([]);
    }
    const keys = lane.lines.map((l) => l.label).join(" · ");
    context.fillStyle = cssVar("--faint");
    context.font = `${10 * ratio}px ${cssVar("--sans")}`;
    context.fillText(keys, 4 * ratio, top + 15 * ratio);
  });
  const x = gutter + (brain.step + 0.5) * stepWidth;
  context.strokeStyle = cssVar("--ink");
  context.lineWidth = 1.5 * ratio;
  context.beginPath();
  context.moveTo(x, 0);
  context.lineTo(x, canvas.height);
  context.stroke();
  context.textBaseline = "alphabetic";
}

function describeStep() {
  const trace = brain.trace;
  const s = brain.step;
  const series = trace.series;
  const readout = series.readout ? series.readout[s] : null;
  const phase = series.bump_phase ? series.bump_phase[s] : null;
  const heading = headingAt();
  facts($("brain-step-facts"), [
    ["Step", `${s} of ${trace.steps - 1} (clip frame ${trace.frames[s]})`],
    ["Heading", heading === null ? "—" : `${number(heading, 1)}° from the start`],
    ["Bump phase", phase === null || phase === undefined ? "—" : `${number((phase * 180) / Math.PI, 1)}°`],
    ["Bump strength", series.bump_strength ? number(series.bump_strength[s], 4) : "—"],
    ["Bump − heading", phase !== null && phase !== undefined && heading !== null ? `${number(angleDifference((phase * 180) / Math.PI, heading), 1)}°` : "—"],
    ["Model presence", readout ? number(readout[0], 3) : "—"],
    ["Honey present", series.presence ? (series.presence[s] ? "yes" : "no") : "—"],
    ["Honey visible", series.visible ? (series.visible[s] ? "yes" : "no") : "—"],
  ]);
}

$("brain-heatmap").addEventListener("pointermove", (event) => {
  if (!brain.trace || !brain.heat) return;
  const box = event.target.getBoundingClientRect();
  const y = (event.clientY - box.top) * devicePixelRatio;
  const x = (event.clientX - box.left) * devicePixelRatio;
  const row = brain.rows.find(([top, bottom]) => y >= top && y < bottom);
  if (!row) return;
  const group = row[2];
  const step = Math.max(0, Math.min(brain.trace.steps - 1, Math.floor((x - brain.heat.gutter) / brain.heat.stepWidth)));
  facts($("brain-cell-facts"), [
    ["Cell type", group.cell_type],
    ["Stage", group.stage],
    ["Neurons", group.count],
    ["Mean here", `${number(group.mean[step], 4)} ± ${number(group.spread[step], 4)} at step ${step}`],
  ]);
});

// Runs

async function loadRuns() {
  if (!brain.runs) {
    brain.runs = await api("/api/brain/runs");
    const families = [...new Set(brain.runs.runs.map((r) => r.family).filter(Boolean))].sort();
    const select = $("brain-run-family");
    for (const family of families) {
      const count = brain.runs.runs.filter((r) => r.family === family).length;
      select.append(new Option(`${family} (${count})`, family));
    }
    $("brain-runs-directory").textContent = brain.runs.directory
      ? `${brain.runs.runs.length} reports in ${brain.runs.directory}`
      : "No run directory. Start the inspector with --runs DIR, the folder fly-brain writes its *.report.json files to.";
  }
  drawRuns();
}

const COLUMNS = [
  ["name", "Run", (r) => r.name],
  ["family", "Model", (r) => r.family],
  ["test", "Test bearing", (r) => metric(r, "test", "mean_bearing_error_deg")],
  ["test_occluded", "Occluded", (r) => metric(r, "test_occluded", "mean_bearing_error_deg")],
  ["test_visible", "Visible", (r) => metric(r, "test_visible", "mean_bearing_error_deg")],
  ["presence", "Presence BA", (r) => metric(r, "test", "presence_balanced_accuracy")],
];

function metric(run, split, name) {
  const value = run.splits && run.splits[split] ? run.splits[split][name] : null;
  return value === undefined ? null : value;
}

function drawRuns() {
  const filter = $("brain-run-filter").value.trim().toLowerCase();
  const family = $("brain-run-family").value;
  const runs = (brain.runs.runs || []).filter((r) => (!family || r.family === family) && (!filter || `${r.name} ${r.family} ${r.dataset_id || ""}`.toLowerCase().includes(filter)));
  const column = COLUMNS.find(([key]) => key === brain.sort.key);
  runs.sort((a, b) => {
    const x = column[2](a);
    const y = column[2](b);
    if (x === null || x === undefined) return 1;
    if (y === null || y === undefined) return -1;
    const order = typeof x === "string" ? x.localeCompare(y) : x - y;
    return brain.sort.ascending ? order : -order;
  });
  const worst = Math.max(90, ...runs.map((r) => metric(r, "test", "mean_bearing_error_deg") || 0));
  const head = element("tr");
  for (const [key, label] of COLUMNS) {
    const cell = element("th");
    const button = element("button", label + (brain.sort.key === key ? (brain.sort.ascending ? " ↑" : " ↓") : ""), "ghost");
    button.addEventListener("click", () => {
      brain.sort = { key, ascending: brain.sort.key === key ? !brain.sort.ascending : true };
      drawRuns();
    });
    cell.append(button);
    head.append(cell);
  }
  const rows = runs.map((run) => {
    const row = element("tr");
    if (brain.run && brain.run.name === run.name) row.className = "current";
    row.tabIndex = 0;
    const open = () => openRun(run.name).catch(report);
    row.addEventListener("click", open);
    row.addEventListener("keydown", (event) => event.key === "Enter" && open());
    row.append(element("td", run.name, "id"), element("td", run.family || "—"));
    for (const split of ["test", "test_occluded", "test_visible"]) {
      const value = metric(run, split, "mean_bearing_error_deg");
      const cell = element("td", null, "metric");
      if (value !== null) {
        const bar = element("span", null, "bar");
        bar.style.setProperty("--value", String(value / worst));
        bar.style.setProperty("--fill", split === "test" ? cssVar("--honey") : cssVar("--faint"));
        const inner = element("div", null, "metric-cell");
        inner.append(bar, element("span", `${number(value, 1)}°`, "num"));
        cell.append(inner);
      } else cell.textContent = "—";
      row.append(cell);
    }
    const presence = metric(run, "test", "presence_balanced_accuracy");
    row.append(element("td", presence === null ? "—" : number(presence, 3), "num"));
    return row;
  });
  const table = $("brain-run-table");
  table.replaceChildren(element("thead"), element("tbody"));
  table.tHead.append(head);
  table.tBodies[0].append(...rows);
  $("brain-run-count").textContent = `${runs.length} shown. Bearing is the mean error in degrees on the test split; lower is better.`;
}

async function openRun(name) {
  const report = await api(`/api/brain/runs/${encodeURIComponent(name)}`);
  brain.run = { name, report };
  brain.circuitRun = brain.run;
  for (const row of $("brain-run-table").tBodies[0].rows) row.classList.toggle("current", row.cells[0].textContent === name);
  const model = report.model || {};
  const training = report.training || {};
  const dataset = report.dataset || {};
  $("brain-run-title").textContent = name;
  facts($("brain-run-facts"), [
    ["Format", report.format],
    ["Revision", report.git_revision ? String(report.git_revision).slice(0, 10) : "—"],
    ["Dataset", dataset.source_dataset_id || "—"],
    ["Neurons", model.compilation ? number(model.compilation.neuron_count) : "—"],
    ["Parameters", model.compilation ? number(model.compilation.trainable_parameter_count) : "—"],
    ["Training", training.steps ? `${number(training.steps)} steps, ${number(training.clips || 0)} clips` : "—"],
    ["Wall time", report.runtime ? `${number(report.runtime.wall_seconds / 3600, 2)} h` : "—"],
  ]);
  drawLoss(training.loss_curve || []);
  drawSplits(report);
  drawComponents((report.connectome || {}).components || {});
  $("brain-raw").textContent = JSON.stringify(report, null, 2);
}

function drawLoss(curve) {
  const canvas = $("brain-loss");
  const context = fitCanvas(canvas);
  $("brain-loss-section").hidden = curve.length < 2;
  if (curve.length < 2) return;
  const ratio = devicePixelRatio;
  const lo = Math.min(...curve);
  const hi = Math.max(...curve);
  const x = (k) => 4 * ratio + (k / (curve.length - 1)) * (canvas.width - 8 * ratio);
  const y = (v) => 4 * ratio + (1 - (v - lo) / (hi - lo || 1)) * (canvas.height - 8 * ratio);
  context.fillStyle = cssVar("--honey-wash");
  context.beginPath();
  context.moveTo(x(0), canvas.height);
  curve.forEach((v, k) => context.lineTo(x(k), y(v)));
  context.lineTo(x(curve.length - 1), canvas.height);
  context.fill();
  context.strokeStyle = cssVar("--honey");
  context.lineWidth = 1.6 * ratio;
  context.beginPath();
  curve.forEach((v, k) => (k ? context.lineTo(x(k), y(v)) : context.moveTo(x(k), y(v))));
  context.stroke();
  $("brain-loss-range").textContent = `${number(curve[0], 3)} → ${number(curve[curve.length - 1], 3)}`;
}

function drawSplits(report) {
  const rows = [];
  const add = (label, value) => {
    if (!value || typeof value !== "object") return;
    rows.push([label, value.mean_bearing_error_deg, value.presence_balanced_accuracy, value.sample_count]);
  };
  for (const split of ["validation", "test", "test_visible", "test_occluded"]) add(humane(split), report[split]);
  for (const [type, value] of Object.entries(report.test_by_clip_type || {})) add(`  ${humane(type)}`, value);
  const table = $("brain-split-table");
  const head = element("tr");
  for (const label of ["Split", "Bearing", "Presence BA", "Samples"]) head.append(element("th", label));
  table.replaceChildren(element("thead"), element("tbody"));
  table.tHead.append(head);
  for (const [label, bearing, presence, count] of rows) {
    const row = element("tr");
    row.append(element("td", label), element("td", bearing === null || bearing === undefined ? "—" : `${number(bearing, 1)}°`, "num"), element("td", presence === null || presence === undefined ? "—" : number(presence, 3), "num"), element("td", count === undefined ? "—" : number(count), "num"));
    table.tBodies[0].append(row);
  }
}

function drawComponents(components) {
  const entries = Object.entries(components);
  $("brain-components-section").hidden = entries.length === 0;
  const peak = Math.max(1, ...entries.map(([, n]) => n));
  $("brain-components").replaceChildren(...entries.map(([name, count]) => {
    const row = element("div", null, "bar-row");
    const bar = element("span", null, "bar");
    bar.style.setProperty("--value", String(Math.sqrt(count / peak)));
    bar.style.setProperty("--fill", cssVar("--faint"));
    row.append(element("span", humane(name), "bar-label wide"), bar, element("span", number(count), "num"));
    return row;
  }));
}

// Circuit: a wiring diagram of the selected run, drawn the way connectome papers draw
// circuits. Each neuropil the model includes is a column, in the order signals flow;
// its cell types are nodes sized by neuron count and coloured by transmitter; edges are
// the synapses between cell types from the --connectome graph, as wide as their synapse
// count, ending in an arrow when excitatory and a bar when inhibitory. Recurrent
// connections inside a column arc beside it. The run's report says which components the
// model compiled and which it reads out.

const SVG = "http://www.w3.org/2000/svg";
const REGIONS = [
  // component, neuropil heading, column, row: the visual system along the top, the
  // anterior visual pathway into the central complex along the bottom.
  ["photoreceptors", "Retina", 0, 0],
  ["lamina_relays", "Lamina", 1, 0],
  ["medulla_relays", "Medulla", 2, 0],
  ["visual_projection", "Lobula complex", 3, 0],
  ["visual_kenyon", "Mushroom body", 4, 0],
  ["anterior_visual_inputs", "Medulla → AOTU", 0, 1],
  ["tubercle_bulb", "AOTU → bulb", 1, 1],
  ["ring_neurons", "Bulb → ellipsoid body", 2, 1],
  ["compass", "Ellipsoid body, PB", 3, 1],
  ["steering", "Bridge → LAL", 4, 1],
];
const TRANSMITTERS = {
  acetylcholine: { colour: "#e0a33a", name: "acetylcholine", sign: "excitatory" },
  gaba: { colour: "#4f8fe0", name: "GABA", sign: "inhibitory" },
  glutamate: { colour: "#9b7bff", name: "glutamate", sign: "inhibitory" },
  histamine: { colour: "#c9ccd1", name: "histamine", sign: "inhibitory" },
};
const OTHER_TRANSMITTER = { colour: "#6b717a", name: "unclear", sign: "unknown" };
// Each column is a box: nodes on its left, labels to their right, and its edges leave from
// the box's right side and arrive at the target box's left side, level with their node, so
// no edge ever crosses a label.
const CIRCUIT = { columnWidth: 196, row: 27, top: 36, left: 150, maxTypes: 8, rowGap: 70, boxLeft: 30, boxWidth: 150, header: 50 };
const circuit = { svg: null, view: null, full: null, nodes: new Map(), focus: null };

function svg(tag, attributes, text) {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes || {})) node.setAttribute(key, String(value));
  if (text !== undefined) node.textContent = text;
  return node;
}

function chooseCircuitRun() {
  if (brain.circuitRun) return brain.circuitRun.name;
  const runs = (brain.runs && brain.runs.runs) || [];
  // The run that compiles the most neurons shows the most of the circuit.
  let best = null;
  for (const run of runs) {
    if (!run.format || !run.format.includes("connectome")) continue;
    if (!best || (run.neurons || 0) > (best.neurons || 0) || ((run.neurons || 0) === (best.neurons || 0) && run.name > best.name)) best = run;
  }
  return best ? best.name : runs.length ? runs[0].name : null;
}

function transmitterOf(name) {
  return TRANSMITTERS[name] || OTHER_TRANSMITTER;
}

async function drawCircuit() {
  const target = $("brain-circuit-svg");
  const graph = await ensureGraph();
  const name = chooseCircuitRun();
  if (!graph) {
    target.replaceChildren();
    $("brain-circuit-note").textContent = "The circuit is drawn from the connectome graph. Build one with honey-factory-tools connectome-graph, then start the inspector with --connectome FILE.";
    return;
  }
  let report = {};
  if (name) {
    if (!brain.circuitRun || brain.circuitRun.name !== name) brain.circuitRun = { name, report: await api(`/api/brain/runs/${encodeURIComponent(name)}`) };
    report = brain.circuitRun.report;
  }
  $("brain-circuit-run").textContent = name ? `${name}${brain.run ? "" : " · the most complete run; pick another in Runs"}` : "";
  const components = (report.connectome || {}).components || {};
  const inModel = (component) => !name || !Object.keys(components).length || component in components;
  const model = report.model || {};
  const readouts = new Set(model.readout_components || []);
  const compilation = model.compilation || {};
  const limit = Number($("circuit-edges").value);
  const showRecurrent = $("circuit-recurrent").checked;

  // Nodes: per region, its strongest cell types, the rest folded into one node.
  const strength = new Float64Array(graph.cell_types.length);
  for (const [a, b, w] of graph.type_edges) {
    strength[a] += w;
    strength[b] += w;
  }
  const nodeOf = new Int32Array(graph.cell_types.length).fill(-1);
  const nodes = [];
  const regions = [];
  for (const [component, heading, column, row] of REGIONS) {
    const types = graph.cell_types.map((t, i) => [t, i]).filter(([t]) => t.component === component);
    if (!types.length) continue;
    types.sort((x, y) => strength[y[1]] - strength[x[1]]);
    const shown = types.slice(0, CIRCUIT.maxTypes);
    const folded = types.slice(CIRCUIT.maxTypes);
    const members = [];
    for (const [t, i] of shown) {
      nodeOf[i] = nodes.length;
      members.push(nodes.length);
      nodes.push({ label: t.name, types: [i], count: t.count, transmitter: t.transmitter, component });
    }
    if (folded.length) {
      const count = folded.reduce((sum, [t]) => sum + t.count, 0);
      const tally = {};
      for (const [t] of folded) tally[t.transmitter] = (tally[t.transmitter] || 0) + t.count;
      const transmitter = Object.entries(tally).sort((a, b) => b[1] - a[1])[0][0];
      for (const [, i] of folded) nodeOf[i] = nodes.length;
      members.push(nodes.length);
      nodes.push({ label: `${folded.length} more types`, types: folded.map(([, i]) => i), count, transmitter, component, folded: true });
    }
    regions.push({ component, heading, column, row, members, neurons: types.reduce((s, [t]) => s + t.count, 0) });
  }

  // Place regions: columns left to right, the two rows one under the other.
  const rowHeight = [0, 1].map((row) => Math.max(0, ...regions.filter((r) => r.row === row).map((r) => CIRCUIT.header + r.members.length * CIRCUIT.row)));
  const rowTop = [CIRCUIT.top, CIRCUIT.top + rowHeight[0] + CIRCUIT.rowGap];
  let height = 0;
  for (const region of regions) {
    region.x = CIRCUIT.left + region.column * CIRCUIT.columnWidth;
    region.y = rowTop[region.row];
    region.h = CIRCUIT.header + region.members.length * CIRCUIT.row;
    height = Math.max(height, region.y + region.h);
    const peak = Math.max(...region.members.map((m) => nodes[m].count));
    region.members.forEach((m, k) => {
      const node = nodes[m];
      node.x = region.x;
      node.y = region.y + CIRCUIT.header - 6 + k * CIRCUIT.row;
      node.r = 3 + 6 * Math.sqrt(node.count / Math.max(peak, 1));
      node.region = region;
    });
  }
  const columns = Math.max(...regions.map((r) => r.column)) + 1;
  const width = CIRCUIT.left + (columns - 1) * CIRCUIT.columnWidth + 252;
  height = Math.max(height + 30, 300);

  // Edges between nodes, summed over folded types, strongest first.
  const sums = new Map();
  for (const [a, b, w] of graph.type_edges) {
    const na = nodeOf[a];
    const nb = nodeOf[b];
    if (na < 0 || nb < 0) continue;
    const key = na * 100000 + nb;
    sums.set(key, (sums.get(key) || 0) + w);
  }
  let edges = [...sums.entries()].map(([key, w]) => ({ a: Math.floor(key / 100000), b: key % 100000, w }));
  edges = edges.filter((e) => showRecurrent || nodes[e.a].region !== nodes[e.b].region);
  edges.sort((x, y) => y.w - x.w);
  edges = edges.slice(0, limit);
  const peakWeight = Math.max(1, ...edges.map((e) => e.w));

  const root = svg("svg");
  const defs = svg("defs");
  for (const [sign, colour] of [["excitatory", "#c9a24a"], ["inhibitory", "#5d8fd8"], ["unknown", "#7a8089"]]) {
    for (const focus of ["", "-focus"]) {
      const fill = focus ? "#ffffff" : colour;
      const marker = svg("marker", { id: `end-${sign}${focus}`, viewBox: "0 0 10 10", refX: sign === "inhibitory" ? 5 : 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto", markerUnits: "userSpaceOnUse" });
      if (sign === "excitatory") marker.append(svg("path", { d: "M0 1 9 5 0 9z", fill }));
      else if (sign === "inhibitory") marker.append(svg("rect", { x: 4, y: 0, width: 2.4, height: 10, fill }));
      else marker.append(svg("circle", { cx: 6, cy: 5, r: 3.5, fill }));
      defs.append(marker);
    }
  }
  root.append(defs);

  // Region bands with their neuropil, component and neuron count.
  const bandLayer = svg("g");
  const headLayer = svg("g", { class: "heads" });
  for (const region of regions) {
    const present = inModel(region.component);
    const band = svg("g", { class: `band${present ? "" : " absent"}${readouts.has(region.component) ? " read" : ""}` });
    band.append(svg("rect", { x: region.x - CIRCUIT.boxLeft, y: region.y, width: CIRCUIT.boxWidth, height: region.h, rx: 8, class: "band-box" }));
    bandLayer.append(band);
    const head = svg("g", { class: `band-head${present ? "" : " absent"}` });
    head.append(svg("text", { x: region.x - 22, y: region.y + 17, class: "band-title" }, region.heading));
    head.append(svg("text", { x: region.x - 22, y: region.y + 31, class: "band-sub" }, `${humane(region.component)} · ${number(region.neurons)}${present ? "" : " · not in model"}`));
    headLayer.append(head);
  }
  root.append(bandLayer);

  // The eye feeds the photoreceptors; the readout takes the components the model reads.
  const terminals = svg("g", { class: "terminals" });
  const retina = regions.find((r) => r.component === "photoreceptors");
  if (retina) {
    const y = retina.y + retina.h / 2;
    terminals.append(svg("rect", { x: 8, y: y - 30, width: 108, height: 60, rx: 8, class: "terminal" }));
    terminals.append(svg("text", { x: 16, y: y - 9, class: "band-title" }, "Compound eye"));
    terminals.append(svg("text", { x: 16, y: y + 6, class: "band-sub" }, "fly-vision"));
    terminals.append(svg("text", { x: 16, y: y + 19, class: "band-sub" }, compilation.input_width ? `${number(compilation.input_width)} ports` : "R1_6, R7, R8"));
    terminals.append(svg("path", { d: `M116 ${y} L${retina.x - CIRCUIT.boxLeft - 2} ${y}`, class: "terminal-edge", "marker-end": "url(#end-excitatory)" }));
  }
  if (readouts.size) {
    const x = CIRCUIT.left + (columns - 1) * CIRCUIT.columnWidth + 150;
    const y = rowTop[1] + 20;
    terminals.append(svg("rect", { x, y, width: 112, height: 60, rx: 8, class: "terminal read" }));
    terminals.append(svg("text", { x: x + 10, y: y + 21, class: "band-title" }, "Readout"));
    terminals.append(svg("text", { x: x + 10, y: y + 36, class: "band-sub" }, "presence, bearing"));
    terminals.append(svg("text", { x: x + 10, y: y + 49, class: "band-sub" }, compilation.readout_neuron_count ? `${number(compilation.readout_neuron_count)} neurons` : ""));
    for (const region of regions) {
      if (!readouts.has(region.component)) continue;
      const sx = region.x - CIRCUIT.boxLeft + CIRCUIT.boxWidth;
      const sy = region.y + 20;
      terminals.append(svg("path", { d: `M${sx} ${sy} C${sx + 60} ${sy}, ${x - 60} ${y + 30}, ${x} ${y + 30}`, class: "readout-edge", "marker-end": "url(#end-excitatory)" }));
    }
  }
  root.append(terminals);

  // Edges, weakest first so the strongest draw on top. Each leaves its column box on the
  // right and enters the target box on the left, level with its nodes.
  const edgeLayer = svg("g", { class: "edges" });
  const outX = (node) => node.region.x - CIRCUIT.boxLeft + CIRCUIT.boxWidth;
  const inX = (node) => node.region.x - CIRCUIT.boxLeft;
  for (const edge of [...edges].reverse()) {
    const a = nodes[edge.a];
    const b = nodes[edge.b];
    const sign = transmitterOf(a.transmitter).sign;
    const width = 0.6 + 2.6 * (Math.log1p(edge.w) / Math.log1p(peakWeight));
    const sx = outX(a);
    const tx = inX(b);
    let d;
    if (a.region === b.region) {
      // Recurrent inside a neuropil: an arc out of the box's right side and back in.
      const bulge = 18 + Math.min(46, Math.abs(b.y - a.y) * 0.3);
      d = edge.a === edge.b
        ? `M${sx} ${a.y - 4} C${sx + 16} ${a.y - 12}, ${sx + 16} ${a.y + 12}, ${sx + 2} ${a.y + 4}`
        : `M${sx} ${a.y} C${sx + bulge} ${a.y}, ${sx + bulge} ${b.y}, ${sx + 3} ${b.y}`;
    } else if (b.region.column > a.region.column || a.region.row !== b.region.row) {
      const dx = Math.max(40, (tx - sx) * 0.5);
      d = `M${sx} ${a.y} C${sx + dx} ${a.y}, ${tx - dx} ${b.y}, ${tx} ${b.y} L${b.x - b.r - 3} ${b.y}`;
    } else {
      // Feedback to an earlier column of the same row: over the top row, under the bottom one.
      const top = a.region.row === 0;
      const lane = top ? Math.min(a.region.y, b.region.y) - 16 : Math.max(a.region.y + a.region.h, b.region.y + b.region.h) + 16;
      d = `M${sx} ${a.y} C${sx + 40} ${a.y}, ${sx + 40} ${lane}, ${sx} ${lane} L${tx} ${lane} C${tx - 40} ${lane}, ${tx - 40} ${b.y}, ${tx} ${b.y} L${b.x - b.r - 3} ${b.y}`;
    }
    const path = svg("path", {
      d, class: `edge ${sign}`, "stroke-width": width.toFixed(2), "marker-end": `url(#end-${sign})`,
      "data-a": edge.a, "data-b": edge.b,
    });
    path.append(svg("title", {}, `${a.label} → ${b.label}: ${number(edge.w)} synapses (${transmitterOf(a.transmitter).name})`));
    edgeLayer.append(path);
  }
  root.append(edgeLayer);

  // Nodes, labelled to their right.
  const nodeLayer = svg("g", { class: "nodes" });
  circuit.nodes = new Map();
  nodes.forEach((node, index) => {
    const t = transmitterOf(node.transmitter);
    const present = inModel(node.component);
    const group = svg("g", { class: `cnode${node.folded ? " folded" : ""}${present ? "" : " absent"}`, transform: `translate(${node.x} ${node.y})`, tabindex: 0, "data-index": index });
    const glow = svg("circle", { r: node.r + 2, class: "glow", opacity: 0 });
    const dot = svg("circle", { r: node.r, fill: node.folded ? "none" : t.colour, stroke: t.colour, class: "dot" });
    group.append(glow, dot, svg("text", { x: 13, y: 4, class: "clabel" }, node.label.length > 14 ? `${node.label.slice(0, 13)}…` : node.label));
    group.append(svg("title", {}, `${node.label} · ${number(node.count)} neurons · ${t.name}`));
    const focus = () => focusNode(index, nodes, edges);
    group.addEventListener("pointerenter", focus);
    group.addEventListener("focus", focus);
    group.addEventListener("pointerleave", () => focusNode(null, nodes, edges));
    group.addEventListener("blur", () => focusNode(null, nodes, edges));
    nodeLayer.append(group);
    circuit.nodes.set(index, { node, glow, dot });
  });
  root.append(nodeLayer, headLayer);

  for (const attribute of root.attributes) target.setAttribute(attribute.name, attribute.value);
  target.replaceChildren(...root.childNodes);
  circuit.full = { x: 0, y: 0, w: width, h: height };
  circuit.view = { ...circuit.full };
  setViewBox();
  circuit.graphNodes = nodes;

  // Legend: transmitters, edge ends, widths.
  $("circuit-legend").replaceChildren(
    ...Object.values(TRANSMITTERS).map((t) => {
      const key = element("span", `${t.name} (${t.sign})`, "key");
      key.style.setProperty("--key", t.colour);
      return key;
    }),
    element("span", "→ excitatory   ⊣ inhibitory   width: synapses", "key plain"),
  );
  $("brain-circuit-note").textContent = `${regions.length} neuropils and ${nodes.length} cell-type nodes from MaleCNS; the ${number(edges.length)} strongest connections between them are drawn. Each column keeps its ${CIRCUIT.maxTypes} most connected cell types and folds the rest into one hollow node.${name ? ` Faded columns are not in ${name}; amber-outlined columns feed its readout.` : ""} Scroll to zoom, drag to pan, double-click to fit.`;
  paintCircuitActivity();
}

function focusNode(index, nodes, edges) {
  const target = $("brain-circuit-svg");
  circuit.focus = index;
  target.classList.toggle("focus", index !== null);
  const linked = new Set(index === null ? [] : [index]);
  for (const path of target.querySelectorAll(".edge")) {
    const a = Number(path.dataset.a);
    const b = Number(path.dataset.b);
    const on = index !== null && (a === index || b === index);
    path.classList.toggle("on", on);
    if (on) {
      linked.add(a);
      linked.add(b);
      const sign = path.classList.contains("inhibitory") ? "inhibitory" : path.classList.contains("excitatory") ? "excitatory" : "unknown";
      path.setAttribute("marker-end", `url(#end-${sign}-focus)`);
    } else {
      const sign = path.classList.contains("inhibitory") ? "inhibitory" : path.classList.contains("excitatory") ? "excitatory" : "unknown";
      path.setAttribute("marker-end", `url(#end-${sign})`);
    }
  }
  for (const group of target.querySelectorAll(".cnode")) group.classList.toggle("linked", linked.has(Number(group.dataset.index)));
  if (index !== null && nodes[index].types.length === 1 && window.brainDescribeType) window.brainDescribeType(nodes[index].types[0]);
}

function setViewBox() {
  const v = circuit.view;
  $("brain-circuit-svg").setAttribute("viewBox", `${v.x} ${v.y} ${v.w} ${v.h}`);
}

function stageActivity() {
  if (!brain.trace) return null;
  const totals = {};
  for (const group of brain.trace.groups) {
    const value = Math.abs(group.mean[brain.step] ?? 0) * group.count;
    const entry = (totals[group.stage] = totals[group.stage] || { sum: 0, count: 0 });
    entry.sum += value;
    entry.count += group.count;
  }
  const means = Object.fromEntries(Object.entries(totals).map(([stage, t]) => [stage, t.sum / Math.max(1, t.count)]));
  const peak = Math.max(1e-6, ...Object.values(means));
  return { means, peak };
}

/** During playback each cell type's node glows with its activity at this step. */
function paintCircuitActivity() {
  if (!circuit.nodes.size) return;
  const activity = brain.traceClip && brain.frame && brain.traceClip === brain.frame.clipId && $("circuit-activity").checked ? typeActivity() : null;
  const names = net.graph ? net.graph.cell_types : [];
  let low = Infinity;
  if (activity) for (const v of Object.values(activity.values)) low = Math.min(low, v);
  const span = activity ? Math.max(1e-6, activity.peak - low) : 1;
  for (const { node, glow } of circuit.nodes.values()) {
    let value = null;
    if (activity) {
      for (const i of node.types) {
        const v = activity.values[names[i].name];
        if (v !== undefined) value = Math.max(value ?? 0, v);
      }
    }
    const t = value === null ? 0 : (value - low) / span;
    glow.setAttribute("opacity", String(value === null ? 0 : 0.12 + 0.6 * t));
    glow.setAttribute("r", String(node.r + 2 + 5 * t));
  }
}

{
  const target = $("brain-circuit-svg");
  let drag = null;
  const scale = () => circuit.view.w / target.clientWidth;
  target.addEventListener("wheel", (event) => {
    if (!circuit.view) return;
    event.preventDefault();
    const box = target.getBoundingClientRect();
    const fx = (event.clientX - box.left) / box.width;
    const fy = (event.clientY - box.top) / box.height;
    const factor = Math.exp(event.deltaY * 0.0012);
    const v = circuit.view;
    const w = Math.max(200, Math.min(circuit.full.w * 2, v.w * factor));
    const h = (w / v.w) * v.h;
    circuit.view = { x: v.x + (v.w - w) * fx, y: v.y + (v.h - h) * fy, w, h };
    setViewBox();
  }, { passive: false });
  target.addEventListener("pointerdown", (event) => {
    if (event.target.closest(".cnode")) return;
    drag = { x: event.clientX, y: event.clientY, id: event.pointerId };
    target.setPointerCapture(event.pointerId);
  });
  target.addEventListener("pointermove", (event) => {
    if (!drag || drag.id !== event.pointerId) return;
    const s = scale();
    circuit.view.x -= (event.clientX - drag.x) * s;
    circuit.view.y -= (event.clientY - drag.y) * s;
    drag.x = event.clientX;
    drag.y = event.clientY;
    setViewBox();
  });
  const release = () => (drag = null);
  target.addEventListener("pointerup", release);
  target.addEventListener("pointercancel", release);
  target.addEventListener("dblclick", () => {
    if (!circuit.full) return;
    circuit.view = { ...circuit.full };
    setViewBox();
  });
  for (const id of ["circuit-edges", "circuit-recurrent"]) $(id).addEventListener("input", () => drawCircuit().catch(report));
  $("circuit-activity").addEventListener("input", paintCircuitActivity);
}

// Network: the connectome itself, from --connectome. Nodes are cell types (or, at the
// neuron level, every central-complex neuron); edges are synapses. The layout is
// force-directed but anchored left to right in the order signals flow, and compass
// neurons sit on a ring at their protocerebral-bridge angle.

const COMPONENT_FLOW = ["photoreceptors", "lamina_relays", "medulla_relays", "visual_projection", "anterior_visual_inputs", "visual_kenyon", "tubercle_bulb", "ring_neurons", "compass", "steering"];
const net = { graph: null, level: "types", nodes: [], edges: [], view: { x: 0, y: 0, k: 1 }, hover: null, running: 0, search: "" };

async function ensureGraph() {
  const info = (((selection.dataset || {}).workspaces || {}).brain || {});
  if (!info.network) return null;
  if (!net.graph) net.graph = await api("/api/brain/network");
  return net.graph;
}

async function loadNetwork() {
  const level = location.search.match(/[?&]network=(types|neurons)/);
  if (level && !net.levelChosen) {
    net.levelChosen = true;
    net.level = level[1];
    for (const input of document.querySelectorAll('input[name="network-level"]')) input.checked = input.value === net.level;
  }
  const info = (((selection.dataset || {}).workspaces || {}).brain || {});
  if (!info.network) {
    $("network-status").textContent = "No connectome graph. Build one with honey-factory-tools connectome-graph, then start the inspector with --connectome FILE.";
    fitCanvas($("network-canvas"));
    return;
  }
  if (!net.described) {
    net.described = true;
    await ensureGraph();
    const g = net.graph;
    facts($("network-facts"), [
      ["Release", g.release],
      ["Neurons", number(g.neuron_count)],
      ["Edges", `${number(g.edge_count)} neuron pairs, at least ${g.minimum_synapse_count} synapses each`],
      ["Synapses", number(g.synapse_count)],
      ["Cell types", number(g.cell_types.length)],
    ]);
    $("network-legend").replaceChildren(
      ...g.components.map((c) => {
        const key = element("span", `${humane(c.name)} (${number(c.neurons)})`, "key");
        key.style.setProperty("--key", STAGE_COLOURS[c.name] || STAGE_COLOURS.other);
        return key;
      }),
      (() => { const k = element("span", "excitatory edge", "key"); k.style.setProperty("--key", "#c9a24a"); return k; })(),
      (() => { const k = element("span", "inhibitory edge", "key"); k.style.setProperty("--key", "#4f8fe0"); return k; })(),
    );
  }
  buildNetwork();
}

function buildNetwork() {
  const g = net.graph;
  const inhibitory = new Set(g.inhibitory_transmitters || []);
  const column = (component) => Math.max(0, COMPONENT_FLOW.indexOf(component));
  const limit = Number($("network-edges").value);
  let nodes;
  let edges;
  if (net.level === "types") {
    nodes = g.cell_types.map((t, i) => ({ i, name: t.name, component: t.component, count: t.count, transmitter: t.transmitter, r: 3 + 2.2 * Math.sqrt(t.count) ** 0.75 }));
    edges = g.type_edges.slice(0, limit).filter(([a, b]) => a !== b).map(([a, b, w, pairs]) => ({ a, b, w, pairs }));
  } else {
    const types = g.cell_types;
    nodes = g.neurons.map((n, i) => ({ i, name: types[n.cell_type].name, component: types[n.cell_type].component, count: 1, transmitter: types[n.cell_type].transmitter, body: n.body_id, side: n.side, instance: n.instance, angle: n.pb_angle, r: 3.2 }));
    edges = [...g.neuron_edges].sort((x, y) => y[2] - x[2]).slice(0, limit).filter(([a, b]) => a !== b).map(([a, b, w]) => ({ a, b, w, pairs: 1 }));
  }
  const columns = [...new Set(nodes.map((n) => column(n.component)))].sort((a, b) => a - b);
  const spacing = net.level === "types" ? 300 : 240;
  const place = (c) => (columns.indexOf(c) - (columns.length - 1) / 2) * spacing;
  for (const n of nodes) {
    n.cx = place(column(n.component));
    n.x = n.cx + (Math.random() - 0.5) * 60;
    n.y = (Math.random() - 0.5) * 400;
    n.vx = 0;
    n.vy = 0;
    n.inhibitory = inhibitory.has(n.transmitter);
    n.pinned = false;
    if (net.level === "neurons" && n.component === "compass" && n.angle !== null && n.angle !== undefined) {
      // A ring at the compass column: 0° (straight ahead) at the top.
      const radius = n.name === "EPG" ? 120 : n.name.startsWith("PEN") ? 150 : 175;
      const a = (n.angle * Math.PI) / 180;
      n.x = n.cx + Math.sin(a) * radius;
      n.y = -Math.cos(a) * radius;
      n.pinned = true;
    }
  }
  const peak = Math.max(1, ...edges.map((e) => e.w));
  for (const e of edges) e.strength = Math.log1p(e.w) / Math.log1p(peak);
  net.nodes = nodes;
  net.edges = edges;
  net.adjacency = nodes.map(() => ({ in: [], out: [] }));
  const all = net.level === "types" ? g.type_edges.map(([a, b, w]) => ({ a, b, w })) : g.neuron_edges.map(([a, b, w]) => ({ a, b, w }));
  for (const e of all) {
    if (e.a === e.b) continue;
    net.adjacency[e.b].in.push(e);
    net.adjacency[e.a].out.push(e);
  }
  net.hover = null;
  net.running = 360;
  net.userMoved = false;
  // Most of the settling happens before the first paint, so the graph opens readable;
  // the last ticks animate.
  const started = performance.now();
  while (net.running > 90 && performance.now() - started < 400) step();
  fitNetwork();
  $("network-note").textContent = net.level === "types"
    ? `${number(nodes.length)} cell types, sized by neuron count; ${number(edges.length)} strongest connections drawn. Columns follow the flow from the eye to steering.`
    : `${number(nodes.length)} central-complex neurons; compass neurons sit on a ring at their bridge angle (EPG inside, PEN, then PEG and Δ7). ${number(edges.length)} strongest connections drawn.`;
  requestAnimationFrame(simulate);
}

function simulate() {
  if (net.running <= 0) return;
  step();
  // Keep the whole graph in view while it settles, until someone pans or zooms.
  if (!net.userMoved && (net.running % 30 === 0 || net.running <= 0)) fitNetwork();
  drawNetwork();
  $("network-status").textContent = net.running > 0 ? `Laying out… ${Math.round(100 - (net.running / 360) * 100)}%` : "";
  if (net.running > 0) requestAnimationFrame(simulate);
}

/** Three ticks of the force layout. */
function step() {
  const nodes = net.nodes;
  const alpha = Math.max(0.02, net.running / 360);
  for (let step = 0; step < 3; step++) {
    // Repulsion between nearby nodes in the same or neighbouring columns.
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i];
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j];
        const dx = b.x - a.x;
        if (dx > 320 || dx < -320) continue;
        const dy = b.y - a.y;
        const d2 = dx * dx + dy * dy + 0.01;
        if (d2 > 90000) continue;
        const d = Math.sqrt(d2);
        const min = a.r + b.r + 10;
        // Inverse-square repulsion, capped so near-coincident nodes cannot explode, plus a
        // firm push apart while they overlap.
        const force = Math.min(4, (alpha * 2600) / d2) + (d < min ? (min - d) * 0.3 : 0);
        const fx = (dx / d) * force;
        const fy = (dy / d) * force;
        a.vx -= fx; a.vy -= fy;
        b.vx += fx; b.vy += fy;
      }
    }
    for (const e of net.edges) {
      const a = nodes[e.a];
      const b = nodes[e.b];
      const dy = b.y - a.y;
      const pull = 0.004 * alpha * e.strength;
      a.vy += dy * pull;
      b.vy -= dy * pull;
    }
    for (const n of nodes) {
      if (n.pinned) { n.vx = n.vy = 0; continue; }
      n.vx += (n.cx - n.x) * 0.06 * alpha;
      n.vy += -n.y * 0.0008 * alpha;
      n.vx *= 0.6;
      n.vy *= 0.6;
      n.x += n.vx;
      n.y += n.vy;
    }
  }
  net.running -= 3;
}

function fitNetwork() {
  const canvas = $("network-canvas");
  if (!canvas.clientWidth || net.nodes.some((n) => !Number.isFinite(n.x) || !Number.isFinite(n.y))) return;
  const xs = net.nodes.map((n) => n.x);
  const ys = net.nodes.map((n) => n.y);
  const [x0, x1, y0, y1] = [Math.min(...xs) - 30, Math.max(...xs) + 30, Math.min(...ys) - 30, Math.max(...ys) + 30];
  const ratio = devicePixelRatio;
  const k = Math.min((canvas.clientWidth * ratio) / (x1 - x0), (canvas.clientHeight * ratio) / (y1 - y0));
  net.view = { k, x: (canvas.clientWidth * ratio) / 2 - ((x0 + x1) / 2) * k, y: (canvas.clientHeight * ratio) / 2 - ((y0 + y1) / 2) * k };
}

window.brainActivity = () => {
  const activity = typeActivity();
  return activity ? { ...activity, step: `${brain.traceClip}:${brain.step}` } : null;
};

function typeActivity() {
  if (!brain.trace || !brain.frame || brain.traceClip !== brain.frame.clipId) return null;
  const values = {};
  let peak = 1e-6;
  for (const group of brain.trace.groups) {
    const v = Math.abs(group.mean[brain.step] ?? 0);
    values[group.cell_type] = v;
    peak = Math.max(peak, v);
  }
  return { values, peak };
}

function drawNetwork() {
  const canvas = $("network-canvas");
  const context = fitCanvas(canvas);
  if (!net.nodes.length) return;
  const { x: ox, y: oy, k } = net.view;
  const ratio = devicePixelRatio;
  const at = (n) => [ox + n.x * k, oy + n.y * k];
  const hover = net.hover;
  const linked = new Set();
  if (hover !== null) {
    linked.add(hover);
    for (const e of net.adjacency[hover].in) linked.add(e.a);
    for (const e of net.adjacency[hover].out) linked.add(e.b);
  }
  const search = net.search;
  // Edges first, faint; the hovered node's edges on top, bright.
  context.lineWidth = Math.max(0.5, 0.8 * ratio);
  for (const e of net.edges) {
    const a = net.nodes[e.a];
    const b = net.nodes[e.b];
    const focus = hover !== null && (e.a === hover || e.b === hover);
    if (hover !== null && !focus) continue;
    const [ax, ay] = at(a);
    const [bx, by] = at(b);
    const alpha = focus ? 0.35 + 0.6 * e.strength : 0.04 + 0.22 * e.strength ** 2;
    context.strokeStyle = a.inhibitory ? `rgb(79 143 224 / ${alpha})` : `rgb(201 162 74 / ${alpha})`;
    context.beginPath();
    context.moveTo(ax, ay);
    const mx = (ax + bx) / 2;
    context.bezierCurveTo(mx, ay, mx, by, bx, by);
    context.stroke();
  }
  const activity = typeActivity();
  for (const n of net.nodes) {
    const [x, y] = at(n);
    const r = Math.max(1.5 * ratio, n.r * k);
    const dim = hover !== null && !linked.has(n.i);
    const match = search && n.name.toLowerCase().includes(search);
    context.globalAlpha = dim ? 0.15 : 1;
    if (activity && activity.values[n.name] !== undefined) {
      const glow = activity.values[n.name] / activity.peak;
      context.fillStyle = `rgb(240 180 41 / ${0.15 + 0.5 * glow})`;
      context.beginPath();
      context.arc(x, y, r + (3 + 9 * glow) * ratio, 0, 2 * Math.PI);
      context.fill();
    }
    context.fillStyle = STAGE_COLOURS[n.component] || STAGE_COLOURS.other;
    context.beginPath();
    context.arc(x, y, r, 0, 2 * Math.PI);
    context.fill();
    if (n.i === hover || match) {
      context.strokeStyle = cssVar("--ink");
      context.lineWidth = 2 * ratio;
      context.stroke();
    }
  }
  context.globalAlpha = 1;
  // Labels: the hovered node and its partners, search matches, and the biggest nodes when zoomed in.
  context.font = `${11 * ratio}px ${cssVar("--mono")}`;
  context.textBaseline = "middle";
  for (const n of net.nodes) {
    const show = n.i === hover || (hover !== null && linked.has(n.i) && linked.size < 40) || (search && n.name.toLowerCase().includes(search)) || (hover === null && !search && n.r * k > 9 * ratio);
    if (!show) continue;
    const [x, y] = at(n);
    const label = net.level === "neurons" && n.i === hover ? n.instance : n.name;
    context.fillStyle = cssVar("--bg");
    const w = context.measureText(label).width;
    context.globalAlpha = 0.75;
    context.fillRect(x + n.r * k + 3 * ratio, y - 7 * ratio, w + 6 * ratio, 14 * ratio);
    context.globalAlpha = 1;
    context.fillStyle = cssVar("--ink");
    context.fillText(label, x + n.r * k + 6 * ratio, y);
  }
  context.textBaseline = "alphabetic";
}

/** The ten most active cell types at this step, for the Network and 3D side panel. */
function drawMostActive() {
  const activity = typeActivity();
  $("network-active-section").hidden = !activity;
  if (!activity) return;
  const components = Object.fromEntries((net.graph ? net.graph.cell_types : []).map((t) => [t.name, t.component]));
  const stages = Object.fromEntries(brain.trace.groups.map((g) => [g.cell_type, g.stage]));
  const top = Object.entries(activity.values).sort((a, b) => b[1] - a[1]).slice(0, 10);
  $("network-active").replaceChildren(...top.map(([name, value]) => {
    const row = element("div", null, "bar-row");
    const bar = element("span", null, "bar");
    bar.style.setProperty("--value", String(value / activity.peak));
    const group = components[name] || stages[name];
    bar.style.setProperty("--fill", STAGE_COLOURS[group] || STAGE_COLOURS.other);
    row.append(element("span", name, "bar-label wide"), bar, element("span", number(value, 3), "num"));
    return row;
  }));
}

function paintNetworkActivity() {
  if (net.running <= 0) drawNetwork();
}

/** The side panel for one cell type of the graph: used by the 3D brain on hover. */
window.brainDescribeType = (typeIndex) => {
  const g = net.graph;
  if (!g) return;
  const type = g.cell_types[typeIndex];
  $("network-node-title").textContent = type.name;
  const activity = typeActivity();
  facts($("network-node-facts"), [
    ["Component", humane(type.component)],
    ["Neurons", number(type.count)],
    ["Transmitter", type.transmitter],
    ["Sides", Object.entries(type.sides).map(([side, n]) => `${side} ${n}`).join(", ")],
    ["Activity now", activity && activity.values[type.name] !== undefined ? number(activity.values[type.name], 4) : undefined],
  ]);
  const rows = (list, key) => {
    const top = list.sort((a, b) => b[2] - a[2]).slice(0, 8);
    const peak = Math.max(1, ...top.map((e) => e[2]));
    return top.map((e) => {
      const other = g.cell_types[e[key]];
      const row = element("div", null, "bar-row");
      const bar = element("span", null, "bar");
      bar.style.setProperty("--value", String(e[2] / peak));
      bar.style.setProperty("--fill", STAGE_COLOURS[other.component] || STAGE_COLOURS.other);
      row.append(element("span", other.name, "bar-label wide"), bar, element("span", number(e[2]), "num"));
      return row;
    });
  };
  $("network-inputs").replaceChildren(...rows(g.type_edges.filter((e) => e[1] === typeIndex && e[0] !== typeIndex), 0));
  $("network-outputs").replaceChildren(...rows(g.type_edges.filter((e) => e[0] === typeIndex && e[1] !== typeIndex), 1));
};

function describeNetworkNode(index) {
  if (index === null) return;
  const n = net.nodes[index];
  const g = net.graph;
  $("network-node-title").textContent = net.level === "neurons" ? n.instance : n.name;
  const activity = typeActivity();
  facts($("network-node-facts"), [
    ["Cell type", n.name],
    ["Component", humane(n.component)],
    ["Neurons", net.level === "types" ? number(n.count) : undefined],
    ["Body ID", n.body],
    ["Bridge angle", n.angle === null || n.angle === undefined ? undefined : `${number(n.angle, 0)}°`],
    ["Transmitter", `${n.transmitter}${n.inhibitory ? " (inhibitory)" : n.transmitter === "acetylcholine" ? " (excitatory)" : ""}`],
    ["Activity now", activity && activity.values[n.name] !== undefined ? number(activity.values[n.name], 4) : undefined],
  ]);
  const label = (i) => (net.level === "types" ? g.cell_types[i].name : net.nodes[i].instance);
  const bars = (list, key) => {
    const top = [...list].sort((a, b) => b.w - a.w).slice(0, 8);
    const peak = Math.max(1, ...top.map((e) => e.w));
    return top.map((e) => {
      const row = element("div", null, "bar-row");
      const bar = element("span", null, "bar");
      bar.style.setProperty("--value", String(e.w / peak));
      bar.style.setProperty("--fill", STAGE_COLOURS[net.nodes[e[key]].component] || STAGE_COLOURS.other);
      row.append(element("span", label(e[key]), "bar-label wide"), bar, element("span", number(e.w), "num"));
      return row;
    });
  };
  const adjacency = net.adjacency[index];
  $("network-inputs").replaceChildren(...bars(adjacency.in, "a"));
  $("network-outputs").replaceChildren(...bars(adjacency.out, "b"));
}

{
  const canvas = $("network-canvas");
  let drag = null;
  const toWorld = (event) => {
    const box = canvas.getBoundingClientRect();
    const px = (event.clientX - box.left) * devicePixelRatio;
    const py = (event.clientY - box.top) * devicePixelRatio;
    return [px, py, (px - net.view.x) / net.view.k, (py - net.view.y) / net.view.k];
  };
  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    const [px, py] = toWorld(event);
    const factor = Math.exp(-event.deltaY * 0.0015);
    net.view.x = px - (px - net.view.x) * factor;
    net.view.y = py - (py - net.view.y) * factor;
    net.view.k *= factor;
    net.userMoved = true;
    drawNetwork();
  }, { passive: false });
  canvas.addEventListener("pointerdown", (event) => {
    drag = { x: event.clientX, y: event.clientY, id: event.pointerId };
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener("pointermove", (event) => {
    if (drag && drag.id === event.pointerId) {
      net.userMoved = true;
      net.view.x += (event.clientX - drag.x) * devicePixelRatio;
      net.view.y += (event.clientY - drag.y) * devicePixelRatio;
      drag.x = event.clientX;
      drag.y = event.clientY;
      drawNetwork();
      return;
    }
    const [, , wx, wy] = toWorld(event);
    let best = null;
    let bestDistance = Infinity;
    for (const n of net.nodes) {
      const d = (n.x - wx) ** 2 + (n.y - wy) ** 2;
      const reach = n.r + 6 / net.view.k;
      if (d < reach * reach && d < bestDistance) {
        best = n.i;
        bestDistance = d;
      }
    }
    const tip = $("network-tip");
    if (best === null) tip.hidden = true;
    else {
      const n = net.nodes[best];
      const box = canvas.getBoundingClientRect();
      const inputs = net.adjacency[best].in.length;
      const outputs = net.adjacency[best].out.length;
      tip.replaceChildren(element("strong", net.level === "neurons" ? n.instance : n.name), element("span", `${humane(n.component)} · ${net.level === "types" ? `${number(n.count)} neurons · ` : ""}${inputs} inputs, ${outputs} outputs`));
      tip.style.left = `${Math.min(box.width - 230, event.clientX - box.left + 14)}px`;
      tip.style.top = `${event.clientY - box.top + 12}px`;
      tip.hidden = false;
    }
    if (best !== net.hover) {
      net.hover = best;
      describeNetworkNode(best);
      drawNetwork();
    }
  });
  const release = () => (drag = null);
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  canvas.addEventListener("pointerleave", () => ($("network-tip").hidden = true));
  canvas.addEventListener("dblclick", () => { net.userMoved = false; fitNetwork(); drawNetwork(); });
  // The canvas may be laid out after the graph is built (a hidden panel has no size), so
  // fit again whenever it changes size, unless someone has panned or zoomed.
  new ResizeObserver(() => {
    if (!net.nodes.length) return;
    if (!net.userMoved) fitNetwork();
    drawNetwork();
  }).observe(canvas);
}
for (const input of document.querySelectorAll('input[name="network-level"]')) {
  input.addEventListener("input", () => {
    net.level = input.value;
    if (net.graph) buildNetwork();
  });
}
$("network-edges").addEventListener("input", () => net.graph && buildNetwork());
$("network-search").addEventListener("input", (event) => {
  net.search = event.target.value.trim().toLowerCase();
  drawNetwork();
});

$("brain-run-filter").addEventListener("input", () => brain.runs && drawRuns());
$("brain-run-family").addEventListener("input", () => brain.runs && drawRuns());
for (const input of document.querySelectorAll('input[name="brain-view"]')) {
  input.addEventListener("input", () => {
    setView(input.value);
    location.hash = `brain-${input.value}`;
    show(selection).catch(report);
  });
}
new ResizeObserver(() => {
  if (!brain.active) return;
  if (brain.view === "playback" && brain.trace && brain.frame) follow(brain.frame);
}).observe($("brain-panel"));

workspaces.register("brain", {
  show: (sel) => show(sel).catch(report),
  hide() {
    brain.active = false;
    if (window.brain3d) window.brain3d.hide();
    filterClipList(false);
  },
  frame(sel) {
    filterClipList(true);
    if (brain.view === "playback" || brain.view === "circuit") {
      if (openFirstTraced(sel)) return;
    }
    if (brain.view === "network" && !net.nodes.length) loadNetwork().catch(report);
    if (brain.view !== "runs") loadTrace(sel).catch(report);
  },
  sample() {},
});

}
