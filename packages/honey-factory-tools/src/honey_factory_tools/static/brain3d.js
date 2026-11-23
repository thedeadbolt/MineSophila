"use strict";

// The 3D brain (ADR 0029): every model neuron at its soma in MaleCNS space, a sampled
// cloud of the rest of the brain for its outline, and the strongest connections, drawn
// with WebGL and additive blending. Drag to turn, scroll to zoom, point at a neuron to
// name it. During clip playback each neuron brightens with its cell type's activity.
// Uses $, element(), number(), humane() from app.js and cssVar() from workspaces.js.
{

const COMPONENT_COLOURS = {
  photoreceptors: [0.85, 0.87, 0.9], lamina_relays: [0.62, 0.66, 0.72], medulla_relays: [0.45, 0.62, 0.78],
  visual_projection: [0.66, 0.5, 1.0], anterior_visual_inputs: [0.24, 0.55, 0.99], visual_kenyon: [0.9, 0.37, 0.69],
  tubercle_bulb: [0.09, 0.78, 0.94], ring_neurons: [0.36, 0.79, 0.54], compass: [0.94, 0.71, 0.16], steering: [0.91, 0.45, 0.23],
};
const EXCITATORY = [0.32, 0.86, 0.85];
const INHIBITORY = [0.42, 0.55, 1.0];
const view3d = {
  gl: null, ready: false, graph: null, active: false, hidden: new Set(),
  yaw: 0.0, pitch: 0.0, distance: 1, auto: true, last: 0,
  center: [0, 0, 0], radius: 1, positions: null, colours: null, base: null, typeOf: null,
  hover: null, frameRequested: false, activityKey: null,
};

function shader(gl, type, source) {
  const s = gl.createShader(type);
  gl.shaderSource(s, source);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}

function program(gl, fragment) {
  const p = gl.createProgram();
  gl.attachShader(p, shader(gl, gl.VERTEX_SHADER, `
    attribute vec3 position; attribute vec4 colour; uniform mat4 matrix; uniform float size;
    varying vec4 v;
    void main() { gl_Position = matrix * vec4(position, 1.0); gl_PointSize = size; v = colour; }`));
  gl.attachShader(p, shader(gl, gl.FRAGMENT_SHADER, fragment));
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  return p;
}

const POINT_FRAGMENT = `precision mediump float; varying vec4 v;
  void main() { float d = length(gl_PointCoord - 0.5); if (d > 0.5) discard; gl_FragColor = vec4(v.rgb, v.a * (1.0 - 1.6 * d)); }`;
const LINE_FRAGMENT = `precision mediump float; varying vec4 v; void main() { gl_FragColor = v; }`;

function buffer(gl, data) {
  const b = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, b);
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
  return b;
}

