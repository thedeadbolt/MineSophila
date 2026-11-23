# 0029: Viewer workspaces for the eye and the brain

- **Status:** Proposed
- **Date:** 2026-11-23

## Context

The inspector ([ADR 0015](0015-inspector-and-replay.md)) shows what honey-factory
captured. The project also needs to show what the fly makes of it: the photoreceptor
signal fly-vision computes from a frame, and what a fly-brain model does with that signal
over a clip. Reviewing these one tool at a time, with each tool keeping its own idea of
the current frame, makes it hard to tie a brain response to the image that caused it.

fly-vision is a workspace package that the inspector can import. fly-brain lives on its
own branch, changes quickly and uses JAX, so the inspector should not import it.

## Decision

- **One page, three workspaces.** The inspector page gains a workspace rail: Scene (the
  existing image and clip views), Eye and Brain. All three follow the same selection: one
  sample, or one clip and frame on a shared timeline.
- **Eye runs fly-vision live.** honey-factory-tools depends on fly-vision and numpy. The
  server decodes the selected frame at half resolution, then samples it through the
  default lattice and spectral model, as `fly-vision convert` does. The page draws
  per-receptor hex maps, an image decoded from the signal (each pixel takes the nearest
  ommatidium's value), a globe of viewing directions, and the rhodopsin curves for both
  pigment axes. Half resolution keeps one sample under 50 ms, and the eye resolves about
  5°, far coarser than the 0.25° half-resolution pixels. Only `PANORAMA_310` captures
  are supported.
- **Brain reads files.** `--runs DIR` lists the fly-brain `*.report.json` files, keyed
  on their `format`. Only the stable fields are relied on: the split metrics,
  `git_revision` and the checkpoint digest. `--traces DIR` reads per-clip
  `fly-brain-clip-trace-v1` files, written by the planned `fly-brain trace-clip`
  command, to play back activity, the compass bump and the readout against the clip. The
  inspector never imports fly-brain.
- **Connectome graph.** `honey-factory-tools connectome-graph` reads the MaleCNS feather
  files with pyarrow and selects fly-brain's components using the same cell-type and
  class rules. It writes a compact cell-type and central-complex neuron graph for the
  network view. The rules are copied, not imported, so they must be kept in step with
  fly-brain.

## Consequences

- honey-factory-tools gains numpy, scipy and matplotlib through fly-vision, and pyarrow
  for the connectome graph. Validation
  is unchanged, but the package's environment is heavier.
- Eye values are approximate: they come from frames decoded from the lossy clip video at
  half resolution, so they can differ slightly from a `fly-vision convert` of the
  original capture. The page says so.
- The trace format belongs to fly-brain. The reader tolerates missing optional arrays,
  but a format bump on that side needs a matching change here.
