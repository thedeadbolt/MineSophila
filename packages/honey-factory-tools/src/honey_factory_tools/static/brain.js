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
  const dataset = sel.dataset || {};
  const traced = ((dataset.workspaces || {}).brain || {}).traced_clips || [];
  const asked = location.hash.match(/^#brain-(playback|circuit|runs)$/);
  if (!brain.view) brain.view = asked ? asked[1] : dataset.kind === "clips" && traced.length ? "playback" : "runs";
  setView(brain.view);
  if (brain.view === "runs") await loadRuns();
  else if (brain.view === "circuit") {
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
  $("brain-runs").hidden = view !== "runs";
  $("brain-trace-details").hidden = view !== "playback";
  $("brain-circuit-details").hidden = view !== "circuit";
  $("brain-run-details").hidden = view !== "runs";
  $("brain-circuit-run").hidden = view !== "circuit";
}

// Playback

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
    button.addEventListener("click", () => {
      const field = $("find-id");
      field.value = id;
      $("find").requestSubmit();
    });
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
    return;
  }
  drawHeatmap();
  drawCompass();
  drawDecisions();
  describeStep();
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
  const runs = (brain.runs.runs || []).filter((r) => !filter || `${r.name} ${r.family} ${r.dataset_id || ""}`.toLowerCase().includes(filter));
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

// Circuit: the selected run's components laid out in the order signals flow, from the
// eye to the readout. The pathway between components follows the fly's anatomy; the
// components, their sizes, the input and the readout come from the run's report.

const SVG = "http://www.w3.org/2000/svg";
const COMPONENTS = {
  eye: { label: "Compound eye", row: 0, col: 0, role: "fly-vision samples each frame through 1,726 ommatidia into R1_6, R7 and R8 signals." },
  input: { label: "Input map", row: 0, col: 1, role: "Assigns eye signals to the connectome's photoreceptor ports." },
  photoreceptors: { label: "Photoreceptors", cells: "R1–R6, R7, R8", row: 0, col: 2, role: "The connectome's photoreceptor neurons, driven by the eye signal." },
  lamina_relays: { label: "Lamina relays", cells: "L1–L5 and other lamina cells", row: 0, col: 3, role: "The optic lobe's first relay: brightness and contrast channels." },
  medulla_relays: { label: "Medulla relays", cells: "Mi, Tm and other medulla cells", row: 0, col: 4, role: "Columnar processing of colour, motion and form." },
  visual_projection: { label: "Visual projection", cells: "lobula columnar and other projection cells", row: 0, col: 5, role: "Optic-lobe outputs that carry visual features into the central brain." },
  visual_kenyon: { label: "Visual Kenyon cells", cells: "KC with visual input", row: 1, col: 5, role: "Mushroom-body Kenyon cells fed by vision; a candidate store for memory." },
  anterior_visual_inputs: { label: "Anterior visual path", cells: "MeTu", stage: "MeTu", row: 1, col: 4, role: "Medulla-to-tubercle neurons: the compass pathway's visual input." },
  tubercle_bulb: { label: "Tubercle to bulb", cells: "TuBu", stage: "TuBu", row: 1, col: 3, role: "Relays the anterior optic tubercle to the bulb." },
  ring_neurons: { label: "Ring neurons", cells: "ER", stage: "ER", row: 1, col: 2, role: "Ellipsoid-body ring neurons that bring visual landmarks to the compass." },
  compass: { label: "Compass", cells: "EPG, PEN, PEG, Δ7", stage: "compass", row: 1, col: 1, role: "The central complex's head-direction ring: a bump of activity tracks heading." },
  steering: { label: "Steering", cells: "PFL", stage: "PFL", row: 1, col: 0, role: "Compares heading with a goal direction to steer." },
  self_motion: { label: "Self-motion", cells: "yaw per frame", row: 2, col: 1, role: "The fly's own turning, fed to the compass so the bump can turn with it." },
  readout: { label: "Readout", row: 2, col: 3, role: "Trained heads that read presence and bearing from the chosen components." },
};
const PATHWAY = [
  ["eye", "input"], ["input", "photoreceptors"], ["photoreceptors", "lamina_relays"], ["lamina_relays", "medulla_relays"],
  ["medulla_relays", "visual_projection"], ["medulla_relays", "anterior_visual_inputs"], ["visual_projection", "visual_kenyon"],
  ["anterior_visual_inputs", "tubercle_bulb"], ["tubercle_bulb", "ring_neurons"], ["ring_neurons", "compass"],
  ["compass", "steering"], ["self_motion", "compass"],
];
const NODE = { w: 156, h: 74, dx: 184, dy: 118, pad: 16 };