function build(graph) {
  const canvas = $("brain3d-canvas");
  const gl = canvas.getContext("webgl", { antialias: true, premultipliedAlpha: false });
  if (!gl) {
    $("brain3d-status").textContent = "This browser has no WebGL, so the 3D brain cannot be drawn.";
    return false;
  }
  view3d.gl = gl;
  view3d.points = program(gl, POINT_FRAGMENT);
  view3d.lines = program(gl, LINE_FRAGMENT);
  const atlas = graph.atlas;
  const types = graph.cell_types;
  const inhibitory = new Set(graph.inhibitory_transmitters || []);
  // Model neurons with a position.
  const keep = [];
  for (let i = 0; i < atlas.body_id.length; i++) if (atlas.position[3 * i] >= 0) keep.push(i);
  const positions = new Float32Array(keep.length * 3);
  const base = new Float32Array(keep.length * 4);
  const typeOf = new Int32Array(keep.length);
  const slot = new Int32Array(atlas.body_id.length).fill(-1);
  let sx = 0, sy = 0, sz = 0;
  keep.forEach((i, k) => {
    slot[i] = k;
    positions.set([atlas.position[3 * i], atlas.position[3 * i + 1], atlas.position[3 * i + 2]], 3 * k);
    sx += atlas.position[3 * i]; sy += atlas.position[3 * i + 1]; sz += atlas.position[3 * i + 2];
    const type = types[atlas.cell_type[i]];
    typeOf[k] = atlas.cell_type[i];
    base.set([...(COMPONENT_COLOURS[type.component] || [0.6, 0.6, 0.6]), 0.75], 4 * k);
  });
  view3d.center = [sx / keep.length, sy / keep.length, sz / keep.length];
  let radius = 1;
  for (let k = 0; k < keep.length; k++) {
    const d = Math.hypot(positions[3 * k] - view3d.center[0], positions[3 * k + 1] - view3d.center[1], positions[3 * k + 2] - view3d.center[2]);
    if (d > radius) radius = d;
  }
  view3d.radius = radius;
  view3d.distance = radius * 2.3;
  view3d.positions = positions;
  view3d.base = base;
  view3d.colours = base.slice();
  view3d.typeOf = typeOf;
  view3d.slot = slot;
  view3d.bodyIndex = new Map(atlas.body_id.map((b, i) => [b, i]));
  view3d.neuronBuffer = buffer(gl, positions);
  view3d.neuronColours = buffer(gl, view3d.colours);
  // Background brain.
  const background = new Float32Array(atlas.background);
  const backgroundColours = new Float32Array((background.length / 3) * 4);
  for (let k = 0; k < background.length / 3; k++) backgroundColours.set([0.45, 0.56, 0.64, 0.3], 4 * k);
  view3d.backgroundBuffer = buffer(gl, background);
  view3d.backgroundColours = buffer(gl, backgroundColours);
  view3d.backgroundCount = background.length / 3;
  // Edges, strongest first.
  const edges = atlas.edges.filter(([a, b]) => slot[a] >= 0 && slot[b] >= 0);
  const peak = Math.max(1, ...edges.map((e) => e[2]));
  const linePositions = new Float32Array(edges.length * 6);
  const lineColours = new Float32Array(edges.length * 8);
  edges.forEach(([a, b, w], k) => {
    const pa = slot[a], pb = slot[b];
    linePositions.set([positions[3 * pa], positions[3 * pa + 1], positions[3 * pa + 2], positions[3 * pb], positions[3 * pb + 1], positions[3 * pb + 2]], 6 * k);
    const colour = inhibitory.has(types[atlas.cell_type[a]].transmitter) ? INHIBITORY : EXCITATORY;
    const alpha = 0.05 + 0.4 * (Math.log1p(w) / Math.log1p(peak)) ** 2;
    lineColours.set([...colour, alpha, ...colour, alpha * 0.5], 8 * k);
  });
  view3d.lineEdges = edges;
  view3d.lineBuffer = buffer(gl, linePositions);
  view3d.lineColourBase = lineColours;
  view3d.lineColours = buffer(gl, lineColours.slice());
  view3d.lineCount = edges.length * 2;
  view3d.neuronCount = keep.length;
  $("brain3d-neurons").textContent = `${number(keep.length)} neurons`;
  $("brain3d-connections").textContent = `${number(edges.length)} connections`;
  $("brain3d-legend").replaceChildren(...graph.components.map((c) => {
    const key = element("button", `${humane(c.name)} ${number(c.neurons)}`, "key toggle-key");
    key.type = "button";
    const [r, g, b] = COMPONENT_COLOURS[c.name] || [0.6, 0.6, 0.6];
    key.style.setProperty("--key", `rgb(${r * 255} ${g * 255} ${b * 255})`);
    key.setAttribute("aria-pressed", "true");
    key.title = "Show or hide this component";
    key.addEventListener("click", () => {
      if (view3d.hidden.has(c.name)) view3d.hidden.delete(c.name);
      else view3d.hidden.add(c.name);
      key.setAttribute("aria-pressed", String(!view3d.hidden.has(c.name)));
      view3d.activityKey = null;
      paint();
    });
    return key;
  }));
  $("brain3d-note").textContent = `Neurons sit at their soma in MaleCNS space (${number(atlas.placed_from_partners)} without a recorded soma, mostly photoreceptors and lamina cells, are placed among their partners). The faint cloud is ${number(view3d.backgroundCount)} other brain neurons. Lines are the strongest connections: teal excitatory, blue inhibitory.`;
  view3d.ready = true;
  return true;
}

