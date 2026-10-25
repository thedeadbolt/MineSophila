# fly-vision v0: agent brief

> **Status: historical, synced 2026-10-25.** This brief was written before implementation.
> The plan (`docs/plans/2026-10-25-fly-vision-v0.md`), [ADR 0016](../../../docs/adr/0016-fly-vision-v0-eye-model.md),
> the [assumptions register](assumptions.md) and the [interface note](interface.md) supersede it where
> they differ. The sections below are kept as written; this block records the differences.

## Sync with the implementation

**Decisions and amendments**

- **Panorama geometry (A1; supersedes sections 1 and 3).** The "1600 × 825, ~160°, horizon at
  mid-height" description is outdated collaborator documentation. fly-vision follows
  honey-factory's current contract `equirectangular-310x160-v1`: level, longitude −155…+155°,
  elevation −70…+90°, image centre at elevation **+10°**, default size 2480 × 1280. The
  formulas in section 3 with centre elevation 0 do not apply; use `honey_factory_panorama()`.
- **A2.** The sampler takes a `Projection` protocol (`width`, `height`, `rays()`,
  `solid_angle()`, `key()`); only `EquirectSpec` exists in v0.
- **A3, A4, A5.** Package in `packages/fly-vision/`, tests in `tests/fly-vision/`, lattice as
  package data (`lattices/generated_v1.npz`), version read from package metadata.
- **A6, A8.** Milestone pauses with hex plots; real-capture smoke runs are done by hand.
- **A7.** A rotation test (shift by one interommatidial angle moves the output by one column).
- **A9 (supersedes decision 1).** Labels are a pure transformation: `FlyLabels` holds only
  `honey_coverage` plus id metadata. No `visibility` field, no thresholds, no detection.
- **A10.** The generated lattice's binocular overlap is nearly redundant (median partner
  distance about 1.2°); see assumptions.
- **Decision 2.** Four subtypes: `pale`, `yellow`, `dorsal_yellow` (R7 mean of Rh3 and Rh4,
  R8 Rh6) and `dra` (R7 and R8 both Rh3).
- **Decision 3.** RGB primaries are assumed Gaussians (610 / 545 / 465 nm, FWHM 30 / 40 /
  25 nm), luminance-matched to sRGB under a Gaussian V(λ).
- **Decision 4.** Level frame and a calibration capture (see the interface note).
- **Decision 5.** `input_levels` and `low_bit_depth` (< 32 distinct levels) in frame meta.
- **Decision 6.** Cache keys name every input; a panorama of the wrong shape raises.
- **Decision 7.** `FlyEye.sample(...)` and `FlyEye.reset()` from day one.
- **Decision 8.** A metamer diagnostic compares honey and distractor colours in receptor space.
- **Decision 9.** Provenance is required on every frame.
- **Decision 10.** R7 is not an informative feature in v0 until `r7_blue_sensitivity()` and
  the real-frame check say so.
- **Decision 11.** Adaptation defaults to `none`; `frame_mean` is experimental.
- **Other image types.** Perspective photos and cube maps are deferred. The lattice is the
  fly and stays fixed; an image is placed in the fly's visual field and uncovered ommatidia
  are `valid = False`.

**Answers to the open questions (section 9)**

1. Panorama conventions: level frame, 310° × 160°, elevation −70…+90°, image centre at
   +10°, sRGB 8-bit; the spans are fixed by the contract name and given in code, not read
   per frame.
3. Lattice: generated for v0; FlyGym or real lattice later.
4. Nonlinearity and contrast normalisation: off by default (`adaptation = "none"`).
5. Storage: `.npz` files (no pickle) with JSON `meta`, inputs and labels separate.

Question 2 (camera pose and head frame) is closed by assumption: the head is level and pose
is not used.

---

You are helping build `fly-vision`, one package of **MineSophila**, a project that models
*Drosophila melanogaster* vision and uses it to find honey blocks in Minecraft. Read this whole
brief before writing code. When something here is marked **open**, ask the user instead of
guessing.

## 1. Project context