function svg(tag, attributes, text) {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attributes || {})) node.setAttribute(key, String(value));
  if (text !== undefined) node.textContent = text;
  return node;
}

function chooseCircuitRun() {
  if (brain.circuitRun) return brain.circuitRun.name;
  const runs = (brain.runs && brain.runs.runs) || [];
  // The run that compiles the most components shows the most of the circuit.
  let best = null;
  for (const run of runs) {
    if (!run.format || !run.format.includes("connectome")) continue;
    if (!best || (run.neurons || 0) > (best.neurons || 0) || ((run.neurons || 0) === (best.neurons || 0) && run.name > best.name)) best = run;
  }
  return best ? best.name : runs.length ? runs[0].name : null;
}

async function drawCircuit() {
  const target = $("brain-circuit-svg");
  const name = chooseCircuitRun();
  if (!name) {
    target.replaceChildren();
    $("brain-circuit-note").textContent = "No run to draw. Start the inspector with --runs DIR.";
    return;
  }
  if (!brain.circuitRun || brain.circuitRun.name !== name) {
    brain.circuitRun = { name, report: await api(`/api/brain/runs/${encodeURIComponent(name)}`) };
  }
  const report = brain.circuitRun.report;
  $("brain-circuit-run").textContent = `${name}${brain.run ? "" : " · most complete run; pick another in Runs"}`;
  const components = (report.connectome || {}).components || {};
  const model = report.model || {};
  const compilation = model.compilation || {};
  const readouts = new Set(model.readout_components || []);
  const connectome = Object.keys(components).length > 0;
  const nodes = {};
  const edges = [];
  if (connectome) {
    for (const [id, spec] of Object.entries(COMPONENTS)) {
      const present = id === "eye" || id === "input" || id === "readout" || id in components || (id === "self_motion" && Boolean(model.self_motion));
      if (id === "self_motion" && !present) continue;
      nodes[id] = { ...spec, id, present, count: components[id] };
    }
    const sensory = (report.dataset || {}).sensory_features || {};
    nodes.input.detail = `${model.input_transform ? humane(model.input_transform) + ", " : ""}${number(compilation.input_width || sensory.width || 0)} ports`;
    nodes.eye.detail = "R1_6, R7, R8 per ommatidium";
    nodes.readout.detail = `${compilation.output_width || 4} outputs from ${number(compilation.readout_neuron_count || 0)} neurons`;
    // Real connectivity when the report has it (synapses between components), else anatomy.
    const measured = (report.connectome || {}).component_edges;
    const cellTypes = (report.connectome || {}).component_cell_types || {};
    for (const [id, types] of Object.entries(cellTypes)) {
      if (!nodes[id]) continue;
      const ranked = Object.entries(types).sort((a, b) => b[1] - a[1]);
      nodes[id].cellTypes = ranked;
      nodes[id].cells = ranked.slice(0, 4).map(([name]) => name).join(", ") + (ranked.length > 4 ? ` +${ranked.length - 4}` : "");
    }
    for (const [from, to] of PATHWAY) if (from === "eye" || from === "input" || from === "self_motion" || !measured) {
      if (nodes[from] && nodes[to]) edges.push({ from, to, present: nodes[from].present && nodes[to].present });
    }
    if (measured) {
      const all = Object.values(measured).flatMap((targets) => Object.values(targets));
      const peak = Math.max(1, ...all);
      for (const [from, targets] of Object.entries(measured)) {
        for (const [to, synapses] of Object.entries(targets)) {
          if (from === to || !nodes[from] || !nodes[to] || synapses <= 0) continue;
          edges.push({ from, to, present: true, synapses, weight: 0.8 + 3.2 * Math.log1p(synapses) / Math.log1p(peak) });
        }
      }
    }
    for (const id of readouts) if (nodes[id]) edges.push({ from: id, to: "readout", present: true, readout: true });
  } else {
    // Models without a connectome: the eye, the features they learn from, the model, the readout.
    const sensory = (report.dataset || {}).sensory_features;
    const kind = sensory && !Array.isArray(sensory) && sensory.kind ? humane(sensory.kind) : "eye features";
    const params = model.parameter_count || model.trainable_parameters;
    const layout = [
      ["eye", "Compound eye", "R1_6, R7, R8 per ommatidium"],
      ["features", "Input features", `${kind}${model.input_width ? `, width ${number(model.input_width)}` : ""}`],
      ["model", `${humane(report.format ? summaryFamily(report.format) : "model")}`, params ? `${number(params)} parameters` : model.kind ? humane(model.kind) : ""],
      ["readout", "Readout", "presence and bearing"],
    ];
    layout.forEach(([id, label, detail], col) => (nodes[id] = { id, label, detail, row: 0, col: col + 1, present: true, role: COMPONENTS[id] ? COMPONENTS[id].role : "" }));
    for (let k = 0; k + 1 < layout.length; k++) edges.push({ from: layout[k][0], to: layout[k + 1][0], present: true });
  }
  const cols = 1 + Math.max(...Object.values(nodes).map((n) => n.col));
  const rows = 1 + Math.max(...Object.values(nodes).map((n) => n.row));
  const width = NODE.pad * 2 + (cols - 1) * NODE.dx + NODE.w;
  const height = NODE.pad * 2 + (rows - 1) * NODE.dy + NODE.h;
  target.setAttribute("viewBox", `0 0 ${width} ${height}`);
  const defs = svg("defs");
  for (const [id, colour] of [["arrow", "var(--faint)"], ["arrow-honey", "var(--honey)"]]) {
    const marker = svg("marker", { id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" });
    marker.append(svg("path", { d: "M0 0 10 5 0 10z", fill: colour }));
    defs.append(marker);
  }
  const place = (n) => [NODE.pad + n.col * NODE.dx, NODE.pad + n.row * NODE.dy];
  const edgeLayer = svg("g");
  for (const edge of edges) {
    const a = nodes[edge.from];
    const b = nodes[edge.to];
    const [ax, ay] = place(a);
    const [bx, by] = place(b);
    let d;
    if (a.row === b.row) {
      const forward = b.col > a.col;
      const y = ay + NODE.h / 2;
      d = `M${forward ? ax + NODE.w : ax} ${y} L${forward ? bx : bx + NODE.w} ${y}`;
    } else if (a.col === b.col) {
      const down = b.row > a.row;
      d = `M${ax + NODE.w / 2} ${down ? ay + NODE.h : ay} L${bx + NODE.w / 2} ${down ? by : by + NODE.h}`;
    } else {
      const sx = ax + NODE.w / 2;
      const sy = b.row > a.row ? ay + NODE.h : ay;
      const ex = bx + NODE.w / 2;
      const ey = b.row > a.row ? by : by + NODE.h;
      const mid = (sy + ey) / 2;
      d = `M${sx} ${sy} C${sx} ${mid} ${ex} ${mid} ${ex} ${ey}`;
    }
    edgeLayer.append(svg("path", {
      d, fill: "none",
      stroke: edge.readout ? "var(--honey)" : "var(--faint)",
      "stroke-width": edge.readout ? 1.6 : edge.weight || 1.4,
      "stroke-dasharray": edge.present ? (edge.readout ? "5 4" : "") : "3 4",
      opacity: edge.present ? 1 : 0.4,
      "marker-end": `url(#${edge.readout ? "arrow-honey" : "arrow"})`,
    }));
    if (edge.synapses) edgeLayer.lastChild.append(svg("title", {}, `${nodes[edge.from].label} → ${nodes[edge.to].label}: ${number(edge.synapses)} synapses`));
  }
  const nodeLayer = svg("g");
  brain.circuitNodes = {};
  for (const node of Object.values(nodes)) {
    const [x, y] = place(node);
    const group = svg("g", { class: `node${node.present ? "" : " absent"}${readouts.has(node.id) ? " read" : ""}`, transform: `translate(${x} ${y})`, tabindex: 0, role: "button", "aria-label": node.label });
    const box = svg("rect", { width: NODE.w, height: NODE.h, rx: 6, class: "box" });
    const fill = svg("rect", { width: NODE.w, height: NODE.h, rx: 6, class: "activity", opacity: 0 });
    group.append(box, fill, svg("text", { x: 10, y: 21, class: "label" }, node.label));
    const count = node.count !== undefined ? `${number(node.count)} neurons` : node.detail || (node.present ? "" : "not in this model");
    group.append(svg("text", { x: 10, y: 40, class: "count" }, count.length > 21 ? `${count.slice(0, 20)}…` : count));
    if (node.detail && node.count === undefined) group.lastChild.append(svg("title", {}, node.detail));
    if (node.cells) group.append(svg("text", { x: 10, y: 58, class: "cells" }, node.cells.length > 27 ? `${node.cells.slice(0, 26)}…` : node.cells));
    else if (node.count !== undefined && node.detail) group.append(svg("text", { x: 10, y: 58, class: "cells" }, node.detail));
    if (node.count !== undefined) {
      const peak = Math.max(...Object.values(components));
      const share = Math.sqrt(node.count / peak);
      group.append(svg("rect", { x: 10, y: NODE.h - 8, width: (NODE.w - 20) * share, height: 3, rx: 1.5, class: "size" }));
    }
    const select = () => describeNode(node, report);
    group.addEventListener("click", select);
    group.addEventListener("keydown", (event) => event.key === "Enter" && select());
    nodeLayer.append(group);
    const meter = svg("rect", { x: 10, y: 4, width: 0, height: 3, rx: 1.5, class: "meter" });
    group.append(meter);
    brain.circuitNodes[node.id] = { node, fill, meter };
  }
  target.replaceChildren(defs, edgeLayer, nodeLayer);
  $("brain-circuit-note").textContent = connectome
    ? `${Object.keys(components).length} components, ${number(compilation.neuron_count || 0)} neurons and ${number(compilation.edge_count || 0)} connections in this model. ${(report.connectome || {}).component_edges ? "Arrow widths show synapse counts between components (hover one)." : "Arrows follow the fly's anatomy; this report does not list connections between components."} Dashed amber arrows mark what the readout uses.`
    : "This model has no connectome; it learns directly from eye features.";
  paintCircuitActivity();
}

function summaryFamily(format) {
  return format.replace(/^fly-brain-/, "").replace(/-v1$/, "").replace(/-report$/, "");
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

function paintCircuitActivity() {
  if (!brain.circuitNodes) return;
  const activity = brain.traceClip && brain.frame && brain.traceClip === brain.frame.clipId ? stageActivity() : null;
  for (const { node, fill, meter } of Object.values(brain.circuitNodes)) {
    const key = activity && activity.means[node.id] !== undefined ? node.id : node.stage;
    const value = activity && activity.means[key] !== undefined ? activity.means[key] / activity.peak : 0;
    fill.setAttribute("opacity", String(0.28 * value));
    if (meter) meter.setAttribute("width", String((NODE.w - 20) * value));
  }
}

function describeNode(node, report) {
  $("brain-node-title").textContent = node.label;
  $("brain-node-role").textContent = node.role || "";
  const model = report.model || {};
  const activity = stageActivity();
  const key = activity && activity.means[node.id] !== undefined ? node.id : node.stage;
  facts($("brain-node-facts"), [
    ["In this model", node.present ? "yes" : "no"],
    ["Neurons", node.count !== undefined ? number(node.count) : undefined],
    ["Cell types", node.cellTypes ? node.cellTypes.map(([name, n]) => `${name} ${n}`).join(", ") : undefined],
    ["Typical cell types", node.cellTypes ? undefined : node.cells],
    ["Details", node.detail],
    ["Read out", (model.readout_components || []).includes(node.id) ? "yes" : undefined],
    ["Activity now", activity && activity.means[key] !== undefined ? `${number(activity.means[key], 4)} mean |activity|` : undefined],
  ]);
}

$("brain-run-filter").addEventListener("input", () => brain.runs && drawRuns());
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
  },
  frame(sel) {
    if (brain.view !== "runs") loadTrace(sel).catch(report);
  },
  sample() {},
});

}