function glMatrix() {
  const canvas = $("brain3d-canvas");
  const aspect = canvas.width / Math.max(1, canvas.height);
  const f = 1 / Math.tan((40 * Math.PI) / 360);
  const near = view3d.distance * 0.05;
  const far = view3d.distance * 4;
  const cy = Math.cos(view3d.yaw), sy = Math.sin(view3d.yaw);
  const cp = Math.cos(view3d.pitch), sp = Math.sin(view3d.pitch);
  // Column-major projection * view, where view centres the model, flips y (image rows grow
  // downwards), turns by yaw then pitch and steps back by the camera distance.
  const rotate = (x, y, z) => {
    const px = x, py = -y, pz = z;
    const rx = cy * px + sy * pz;
    const rz = -sy * px + cy * pz;
    const ry = cp * py - sp * rz;
    const rz2 = sp * py + cp * rz;
    return [rx, ry, rz2];
  };
  const ex = rotate(1, 0, 0), ey = rotate(0, 1, 0), ez = rotate(0, 0, 1);
  const [cx0, cy0, cz0] = view3d.center;
  const t = rotate(-cx0, -cy0, -cz0);
  const view = [
    ex[0], ex[1], ex[2], 0,
    ey[0], ey[1], ey[2], 0,
    ez[0], ez[1], ez[2], 0,
    t[0], t[1], t[2] - view3d.distance, 1,
  ];
  const projection = [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0, (2 * far * near) / (near - far), 0];
  const out = new Float32Array(16);
  for (let c = 0; c < 4; c++) for (let rI = 0; rI < 4; rI++) {
    let sum = 0;
    for (let k = 0; k < 4; k++) sum += projection[k * 4 + rI] * view[c * 4 + k];
    out[c * 4 + rI] = sum;
  }
  return out;
}

function draw(now) {
  view3d.frameRequested = false;
  if (!view3d.active || !view3d.ready) return;
  const gl = view3d.gl;
  const canvas = $("brain3d-canvas");
  const ratio = devicePixelRatio;
  const w = Math.max(1, Math.round(canvas.clientWidth * ratio));
  const h = Math.max(1, Math.round(canvas.clientHeight * ratio));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  if (view3d.auto && !view3d.dragging) view3d.yaw += Math.min(50, now - (view3d.last || now)) * 0.00012;
  view3d.last = now;
  gl.viewport(0, 0, w, h);
  gl.clearColor(0, 0, 0, 0);
  gl.clear(gl.COLOR_BUFFER_BIT);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE);
  gl.disable(gl.DEPTH_TEST);
  const m = glMatrix();
  const layer = (prog, positions, colours, mode, count, size) => {
    gl.useProgram(prog);
    gl.uniformMatrix4fv(gl.getUniformLocation(prog, "matrix"), false, m);
    gl.uniform1f(gl.getUniformLocation(prog, "size"), size * ratio);
    const p = gl.getAttribLocation(prog, "position");
    gl.bindBuffer(gl.ARRAY_BUFFER, positions);
    gl.enableVertexAttribArray(p);
    gl.vertexAttribPointer(p, 3, gl.FLOAT, false, 0, 0);
    const c = gl.getAttribLocation(prog, "colour");
    gl.bindBuffer(gl.ARRAY_BUFFER, colours);
    gl.enableVertexAttribArray(c);
    gl.vertexAttribPointer(c, 4, gl.FLOAT, false, 0, 0);
    gl.drawArrays(mode, 0, count);
  };
  if ($("brain3d-background").checked) layer(view3d.points, view3d.backgroundBuffer, view3d.backgroundColours, gl.POINTS, view3d.backgroundCount, 2.2);
  if ($("brain3d-edges").checked) layer(view3d.lines, view3d.lineBuffer, view3d.lineColours, gl.LINES, view3d.lineCount, 1);
  layer(view3d.points, view3d.neuronBuffer, view3d.neuronColours, gl.POINTS, view3d.neuronCount, 3.2);
  if (view3d.auto) request();
}