- **honey-factory** (owned by a collaborator) is a Minecraft mod that generates scenes and
  renders a cube map internally, then **projects it into a single flat equirectangular
  panorama** (one RGB image per frame, longitude across, latitude down) covering the fly's
  assumed field of view of **310° horizontally**, with an aligned honey mask and camera
  metadata. fly-vision receives the flat panorama, not 6 faces.
- **fly-vision** (this package, owned by the user) is a **virtual fly eye**. It samples those
  panoramas the way a fly's compound eye would and outputs photoreceptor activations.
- **Downstream** (later phases, not this package): a connectome-constrained model built from the
  optic lobes of the MaleCNS v1.0 connectome, plus a separate smell module.

Think of fly-vision as a camera, not an image filter: each ommatidium is a viewing direction
that samples whatever part of the panorama covers that direction.

**Important:** the equirectangular image is a map of directions, not a photo to be resized or
filtered. Its distortions (curved horizon, stretched top and bottom rows) are expected. Never
apply ordinary 2D image operations (resizing, 2D convolution, cropping) to it before sampling;
convert pixels to directions first.

## 2. Scope

### In scope for v0

1. Load a static **eye lattice** (ommatidium directions, eye, subtype).
2. Build a **sampling matrix** from panorama pixels to ommatidia.
3. Convert RGB to **photoreceptor channels** (R1–6, R7, R8).
4. Project the **honey mask** through the same sampler to get labels.
5. Write the **output data structure** and a debug **hex plot**.
6. A **test suite** that verifies all of the above.

### Out of scope (do not implement)

- Any lamina, medulla or other optic-lobe processing. That is the connectome model's job.
  Hand-coding it here would make it impossible to tell what came from the wiring.
- Learned layers or trainable parameters of any kind. fly-vision is a fixed sensor.
- Ocelli, polarisation (dorsal rim area), photoreceptor temporal dynamics, smell.
- Synthetic UV (assigning UV reflectance to blocks). R7 is computed from the visible-light
  tails of the UV rhodopsin curves instead (see step 3 and section 6).

## 3. The contract

```text
sample(panorama, mask, pose, lattice, config) -> FlyFrame, FlyLabels
```

Image in, data structure out. **The output is not an image.** It is arrays of numbers indexed by
ommatidium on a hex lattice.

### Input (from honey-factory)

