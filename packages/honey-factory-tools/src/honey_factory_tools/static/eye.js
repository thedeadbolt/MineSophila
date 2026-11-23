"use strict";

// The Eye workspace (ADR 0029): the selected frame through fly-vision's compound eye.
// The server samples the frame; this page draws the camera image, the image decoded from
// the receptor signal (each pixel takes its nearest ommatidium), both eyes' lattices as
// hexagons, a globe of viewing directions, and the rhodopsin curves.
// Uses api(), element(), facts(), number() from app.js and workspaces.js's helpers.
{

const DECODE_SIZE = [620, 320];
const FIXED_PEAK = 0.75;
const RHODOPSIN_COLOURS = { Rh1: "#c9ccd1", Rh3: "#7a2fe0", Rh4: "#b48cff", Rh5: "#3d8bfd", Rh6: "#1e9e3c" };
const BAND_COLOURS = { R: "rgb(230 60 60 / 0.16)", G: "rgb(60 200 90 / 0.16)", B: "rgb(70 120 255 / 0.16)" };
const eye = {
  active: false,
  layout: null,
  unit: null,
  decodeMap: null,
  data: null,
  key: null,
  cache: new Map(),
  loading: null,
  pending: null,
  hover: null,
  globe: { yaw: -0.5, pitch: 0.35 },
  hexPoints: [],
  globePoints: [],
};

async function ready() {
  if (!eye.layout) {
    eye.layout = await api("/api/eye/layout");
    const { azimuth, elevation } = eye.layout;
    eye.unit = azimuth.map((az, i) => {
      const a = (az * Math.PI) / 180;
      const e = (elevation[i] * Math.PI) / 180;
      return [Math.cos(e) * Math.cos(a), Math.cos(e) * Math.sin(a), Math.sin(e)];
    });
    facts($("eye-model"), [
      ["fly-vision", eye.layout.fly_vision_version],
      ["Lattice", `${eye.layout.lattice_version}, ${eye.layout.count} ommatidia`],
      ["Sampled at", `${eye.layout.scale * 100}% of the capture`],
    ]);
  }
  if (!eye.decodeMap) {
    const response = await fetch(`/api/eye/decode-map?width=${DECODE_SIZE[0]}&height=${DECODE_SIZE[1]}`, { headers: { "X-Inspector-Token": token } });
    if (!response.ok) throw new Error(`decode map: ${response.status}`);
    eye.decodeMap = new Uint16Array(await response.arrayBuffer());
  }
}

function subject(sel) {
  if (sel.frame) return { key: `clip:${sel.frame.clipId}:${sel.frame.index}`, path: `/api/eye/clips/${encodeURIComponent(sel.frame.clipId)}/${sel.frame.index}`, image: sel.frame.image, label: `${sel.frame.clipId}, frame ${sel.frame.index}` };
  if (sel.sample) return { key: `sample:${sel.sample.sample.sample_id}`, path: `/api/eye/samples/${encodeURIComponent(sel.sample.sample.sample_id)}`, image: sel.sample.image, label: sel.sample.sample.sample_id };
  return null;
}

async function load(sel) {
  if (!eye.active) return;
  const wanted = subject(sel);
  if (!wanted) {
    status("Open a sample or clip to see it through the eye.");
    return;
  }
  eye.camera = wanted.image;
  if (eye.cache.has(wanted.key)) {
    eye.data = eye.cache.get(wanted.key);
    eye.key = wanted.key;
    drawAll();
    status(wanted.label);
    return;
  }
  // While a sample is on its way, only the newest request waits; playback skips frames.
  if (eye.loading) {
    eye.pending = sel;
    drawCamera();
    return;
  }
  eye.loading = wanted.key;
  status(`Sampling ${wanted.label}…`);
  try {
    await ready();
    const data = await api(wanted.path);
    eye.cache.set(wanted.key, data);
    if (eye.cache.size > 600) eye.cache.delete(eye.cache.keys().next().value);
    eye.data = data;
    eye.key = wanted.key;
    drawAll();
    status(wanted.label + (data.low_bit_depth ? " · few input levels" : ""));
  } catch (error) {
    status(error.message, true);
  } finally {
    eye.loading = null;
    const next = eye.pending;
    eye.pending = null;
    if (next) load(next);
  }
}

function status(text, bad) {
  const node = $("eye-status");
  node.textContent = text;
  node.classList.toggle("bad", Boolean(bad));
}

function signal() {
  return document.querySelector('input[name="eye-channel"]:checked').value;
}

/** Per-channel display peak: the frame's own peak (auto) or a fixed one. */
function peaks() {
  if ($("eye-gain").value === "fixed") return [FIXED_PEAK, FIXED_PEAK, FIXED_PEAK];
  return eye.data.maxima.map((m) => Math.max(m, 1e-6));
}

/** RGB colour of ommatidium i under the chosen signal, or null when it sees nothing. */
function colourer() {
  const data = eye.data;
  const which = signal();
  const peak = peaks();
  const [r16, r7, r8] = data.activations;
  const fixed = $("eye-gain").value === "fixed";
  const shape = (v) => (fixed ? Math.sqrt(Math.max(0, v)) : Math.max(0, v));
  if (which === "fly") {
    // False colour: red from R8, green from R1_6, blue from R7.
    return (i) => (data.valid[i] ? [255 * shape(r8[i] / peak[2]), 255 * shape(r16[i] / peak[0]), 255 * shape(r7[i] / peak[1])] : null);
  }
  if (which === "honey") {
    const coverage = data.coverage || [];
    return (i) => {
      if (!data.valid[i]) return null;
      const grey = 70 * shape(r16[i] / peak[0]);
      const c = coverage[i] || 0;
      if (c <= 0) return [grey, grey, grey];
      const h = ramp(CHANNEL_STOPS.honey, 0.35 + 0.65 * Math.min(1, c * 2));
      return h;
    };
  }
  const channel = ["R1_6", "R7", "R8"].indexOf(which);
  const values = data.activations[channel];
  return (i) => (data.valid[i] ? ramp(CHANNEL_STOPS[which], shape(values[i] / peak[channel])) : null);
}

function drawAll() {
  if (!eye.active || !eye.data) return;
  drawCamera();
  drawDecoded();
  drawHex();
  drawGlobe();
  drawMeans();
  drawSpectra();
  describeHover();
}

function contain(canvas, aspect) {
  const box = { w: canvas.width, h: canvas.height };
  let w = box.w;
  let h = w / aspect;
  if (h > box.h) {
    h = box.h;
    w = h * aspect;
  }
  return { x: (box.w - w) / 2, y: (box.h - h) / 2, w, h };
}

/** Panorama pixel position of a viewing direction, as a fraction of the image. */
function panoramaXY(i) {
  const az = eye.layout.azimuth[i];
  const el = eye.layout.elevation[i];
  return [az / 310 + 0.5, 0.5 - (el - 10) / 160];
}

function marker(context, x, y, r) {
  context.strokeStyle = "#000";
  context.lineWidth = 3 * devicePixelRatio;
  context.beginPath();
  context.arc(x, y, r, 0, 2 * Math.PI);
  context.stroke();
  context.strokeStyle = cssVar("--mask");
  context.lineWidth = 1.5 * devicePixelRatio;
  context.stroke();
}

function drawCamera() {
  const canvas = $("eye-camera");
  const context = fitCanvas(canvas);
  const box = contain(canvas, 310 / 160);
  context.fillStyle = cssVar("--stage");
  context.fillRect(box.x, box.y, box.w, box.h);
  if (eye.camera) {
    context.imageSmoothingEnabled = true;
    context.drawImage(eye.camera, box.x, box.y, box.w, box.h);
  }
  if (eye.hover !== null && eye.layout) {
    const [fx, fy] = panoramaXY(eye.hover);
    marker(context, box.x + fx * box.w, box.y + fy * box.h, 2.6 / 310 * box.w + 3);
  }
  eye.cameraBox = box;
}

function drawDecoded() {
  const canvas = $("eye-decoded");
  const context = fitCanvas(canvas);
  const [w, h] = DECODE_SIZE;
  const colour = colourer();
  const colours = new Array(eye.layout.count);
  for (let i = 0; i < colours.length; i++) colours[i] = colour(i);
  const image = new ImageData(w, h);
  const pixels = image.data;
  const map = eye.decodeMap;
  for (let p = 0; p < map.length; p++) {
    const c = map[p] === 0xffff ? null : colours[map[p]];
    const o = p * 4;
    if (c) {
      pixels[o] = c[0];
      pixels[o + 1] = c[1];
      pixels[o + 2] = c[2];
    } else {
      pixels[o] = pixels[o + 1] = pixels[o + 2] = 40;
    }
    pixels[o + 3] = 255;
  }
  const off = new OffscreenCanvas(w, h);
  off.getContext("2d").putImageData(image, 0, 0);
  const box = contain(canvas, w / h);
  context.imageSmoothingEnabled = false;
  context.drawImage(off, box.x, box.y, box.w, box.h);
  if (eye.hover !== null) {
    const [fx, fy] = panoramaXY(eye.hover);
    marker(context, box.x + fx * box.w, box.y + fy * box.h, 2.6 / 310 * box.w + 3);
  }
  eye.decodedBox = box;
}

function hexagon(context, x, y, r) {
  context.beginPath();
  for (let k = 0; k < 6; k++) {
    const a = (Math.PI / 3) * k + Math.PI / 6;
    const px = x + r * Math.cos(a);
    const py = y + r * Math.sin(a);
    if (k === 0) context.moveTo(px, py);
    else context.lineTo(px, py);
  }
  context.closePath();
}

function drawHex() {
  const canvas = $("eye-hex");
  const context = fitCanvas(canvas);
  const { azimuth, elevation, eye: sides } = eye.layout;
  const colour = colourer();
  const ratio = devicePixelRatio;
  const gap = 24 * ratio;
  const label = 18 * ratio;
  const panelWidth = (canvas.width - gap) / 2;
  eye.hexPoints = [];
  ["L", "R"].forEach((side, panel) => {
    const members = [];
    for (let i = 0; i < sides.length; i++) if (sides[i] === side) members.push(i);
    const az = members.map((i) => azimuth[i]);
    const el = members.map((i) => elevation[i]);
    const [a0, a1] = [Math.min(...az) - 4, Math.max(...az) + 4];
    const [e0, e1] = [Math.min(...el) - 4, Math.max(...el) + 4];
    const scale = Math.min(panelWidth / (a1 - a0), (canvas.height - label) / (e1 - e0));
    const left = panel * (panelWidth + gap) + (panelWidth - (a1 - a0) * scale) / 2;
    const top = label + (canvas.height - label - (e1 - e0) * scale) / 2;
    const r = 2.7 * scale;
    context.fillStyle = cssVar("--muted");
    context.font = `${12 * ratio}px ${cssVar("--sans")}`;
    context.fillText(side === "L" ? "Left eye" : "Right eye", panel * (panelWidth + gap) + 2, 13 * ratio);
    for (const i of members) {
      const x = left + (azimuth[i] - a0) * scale;
      const y = top + (e1 - elevation[i]) * scale;
      const c = colour(i);
      hexagon(context, x, y, r);
      if (c) {
        context.fillStyle = `rgb(${c[0]} ${c[1]} ${c[2]})`;
        context.fill();
      } else {
        context.strokeStyle = cssVar("--faint");
        context.lineWidth = ratio;
        context.stroke();
      }
      eye.hexPoints.push([x, y, i]);
      if (i === eye.hover) marker(context, x, y, r + 2 * ratio);
    }
  });
}

function rotate(v) {
  const { yaw, pitch } = eye.globe;
  // Forward x, right y, up z. Turn about up by yaw, then tilt about right by pitch.
  const x1 = v[0] * Math.cos(yaw) - v[1] * Math.sin(yaw);
  const y1 = v[0] * Math.sin(yaw) + v[1] * Math.cos(yaw);
  const x2 = x1 * Math.cos(pitch) + v[2] * Math.sin(pitch);
  const z2 = -x1 * Math.sin(pitch) + v[2] * Math.cos(pitch);
  // Screen: right is y, up is z, towards the viewer is -x.
  return [y1, z2, -x2];
}

function drawGlobe() {
  const canvas = $("eye-globe");
  const context = fitCanvas(canvas);
  const ratio = devicePixelRatio;
  const radius = Math.min(canvas.width, canvas.height) / 2 - 8 * ratio;
  const cx = canvas.width / 2;
  const cy = canvas.height / 2;
  context.fillStyle = cssVar("--bg");
  context.beginPath();
  context.arc(cx, cy, radius, 0, 2 * Math.PI);
  context.fill();
  context.strokeStyle = cssVar("--rule");
  context.lineWidth = ratio;
  context.stroke();
  // Equator and the forward meridian, front halves only.
  const line = (points) => {
    context.beginPath();
    let pen = false;
    for (const p of points) {
      const [x, y, z] = rotate(p);
      if (z < 0) {
        pen = false;
        continue;
      }
      if (pen) context.lineTo(cx + x * radius, cy - y * radius);
      else context.moveTo(cx + x * radius, cy - y * radius);
      pen = true;
    }
    context.stroke();
  };
  const circle = (f) => Array.from({ length: 121 }, (_, k) => f((k / 120) * 2 * Math.PI));
  line(circle((t) => [Math.cos(t), Math.sin(t), 0]));
  line(circle((t) => [Math.cos(t), 0, Math.sin(t)]));
  const colour = colourer();
  const projected = eye.unit.map((v, i) => [...rotate(v), i]).filter((p) => p[2] > 0).sort((a, b) => a[2] - b[2]);
  const r = Math.max(1.5 * ratio, radius * 0.042);
  eye.globePoints = [];
  for (const [x, y, z, i] of projected) {
    const px = cx + x * radius;
    const py = cy - y * radius;
    const c = colour(i);
    context.globalAlpha = 0.45 + 0.55 * z;
    context.beginPath();
    context.arc(px, py, r * (0.55 + 0.45 * z), 0, 2 * Math.PI);
    if (c) {
      context.fillStyle = `rgb(${c[0]} ${c[1]} ${c[2]})`;
      context.fill();
    } else {
      context.strokeStyle = cssVar("--faint");
      context.stroke();
    }
    eye.globePoints.push([px, py, i]);
    if (i === eye.hover) {
      context.globalAlpha = 1;
      marker(context, px, py, r + 2 * ratio);
    }
  }
  context.globalAlpha = 1;
  // The direction the fly faces.
  const [fx, fy, fz] = rotate([1, 0, 0]);
  if (fz > 0) {
    context.fillStyle = cssVar("--honey");
    context.beginPath();
    context.arc(cx + fx * radius, cy - fy * radius, 3 * ratio, 0, 2 * Math.PI);
    context.fill();
  }
}

function drawMeans() {
  const data = eye.data;
  const rows = data.channels.map((name, c) => {
    const row = element("div", null, "bar-row");
    const bar = element("span", null, "bar");
    const end = ramp(CHANNEL_STOPS[name], 0.75);
    bar.style.setProperty("--fill", `rgb(${end[0]} ${end[1]} ${end[2]})`);
    bar.style.setProperty("--value", String(Math.min(1, data.means[c] / FIXED_PEAK)));
    row.append(element("span", name, "bar-label"), bar, element("span", number(data.means[c], 3), "num"));
    return row;
  });
  const coverage = data.coverage || [];
  const seeing = coverage.filter((c) => c > 0).length;
  const honey = element("p", seeing ? `${seeing} ommatidia see honey (peak coverage ${number(Math.max(...coverage), 2)}).` : "No ommatidium sees honey.", "hint");
  $("eye-means").replaceChildren(...rows, honey);
}

function drawSpectra() {
  const canvas = $("eye-spectra");
  const context = fitCanvas(canvas);
  const spectra = eye.layout.spectra;
  const mode = document.querySelector('input[name="eye-pigments"]:checked').value;
  const ratio = devicePixelRatio;
  const pad = { l: 6 * ratio, r: 6 * ratio, t: 6 * ratio, b: 18 * ratio };
  const w = canvas.width - pad.l - pad.r;
  const h = canvas.height - pad.t - pad.b;
  const wl = spectra.wavelengths;
  const x = (nm) => pad.l + ((nm - wl[0]) / (wl[wl.length - 1] - wl[0])) * w;
  const y = (v) => pad.t + (1 - v) * h;
  for (const [band, values] of Object.entries(spectra.bands)) {
    context.fillStyle = BAND_COLOURS[band];
    context.beginPath();
    context.moveTo(x(wl[0]), y(0));
    values.forEach((v, k) => context.lineTo(x(wl[k]), y(v)));
    context.lineTo(x(wl[wl.length - 1]), y(0));
    context.fill();
  }
  context.lineWidth = 1.6 * ratio;
  for (const [opsin, values] of Object.entries(spectra.modes[mode])) {
    context.strokeStyle = RHODOPSIN_COLOURS[opsin];
    context.beginPath();
    values.forEach((v, k) => (k ? context.lineTo(x(wl[k]), y(v)) : context.moveTo(x(wl[k]), y(v))));
    context.stroke();
  }
  context.fillStyle = cssVar("--muted");
  context.font = `${10.5 * ratio}px ${cssVar("--mono")}`;
  for (const nm of [300, 400, 500, 600, 700]) {
    context.fillText(String(nm), x(nm) - (nm === 700 ? 22 * ratio : nm === 300 ? 0 : 10 * ratio), canvas.height - 4 * ratio);
  }
  $("eye-spectra-legend").replaceChildren(
    ...Object.keys(RHODOPSIN_COLOURS).map((opsin) => {
      const key = element("span", `${opsin}`, "key");
      key.style.setProperty("--key", RHODOPSIN_COLOURS[opsin]);
      return key;
    }),
  );
  const used = eye.data && eye.data.spectral && eye.data.spectral.pigment_map ? eye.data.spectral.pigment_map.kind : null;
  $("eye-spectra-note").textContent = `Shaded: the camera's assumed R, G and B bands. Frames are sampled with the ${used === "affine" ? "remapped" : used === "identity" || used === "none" ? "physical" : used || "default"} pigments.`;
}

function nearest(points, px, py, limit) {
  let best = null;
  let bestDistance = limit * limit;
  for (const [x, y, i] of points) {
    const d = (x - px) ** 2 + (y - py) ** 2;
    if (d < bestDistance) {
      bestDistance = d;
      best = i;
    }
  }
  return best;
}

function setHover(index) {
  if (index === eye.hover) return;
  eye.hover = index;
  drawAll();
}

function describeHover() {
  const i = eye.hover;
  $("eye-hover-hint").hidden = i !== null;
  if (i === null || !eye.data) {
    facts($("eye-ommatidium"), []);
    return;
  }
  const layout = eye.layout;
  const subtype = layout.subtype[i];
  const opsins = layout.opsins[subtype] || {};
  const [r16, r7, r8] = eye.data.activations;
  facts($("eye-ommatidium"), [
    ["Index", `${i}, ${layout.eye[i] === "L" ? "left" : "right"} eye`],
    ["Looks at", `${number(layout.azimuth[i], 1)}° right, ${number(layout.elevation[i], 1)}° up`],
    ["Subtype", subtype.replaceAll("_", " ")],
    ["R1_6", `${number(r16[i], 4)} (${opsins.R1_6 || "—"})`],
    ["R7", `${number(r7[i], 4)} (${opsins.R7 || "—"})`],
    ["R8", `${number(r8[i], 4)} (${opsins.R8 || "—"})`],
    ["Honey coverage", eye.data.coverage ? number(eye.data.coverage[i], 3) : "—"],
    ["Sees the image", eye.data.valid[i] ? "yes" : "no"],
  ]);
}

function pointer(canvas, event) {
  const box = canvas.getBoundingClientRect();
  return [(event.clientX - box.left) * devicePixelRatio, (event.clientY - box.top) * devicePixelRatio];
}

function panoramaHover(box, event, canvas) {
  if (!box || !eye.decodeMap) return null;
  const [px, py] = pointer(canvas, event);
  const fx = (px - box.x) / box.w;
  const fy = (py - box.y) / box.h;
  if (fx < 0 || fx >= 1 || fy < 0 || fy >= 1) return null;
  const index = eye.decodeMap[Math.floor(fy * DECODE_SIZE[1]) * DECODE_SIZE[0] + Math.floor(fx * DECODE_SIZE[0])];
  return index === 0xffff ? null : index;
}

$("eye-decoded").addEventListener("pointermove", (event) => setHover(panoramaHover(eye.decodedBox, event, $("eye-decoded"))));
$("eye-camera").addEventListener("pointermove", (event) => setHover(panoramaHover(eye.cameraBox, event, $("eye-camera"))));
$("eye-hex").addEventListener("pointermove", (event) => {
  const [px, py] = pointer($("eye-hex"), event);
  setHover(nearest(eye.hexPoints, px, py, 14 * devicePixelRatio));
});
{
  const globe = $("eye-globe");
  let drag = null;
  globe.addEventListener("pointerdown", (event) => {
    drag = { x: event.clientX, y: event.clientY, id: event.pointerId };
    globe.setPointerCapture(event.pointerId);
  });
  globe.addEventListener("pointermove", (event) => {
    if (drag && drag.id === event.pointerId) {
      eye.globe.yaw += (event.clientX - drag.x) * 0.01;
      eye.globe.pitch = Math.max(-1.5, Math.min(1.5, eye.globe.pitch + (event.clientY - drag.y) * 0.01));
      drag.x = event.clientX;
      drag.y = event.clientY;
      if (eye.data) drawGlobe();
      return;
    }
    const [px, py] = pointer(globe, event);
    setHover(nearest(eye.globePoints, px, py, 12 * devicePixelRatio));
  });
  const release = () => (drag = null);
  globe.addEventListener("pointerup", release);
  globe.addEventListener("pointercancel", release);
}
for (const id of ["eye-decoded", "eye-camera", "eye-hex", "eye-globe"]) $(id).addEventListener("pointerleave", () => setHover(null));
for (const input of document.querySelectorAll('input[name="eye-channel"], #eye-gain')) input.addEventListener("input", drawAll);
for (const input of document.querySelectorAll('input[name="eye-pigments"]')) input.addEventListener("input", () => eye.data && drawSpectra());
new ResizeObserver(() => eye.active && drawAll()).observe($("eye-panel"));

workspaces.register("eye", {
  show(sel) {
    eye.active = true;
    load(sel);
  },
  hide() {
    eye.active = false;
  },
  sample: load,
  frame: load,
});

}