function request() {
  if (view3d.frameRequested) return;
  view3d.frameRequested = true;
  requestAnimationFrame(draw);
}

/** Colours from the components shown, the hovered cell type and the trace's activity. */
function paint() {
  if (!view3d.ready) return;
  const gl = view3d.gl;
  const types = view3d.graph.cell_types;
  const activity = window.brainActivity ? window.brainActivity() : null;
  const hoverType = view3d.hover === null ? null : view3d.typeOf[view3d.hover];
  const colours = view3d.colours;
  for (let k = 0; k < view3d.neuronCount; k++) {
    const type = types[view3d.typeOf[k]];
    let alpha = view3d.hidden.has(type.component) ? 0 : view3d.base[4 * k + 3];
    let gain = 1;
    if (activity && activity.values[type.name] !== undefined) gain = 0.35 + 2.2 * (activity.values[type.name] / activity.peak);
    else if (activity) gain = 0.35;
    if (hoverType !== null) {
      if (view3d.typeOf[k] === hoverType) { gain = Math.max(gain, 2.2); alpha = Math.max(alpha, 1); }
      else alpha *= 0.35;
    }
    colours[4 * k] = view3d.base[4 * k] * gain;
    colours[4 * k + 1] = view3d.base[4 * k + 1] * gain;
    colours[4 * k + 2] = view3d.base[4 * k + 2] * gain;
    colours[4 * k + 3] = alpha;
  }
  gl.bindBuffer(gl.ARRAY_BUFFER, view3d.neuronColours);
  gl.bufferSubData(gl.ARRAY_BUFFER, 0, colours);
  // Edges of hidden components disappear too.
  if (view3d.hidden.size) {
    const lines = view3d.lineColourBase.slice();
    const atlas = view3d.graph.atlas;
    view3d.lineEdges.forEach(([a, b], k) => {
      const ca = types[atlas.cell_type[a]].component;
      const cb = types[atlas.cell_type[b]].component;
      if (view3d.hidden.has(ca) || view3d.hidden.has(cb)) { lines[8 * k + 3] = 0; lines[8 * k + 7] = 0; }
    });
    gl.bindBuffer(gl.ARRAY_BUFFER, view3d.lineColours);
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, lines);
  } else {
    gl.bindBuffer(gl.ARRAY_BUFFER, view3d.lineColours);
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, view3d.lineColourBase);
  }
  request();
}