| Field | Description | Status |
|---|---|---|
| `panorama` | one equirectangular RGB image, H×W×3 (example dataset frame: 1600×825 PNG) | agreed |
| horizontal span | 310° (honey-factory's assumed fly field of view) | agreed |
| vertical span | probably ~160° (825 px at the horizontal scale of 310°/1600 ≈ 0.194°/px) | **open:** confirm |
| centre direction | assumed: image centre = straight ahead, horizon at mid-height, the missing 50° directly behind | **open:** confirm |
| colour space | sRGB or linear | **open:** prefer linear before tone mapping if honey-factory can export it |
| `mask` | H×W visible-honey mask, pixel-aligned to `panorama` | agreed in principle |
| `pose` | camera position and orientation in world coordinates | **open:** exact format |
| `meta` | honey-factory's own metadata (scene id, tick, spans, weather, lighting, biome, difficulty tier). fly-vision reads only what it needs (spans, ids) and keeps the reference | owned by honey-factory |

**Equirectangular mapping.** Column x maps linearly to longitude (azimuth) and row y maps linearly
to latitude (elevation). The panorama is **not** a full 360° × 180° view: it spans 310°
horizontally, and 1600×825 at a constant degrees-per-pixel implies about 160° vertically. For
pixel centre (x + 0.5, y + 0.5), width W, height H, spans `fov_h`, `fov_v`:

```text
azimuth   = ((x + 0.5) / W - 0.5) * fov_h      # degrees, 0 = straight ahead
elevation = (0.5 - (y + 0.5) / H) * fov_v      # degrees, 0 = horizon
```

- **Read the spans and centre direction from capture metadata**; never hard-code 310°. If
  honey-factory does not record them yet, ask the user to agree this with the collaborator.
- With a 310° span, the left and right edges are **50° apart and must not wrap**. Wrap only if
  a frame's span is 360°.
- At ~5 px per degree, each ommatidium's ~5° cone covers roughly 25×25 pixels, so resolution is
  ample. Do not downsample before sampling.

**Image field of view vs eye coverage.** 310° is honey-factory's assumption for the fly's
combined horizontal field of view; treat it as an approximation to be checked against the
literature, and record the source in `docs/assumptions.md`. fly-vision does not depend on it
being exact: the eye lattice defines which directions the fly sees. Ommatidia whose directions
fall outside the image get `valid = False`, and image regions no ommatidium looks at are unused.
Report both as a diagnostic (fraction of ommatidia covered, fraction of image used) so any
mismatch between the image span and the eye map is visible rather than silent.

Example frames already exist, so test with real captures early, alongside synthetic
equirectangular images you generate (uniform colour, single dot, stripes of known angular width).

**Rendering conditions in captures (decided).** Weather, lighting, time of day and biome vary on
purpose: they are part of honey-factory's difficulty conditions. The example frame has falling
snow, dusk lighting and fog. fly-vision must **not** remove, filter or correct any of this; it
samples whatever was rendered. That metadata belongs to honey-factory. fly-vision does not
generate it; it only keeps the link (`scene_id`, frame id) so results can later be split by
condition.

One consequence for the pipeline: dark scenes produce much smaller raw activations than
daylight ones. Real photoreceptors light-adapt, which is what the optional adaptation /
contrast-normalisation step (step 3.5) models. Ask the user whether it should be on by default
given that lighting varies across tiers, and test both settings on a day and a night frame.

Write the sampler against an abstraction of "pixel → unit ray direction", not against the
equirectangular layout directly. Equirectangular (the default), cube map and plain perspective images should each need only a small
adapter. The collaborator wants to compare different fields of view (for example 90° vs 310°).

### Lattice (static, saved once per eye model)

| Field | Shape | Description |
|---|---|---|
| `ommatidium_id` | [N] | stable integer id |
| `eye` | [N] | `L` / `R` |
| `hex_uv` | [N, 2] | lattice coordinates |
| `direction` | [N, 2] | azimuth, elevation (degrees, head-centred frame; document the frame) |
| `subtype` | [N] | `pale` / `yellow` / `dra` |
| `column_id` | [N] | MaleCNS optic-lobe column; empty for now, filled in phase 2 |

Store the lattice as a data file (for example `.npz` or `.parquet`) with a version field, so it
can later be replaced by MaleCNS column coordinates without code changes.

### Output per frame

```text
FlyFrame
  activations  [N, C] float32   C = (R1_6, R7, R8); order fixed and documented
  valid        [N] bool         ommatidium covered by the input image's field of view
  meta         dict             scene_id, tick, pose, config hash, lattice version,
                                flags such as uv_modelled = False

FlyLabels (stored separately, never fed to the model)
  honey_coverage  [N] float32   0–1, mask projected through the same sampling weights
```

Batches stack to `[T, N, C]` for PyTorch. Keep labels in separate files from inputs.

## 4. Pipeline

### Step 1: eye lattice

- First choice: extract ommatidium viewing directions and pale/yellow assignment from
  NeuroMechFly v2 / FlyGym. Note that FlyGym 2.x (March 2026) is a rewrite that is not
  backward compatible with 1.x; check which version has a working vision module.
- Fallback: generate a regular hex lattice, roughly 700–800 ommatidia per eye at about 5°
  spacing, covering roughly a hemisphere per eye with small binocular overlap.
- Assign pale (~30%) and yellow (~70%) at random from a recorded seed. Fixed seed = one
  simulated fly.
- Define the head-centred frame so azimuth 0 / elevation 0 matches the panorama's centre
  convention.

### Step 2: sampling matrix

- For each ommatidium, weight panorama pixels with a Gaussian of the angle between the pixel's
  ray and the ommatidium direction. Acceptance angle (FWHM) about 5°, configurable.
- Account for pixel solid angle. In an equirectangular image a pixel's solid angle scales with
  cos(latitude): rows near the top and bottom are heavily stretched and cover far less solid angle
  than rows at the horizon. Without this weighting, ommatidia looking up or down are biased toward
  the stretched polar rows.
- Normalise weights to sum to 1 per ommatidium.
- Store as a sparse matrix (scipy.sparse, convertible to torch sparse). Build once per
  (lattice, panorama resolution and angular extents, acceptance angle); cache to disk.
- Ommatidia with no covering pixels get `valid = False`.
- Per frame: `activations_raw = W @ pixels` for each colour channel.

### Step 3: RGB to photoreceptor channels

1. Linearise sRGB if the input is sRGB.
2. Treat R, G, B as display primaries with known emission spectra (start with standard sRGB
   primaries; document the choice).
3. Integrate each primary against rhodopsin sensitivity templates (Govardovskii template or
   measured curves from Salcedo et al. 1999) to get a fixed matrix: linear RGB → (Rh1, Rh3,
   Rh4, Rh5, Rh6). Integrate over the full range where the primaries emit (~380–780 nm);
   do not truncate the UV rhodopsin curves at their peaks.
4. Map per ommatidium:
   - `R1_6` ← Rh1 catch
   - `R7` ← Rh3 catch if pale, Rh4 catch if yellow
   - `R8` ← Rh5 catch if pale, Rh6 catch if yellow
   - Set `meta.uv_modelled = False`.

**How R7 is handled (decision).** Rh3 and Rh4 peak in the UV, which RGB does not contain, but
their sensitivity curves have long-wavelength tails that extend into violet and blue. Integrating
the RGB primaries against those tails gives R7 values that are **small, non-zero and
scene-dependent**: bluish surfaces excite R7 slightly more, long-wavelength surfaces such as honey
slightly less. This represents what R7 would catch in a world with no UV light. It is not a
measurement of real UV.

Rules that follow from this decision:

- Do **not** replace R7 with a constant ε. A constant makes R7/R8 = ε/R8, which encodes inverse
  brightness while looking like a colour signal.
- Do **not** rescale R7 to match R8's range. Its small magnitude is the honest result.
- Any receptor ratio or log-ratio must handle near-zero values **at the point of computation**
  (for example `R7 / (R8 + eps)` or `log(R7 + eps) - log(R8 + eps)`), with `eps` documented as a
  numerical choice. Keep the stored data unmodified.
- Keep `meta.uv_modelled = False` in every frame, so downstream code and results can state that
  the true UV component is missing.
- Synthetic UV (block-assigned UV reflectance) remains a possible later experiment. If added, it
  must sit behind a config flag, set `uv_modelled = True`, and be documented as a design choice.
5. Optional, behind a config flag: a compressive nonlinearity (for example Naka–Rushton) and
   contrast normalisation. Minecraft frames are tone-mapped display values, so absolute
   intensity has no biological meaning; only contrast does.

### Step 4: labels

- `honey_coverage = W @ mask_pixels`, using exactly the same `W` as the activations.

### Step 5: debug plot

- `plot_hex(frame, channel)` draws each ommatidium as a hexagon at its lattice position,
  coloured by value. One panel per eye. This is a view of the data, not part of the contract.

## 5. Validation (write these as automated tests)

### Code correctness

- Uniform image → identical activations in every valid ommatidium.
- Single bright dot → only ommatidia whose acceptance cone covers it respond, with Gaussian
  falloff of the configured width.
- Shift the scene by one interommatidial angle (~5°) in azimuth → output shifts by about one
  column. This catches orientation and indexing bugs.
- A uniform image must still give uniform activations near the top and bottom rows. This
  catches a missing `cos(elevation)` weight.
- A dot placed at known (azimuth, elevation) excites the ommatidium pointing there, including
  near the left/right edges (catches wrap-around and centre-convention errors).
- Gratings: stripe patterns finer than about 2 × interommatidial angle (~10° per cycle) average
  out to near-uniform; coarser ones survive.
- Weights sum to 1 per ommatidium; `honey_coverage` stays within [0, 1].
- Same seed → identical mosaic and outputs.
- Mask and activations use the identical sampling matrix.
- R7 is non-zero but much smaller than R8 for typical scenes, and never NaN or negative.
- R7 responds more to a pure blue patch than to a pure red or honey-coloured patch (checks that
  the curve tails are integrated, not truncated).
- `meta.uv_modelled` is `False` in every frame.

### Sanity checks against expectations

- A 1 m block at 5 m covers about 2–3 ommatidia, at 10 m about 1, at 20 m less than 1
  (detection only).
- Reduced field of view → the corresponding ommatidia become `valid = False`, with no change
  in output shape.

### Later (document as TODO, do not block v0)

- Cross-check against FlyGym's own retina rendering of a comparable scene.
- Feed moving edges through fly-vision into a pretrained flyvis model (Lappalainen et al. 2024)
  and check that T4/T5 direction tuning appears.
- Information check: simple baselines (logistic regression, small CNN) can localise honey from
  the output; shuffled labels drop to chance.

## 6. Assumptions register

Keep a file `docs/assumptions.md`. For every parameter record: value, whether it is
**measured** (with citation), **estimated**, or **chosen**, and where in the code it is used.
Starting entries:

| Parameter | v0 value | Type |
|---|---|---|
| Ommatidia per eye | from FlyGym or ~750 | measured / estimated |
| Interommatidial angle | ~5° | measured, approximate |
| Acceptance angle | ~5° FWHM | measured, approximate |
| Pale / yellow ratio | 30 / 70, random | measured |
| Panorama field of view | 310° × ~160°, equirectangular | chosen by honey-factory; check against literature and the eye map's own coverage |
| Rhodopsin curves | Salcedo et al. 1999 or Govardovskii template | measured / model |
| RGB primaries | sRGB standard | chosen |
| R7 (UV) | Rh3/Rh4 catch from RGB primaries via visible-light curve tails; true UV missing (`uv_modelled = False`) | chosen (RGB has no UV); curves measured |
| `eps` in receptor ratios | small constant at computation time, not stored | chosen (numerical) |
| R1–R6 pooled as one channel per direction | neural superposition approximation | chosen |
| Male vs female eye | FlyGym is female; MaleCNS is male | known mismatch, phase 2 |

Cite sources in code comments next to the parameter they justify.

## 7. Suggested layout

```text
fly-vision/
  pyproject.toml
  src/fly_vision/
    lattice.py        load / generate / save eye lattice
    projection.py     pixel → ray adapters (equirect default; cube map, perspective)
    sampler.py        build and apply the sparse sampling matrix
    spectral.py       RGB → rhodopsin catches → R1_6, R7, R8
    frame.py          FlyFrame / FlyLabels dataclasses, save / load
    plot.py           hex debug plots
  data/lattices/      versioned lattice files
  tests/
  docs/assumptions.md
  docs/interface.md   input/output contract to share with honey-factory
```

Python, numpy, scipy, matplotlib; torch only as an optional dependency for batching.

## 8. Milestones

1. **M0:** lattice loaded or generated, saved, plotted.
2. **M1:** sampler on synthetic equirectangular images and a real capture; uniform, dot and rotation tests pass.
3. **M2:** spectral conversion and mosaic; output written in the FlyFrame format.
4. **M3:** mask projection and labels; grating and block-size checks pass.
5. **M4:** run on real honey-factory captures; `docs/interface.md` agreed with the collaborator.

Stop after each milestone and show the user the hex plots before continuing.

## 9. Open questions (ask the user)

1. Panorama conventions: confirm the vertical span (~160° implied), which direction the image
   centre faces, colour space (sRGB or linear), and whether capture metadata records the spans.
   The horizontal span is 310°.
2. Camera pose format and head-centred frame definition.
3. FlyGym lattice vs generated lattice for v0.
4. Whether the nonlinearity / contrast normalisation is on by default.
5. Storage format for frames (`.npz`, HDF5, zarr) given honey-factory uses PNG + JSONL.

## 10. References

- NeuroMechFly v2 / FlyGym: simulated fly retina (github.com/NeLy-EPFL/flygym).
- Lappalainen et al. (2024), *Nature*: flyvis, connectome-constrained fly visual system model.
- Millward et al. (2022), *eLife*: CompoundRay compound-eye renderer.
- Salcedo et al. (1999), *J. Neurosci.*: *Drosophila* rhodopsin spectral sensitivities.
- Wernet & Desplan (2004): pale / yellow / dorsal rim ommatidia.
- MaleCNS v1.0 (Janelia, Google Research et al., 2026, *Cell*): target connectome for phase 2.