function pick(event) {
  const canvas = $("brain3d-canvas");
  const box = canvas.getBoundingClientRect();
  const mx = ((event.clientX - box.left) / box.width) * 2 - 1;
  const my = 1 - ((event.clientY - box.top) / box.height) * 2;
  const m = glMatrix();
  const p = view3d.positions;
  const types = view3d.graph.cell_types;
  let best = null;
  let bestDistance = (10 / box.width) ** 2 * 4;
  for (let k = 0; k < view3d.neuronCount; k++) {
    if (view3d.hidden.has(types[view3d.typeOf[k]].component)) continue;
    const x = p[3 * k], y = p[3 * k + 1], z = p[3 * k + 2];
    const cw = m[3] * x + m[7] * y + m[11] * z + m[15];
    if (cw <= 0) continue;
    const cx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / cw;
    const cy = (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw;
    const d = (cx - mx) ** 2 + ((cy - my) * (box.height / box.width)) ** 2;
    if (d < bestDistance) { bestDistance = d; best = k; }
  }
  return best;
}

function describe(k, event) {
  const tip = $("brain3d-tip");
  if (k === null) { tip.hidden = true; return; }
  const types = view3d.graph.cell_types;
  const type = types[view3d.typeOf[k]];
  const box = $("brain3d-canvas").getBoundingClientRect();
  tip.hidden = false;
  tip.replaceChildren(element("strong", type.name), element("span", `${humane(type.component)} · ${number(type.count)} neurons · ${type.transmitter}`));
  tip.style.left = `${Math.min(box.width - 220, event.clientX - box.left + 14)}px`;
  tip.style.top = `${event.clientY - box.top + 12}px`;
  if (window.brainDescribeType) window.brainDescribeType(view3d.typeOf[k]);
}

{
  const canvas = $("brain3d-canvas");
  let drag = null;
  canvas.addEventListener("pointerdown", (event) => {
    drag = { x: event.clientX, y: event.clientY, id: event.pointerId };
    view3d.dragging = true;
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener("pointermove", (event) => {
    if (drag && drag.id === event.pointerId) {
      view3d.yaw += (event.clientX - drag.x) * 0.008;
      view3d.pitch = Math.max(-1.5, Math.min(1.5, view3d.pitch + (event.clientY - drag.y) * 0.008));
      drag.x = event.clientX;
      drag.y = event.clientY;
      request();
      return;
    }
    if (!view3d.ready) return;
    const k = pick(event);
    describe(k, event);
    const type = k === null ? null : view3d.typeOf[k];
    const before = view3d.hover === null ? null : view3d.typeOf[view3d.hover];
    view3d.hover = k;
    if (type !== before) paint();
  });
  const release = () => { drag = null; view3d.dragging = false; };
  canvas.addEventListener("pointerup", release);
  canvas.addEventListener("pointercancel", release);
  canvas.addEventListener("pointerleave", () => {
    $("brain3d-tip").hidden = true;
    if (view3d.hover !== null) { view3d.hover = null; paint(); }
  });
  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    view3d.distance = Math.max(view3d.radius * 0.4, Math.min(view3d.radius * 6, view3d.distance * Math.exp(event.deltaY * 0.0012)));
    request();
  }, { passive: false });
  canvas.addEventListener("dblclick", () => {
    view3d.yaw = 0;
    view3d.pitch = 0;
    view3d.distance = view3d.radius * 2.3;
    request();
  });
  for (const id of ["brain3d-background", "brain3d-edges"]) $(id).addEventListener("input", request);
  $("brain3d-rotate").addEventListener("input", (event) => { view3d.auto = event.target.checked; request(); });
  for (const [id, yaw, pitch] of [["brain3d-front", 0, 0], ["brain3d-top", 0, -1.45], ["brain3d-side", Math.PI / 2, 0]]) {
    $(id).addEventListener("click", () => { view3d.yaw = yaw; view3d.pitch = pitch; request(); });
  }
  new ResizeObserver(request).observe(canvas);
}

window.brain3d = {
  show(graph) {
    view3d.active = true;
    if (!view3d.ready && graph && graph.atlas) {
      view3d.graph = graph;
      try {
        if (!build(graph)) return;
      } catch (error) {
        $("brain3d-status").textContent = `The 3D brain failed to start: ${error.message}`;
        return;
      }
    } else if (graph && !graph.atlas) {
      $("brain3d-status").textContent = "This connectome graph has no atlas. Rebuild it with honey-factory-tools connectome-graph.";
      return;
    }
    paint();
    request();
  },
  hide() {
    view3d.active = false;
  },
  activity() {
    const key = window.brainActivity ? JSON.stringify(window.brainActivity()?.step ?? null) : null;
    if (key !== view3d.activityKey) {
      view3d.activityKey = key;
      paint();
    }
  },
};

}
