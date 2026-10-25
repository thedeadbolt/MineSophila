# fly-vision v0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fixed, testable virtual fly eye that turns one 310° equirectangular Minecraft panorama (+ honey mask) into per-ommatidium R1–6 / R7 / R8 activations, labels and provenance.

**Architecture:** Pixels are converted to unit rays through a `Projection` adapter (equirectangular in v0); a cached sparse Gaussian sampling matrix maps them to ~1,700 ommatidia (two eyes) on a generated hex lattice; a fixed spectral matrix maps linear RGB to rhodopsin catches per ommatidial subtype. A `FlyEye` object owns lattice, matrix and spectral model, so later phases can add temporal state without changing the call signature.

**Tech Stack:** Python ≥ 3.13 (uv workspace member), numpy, scipy (sparse, spatial), pillow, matplotlib, pytest. torch optional (not used in v0).

**Spec:** [`fly-vision-agent-brief.md`](../fly-vision-agent-brief.md) (the brief). This plan adds the decisions in "Decisions added by this plan" and the amendments below; where they differ from the brief, this plan wins and the brief is updated in Task 9.

## Amendments (2026-10-25, agreed with the user)

These override the original draft of this plan and the brief where they conflict.

- **A1. Geometry follows the honey-factory contract** (`schemas/honey-factory/README.md`, `equirectangular-310x160-v1`; ADR 0010). The panorama is *level*: forward and right come from the camera yaw alone, up is world up, pitch is ignored. Longitude spans −155…+155°, elevation −70…+90°, so the image centre is at elevation **+10°**, not 0°. Default capture size 2480 × 1280. Pixel centres: `longitude = −155 + 310 (px + 0.5)/W`, `elevation = 90 − 160 (py + 0.5)/H`. The eye (head-centred) frame is this level frame: azimuth 0, elevation 0 = forward on the horizon.
- **A2. Pixel → ray abstraction** (brief §3). The sampler consumes a `Projection` protocol (`width`, `height`, `rays()`, `solid_angle()`, `key()`), not the equirectangular layout. Only `EquirectSpec` is implemented in v0. Neighbourhoods are found in direction space (KD-tree on unit rays), so the 310° panorama never wraps and a 360° one wraps automatically.
- **A3. Repository layout.** Package at `packages/fly-vision/` (uv workspace member, `uv_build`). Tests in `tests/fly-vision/` (not inside the package), fixtures in `tests/fly-vision/fixtures/`. Package docs in `packages/fly-vision/docs/`. No repo-root or shared `tests/conftest.py`.
- **A4. Lattice file is package data**, at `src/fly_vision/lattices/generated_v1.npz` (git ignores `data/`, and issue #1 blocks dataset storage). `default_lattice()` loads it.
- **A5. Version.** `__version__` stays read from package metadata; release-please owns versions and `CHANGELOG.md`. Never hard-code a version.
- **A6. Milestone pauses** (brief §8): stop after Task 3 (M0), Task 4 (M1), Task 7 (M2 + M3) and Task 9 (M4) and show the user hex plots. `plot_hex` therefore moves to Task 3.
- **A7. Brief rotation test** (shift by one interommatidial angle → output shifts by one column) is added to Task 4.
- **A8. Real-capture smoke runs** (Task 9 step 6, M1/M4 checks) are done by the controller with frames the user provides; they are not automated tests.

## Global Constraints

- No optic-lobe processing (lamina, medulla, …) and no learned or trainable parameters anywhere in the package.
- The panorama is never resized, filtered, cropped or convolved as a 2D image; every operation happens after pixel → direction.
- Field of view and image size come from capture metadata or explicit arguments. The contract numbers `310`, `160` and `10` (elevation of the image centre) appear only in `honey_factory_panorama()` in `projection.py` (named after `equirectangular-310x160-v1`) and in tests.
- Eye frame = honey-factory's level panorama frame (A1): `to_unit(az, el) = (cos el cos az, cos el sin az, sin el)` as (forward, right, up); azimuth positive to the right, elevation positive up.
- Azimuth never wraps unless the projection itself covers 360°.
- Pixel weights include pixel solid angle (equirectangular: `Δaz · (sin el_top − sin el_bottom)` per pixel, ∝ cos(elevation)).
- Output order of channels is fixed: `CHANNELS = ("R1_6", "R7", "R8")`.
- R7 = Rh3/Rh4 catch from the visible tails; never a constant, never rescaled in storage; `meta["uv_modelled"] = False` on every frame.
- Ratios between receptors use `eps` at the point of computation only; stored data is never modified.
- Labels are written to separate files from model inputs.
- Any adaptation uses only the current frame (no dataset-wide statistics).
- Every saved frame carries provenance: package version, lattice hash, sampling-matrix key, spectral hash, config hash, source image SHA-256.
- Rhodopsin peaks live in one table in `spectral.py`, values provisional (Salcedo et al. 1999): Rh1 478, Rh3 345, Rh4 375, Rh5 437, Rh6 508 nm. Verify against the paper before merging Task 5 and cite it there.
- Every chosen or estimated parameter gets a row in `packages/fly-vision/docs/assumptions.md` in the task that introduces it.
- Repository rules: `just lint`, `just typecheck` (pyright strict, includes `tests/`) and `just test fly-vision` must pass before each commit. Test code taken from this plan may need type annotations for pyright strict; keep its assertions unchanged. Tests import only `fly_vision` and its declared dependencies. Commit subjects use Conventional Commits scoped to the package, e.g. `feat(fly-vision): …`. Do not edit `CHANGELOG.md` or package versions.

## Decisions added by this plan

These close oversights in the brief. Each is owned by a task and tested.

1. **Fly-space visibility labels (Task 7).** honey-factory's mask says honey is in the *image*; that is not the same as the fly seeing it (blind zone behind, gaps between eyes, sub-ommatidium size). Labels add a frame-level `visibility` in fly space: `visible_to_fly` (max valid coverage ≥ 0.05), `in_image_not_fly` (mask non-empty but below threshold), `not_in_image` (mask empty). honey-factory's own `target_status` (absent / out_of_view / subpixel / …) passes through untouched in `meta["source"]`.
2. **Complete subtype mapping (Tasks 3, 5).** The lattice uses four subtypes: `pale`, `yellow`, `dorsal_yellow`, `dra`. `dra` → R7 = Rh3, R8 = Rh3. `dorsal_yellow` → R7 = mean(Rh3, Rh4), R8 = Rh6. The brief only defined pale/yellow, which would break on the dorsal rim.
3. **Spectra for RGB primaries are an assumption (Task 5).** sRGB defines chromaticities, not spectra, and R7 (curve tails) is especially sensitive to the blue primary's spectrum. v0 uses Gaussian primaries (peaks 610 / 545 / 465 nm, FWHM 30 / 40 / 25 nm) scaled so their luminances match sRGB weights (0.2126 / 0.7152 / 0.0722) under a Gaussian V(λ) (peak 555 nm, FWHM 100 nm). A diagnostic reports how R7 changes when the blue peak shifts ±10 nm. The alternative model (Minecraft colours as surface reflectances under daylight, via spectral upsampling) is noted in assumptions as a later option.
4. **Level, contract-defined frame + calibration capture (Tasks 2, 9).** The panorama follows honey-factory's `equirectangular-310x160-v1` contract (A1): level, yaw-aligned, elevation −70…+90°. The eye frame is that level frame, so the fly's head is assumed level (pitch ignored) and the image centre (elevation +10°) is *not* the eye's (0, 0). A calibration test uses one capture with honey at known directions, to catch sign or half-pixel bugs on either side; it skips until that fixture exists.
5. **Low bit depth at night (Task 7).** Dark 8-bit frames have few distinct levels; linearisation and adaptation amplify the quantisation, worst on tiny R7 values. Frames record `meta["input_levels"]` and `meta["low_bit_depth"]` (< 32 distinct levels in any channel). 16-bit PNG input is accepted.
6. **Provenance and cache safety (Tasks 1, 4, 7).** Cache keys include every input that changes the matrix; a panorama whose shape differs from the projection raises instead of using a stale matrix.
7. **Stateful API from day one (Task 7).** `FlyEye.sample(...)` and `FlyEye.reset()`; v0 is stateless inside, but phase-4 temporal adaptation will not change the interface.
8. **Metamer gate (Task 8).** Before dataset generation, honey and each distractor block's mean colour are compared in receptor space; colours the fly cannot tell apart are reported.
9. **Provenance is required, not optional (Task 7).** Many configurations of the same frames will be generated (spectral variants now, resolution variants later), so every output carries the hashes listed in Task 7. This is for comparing your own experiments months later, independent of communication with honey-factory.
10. **R7 is not an informative feature in v0.** Its values are correct for a UV-free world, but their size depends on the chosen blue primary and may vary less than 8-bit quantisation. After Task 7, run `r7_blue_sensitivity()` and compare R7's spread across real frames with its quantisation step; record the result. No downstream analysis may treat R7/R8 differences as colour vision without that check.
11. **Adaptation default `none` (Task 6).** `frame_mean` mode exists for the day/night comparison; it rescales each channel, including R7, so it is experimental until the night-frame check is done.

## Review Focus

1. **Panorama orientation does not match the eye frame** (sign flip, half-pixel offset, or a deviation from the honey-factory contract) → honey appears in the wrong ommatidia while all synthetic tests pass. Pinned: Task 2 contract-formula test, Task 4 rotation test, calibration test in Task 9.
2. **Honey visible in the image but not to the fly** (behind, in the 50° rear gap, below −70°, or smaller than a cone) → label must say `in_image_not_fly`, not visible. Pinned: Task 7 tests.
3. **Night frames in 8-bit** → quantised R7 / R8; frame must be flagged, not silently trusted. Pinned: Task 7 `low_bit_depth` test.
4. **honey-factory changes resolution or FOV** → matrix rebuilt or a clear error, never a stale matrix. Pinned: Task 4 cache-key test and Task 7 shape-mismatch test.
5. **Spectral assumptions drive conclusions** (blue primary, normalisation) → sensitivity is reported and the spectral hash is in every frame. Pinned: Task 5 diagnostic test and Task 7 provenance test.

---

## File Structure

```text
packages/fly-vision/
  pyproject.toml
  src/fly_vision/
    __init__.py        __version__ (from metadata), CHANNELS
    config.py          FlyVisionConfig, config_hash
    projection.py      Projection protocol, EquirectSpec, honey_factory_panorama, pixel ↔ direction
    lattice.py         Lattice, generate, subtypes, validate, save/load, hash, default_lattice
    lattices/generated_v1.npz   packaged default lattice
    plot.py            plot_hex
    sampler.py         SamplingMatrix, build, apply, cache
    spectral.py        rhodopsin templates, primaries, SpectralModel
    adaptation.py      adapt()
    frame.py           SourceRef, FlyFrame, FlyLabels, save/load
    eye.py             FlyEye (ties everything together), image loading
    diagnostics.py     coverage report, metamer table, primary sensitivity
    cli.py             convert command
  docs/
    fly-vision-agent-brief.md
    assumptions.md
    interface.md
    plans/2026-10-25-fly-vision-v0.md
tests/fly-vision/      one test file per module + test_calibration.py
tests/fly-vision/fixtures/calibration/   (provided later by honey-factory)
```

---

### Task 1: Package scaffold, config and hashing

**Files:**
- Modify: `packages/fly-vision/pyproject.toml`, root `pyproject.toml` (dev group), `uv.lock`, `packages/fly-vision/src/fly_vision/__init__.py`
- Create: `packages/fly-vision/src/fly_vision/config.py`, `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_config.py` (keep the existing `tests/fly-vision/test_package.py`)

**Interfaces:**
- Produces: `__version__: str` (unchanged: read from installed metadata), `CHANNELS: tuple[str, str, str] = ("R1_6", "R7", "R8")`; `FlyVisionConfig` (frozen dataclass): `acceptance_fwhm_deg: float = 5.0`, `cutoff_sigmas: float = 3.0`, `valid_coverage_min: float = 0.5`, `visible_coverage_threshold: float = 0.05`, `low_bit_depth_levels: int = 32`, `adaptation: Literal["none", "frame_mean"] = "none"`, `seed: int = 0`; `config_hash(cfg: FlyVisionConfig) -> str` (sha256 hex of the fields as JSON with sorted keys, plus `__version__`).

- [ ] **Step 1: Dependencies.** From the repo root: `uv add --package fly-vision numpy scipy pillow matplotlib`; add `scipy-stubs` to the root `dev` dependency group (`uv add --dev scipy-stubs`) so `just typecheck` sees scipy types. Check `uv.lock` updated and `just test fly-vision` still passes.
- [ ] **Step 2: Write the failing tests** in `tests/fly-vision/test_config.py`:

```python
from fly_vision import CHANNELS
from fly_vision.config import FlyVisionConfig, config_hash


def test_channels_order():
    assert CHANNELS == ("R1_6", "R7", "R8")


def test_config_defaults():
    c = FlyVisionConfig()
    assert (
        c.acceptance_fwhm_deg == 5.0
        and c.adaptation == "none"
        and c.visible_coverage_threshold == 0.05
    )


def test_config_hash_stable_and_sensitive():
    assert config_hash(FlyVisionConfig()) == config_hash(FlyVisionConfig())
    assert config_hash(FlyVisionConfig()) != config_hash(FlyVisionConfig(acceptance_fwhm_deg=4.0))
```

- [ ] **Step 3: Run** `just test fly-vision` — Expected: FAIL (`CHANNELS` / `fly_vision.config` missing).
- [ ] **Step 4: Implement** `CHANNELS` in `__init__.py` (export it in `__all__`, keep `__version__` as is) and `config.py` as in Interfaces. Create `packages/fly-vision/docs/assumptions.md` with the brief's section 6 table, columns `Parameter | Value | Type | Source | Code location`. Adjust the "Panorama field of view" row to the contract: 310° × 160°, elevation −70…+90°, level frame (honey-factory `equirectangular-310x160-v1`, ADR 0010). Link `assumptions.md` from `packages/fly-vision/README.md`.
- [ ] **Step 5: Run** `just test fly-vision`, `just lint`, `just typecheck` — Expected: PASS.
- [ ] **Step 6: Commit** `feat(fly-vision): add config, hashing and dependencies`

---

### Task 2: Projection: pixel → ray, equirectangular contract

**Files:**
- Create: `packages/fly-vision/src/fly_vision/projection.py`
- Test: `tests/fly-vision/test_projection.py`

**Interfaces:**
- Produces:
  - `Projection` (`typing.Protocol`): `width: int`, `height: int`, `rays() -> np.ndarray` (`(H, W, 3)` unit vectors in the eye frame, (forward, right, up)), `solid_angle() -> np.ndarray` (`(H, W)` steradians), `key() -> str` (stable text naming the projection type and every field; used in cache keys).
  - `EquirectSpec` (frozen dataclass, implements `Projection`): `width: int`, `height: int`, `fov_h_deg: float`, `fov_v_deg: float`, `center_az_deg: float = 0.0`, `center_el_deg: float = 0.0`.
  - `honey_factory_panorama(width: int, height: int) -> EquirectSpec`: the `equirectangular-310x160-v1` contract, `fov_h_deg=310`, `fov_v_deg=160`, `center_el_deg=10`. The only place these numbers appear in source.
  - `pixel_directions(spec) -> tuple[np.ndarray, np.ndarray]` (az, el in degrees, each `(H, W)`; pixel centres: `az = center_az + ((x + 0.5)/W − 0.5)·fov_h`, `el = center_el + (0.5 − (y + 0.5)/H)·fov_v`).
  - `pixel_solid_angle(spec) -> np.ndarray` `(H, W)`: `Δaz_rad · (sin el_top_edge − sin el_bottom_edge)` per pixel (exact for an equirectangular band).
  - `direction_to_pixel(az_deg, el_deg, spec) -> tuple[np.ndarray, np.ndarray]` (float x, y in pixel units; pixel `i` covers `[i, i+1)`).
  - `to_unit(az_deg, el_deg) -> np.ndarray` (`(..., 3)`, `(cos el cos az, cos el sin az, sin el)`).

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np
from fly_vision.projection import (
    direction_to_pixel,
    honey_factory_panorama,
    pixel_directions,
    pixel_solid_angle,
    to_unit,
)

S = honey_factory_panorama(width=2480, height=1280)


def test_preset_fields():
    assert (S.fov_h_deg, S.fov_v_deg, S.center_az_deg, S.center_el_deg) == (310.0, 160.0, 0.0, 10.0)


def test_matches_honey_factory_formula():
    # schemas/honey-factory/README.md, equirectangular-310x160-v1
    az, el = pixel_directions(S)
    for px, py in [(0, 0), (7, 11), (1239, 639), (2479, 1279)]:
        assert np.isclose(az[py, px], -155 + 310 * (px + 0.5) / 2480)
        assert np.isclose(el[py, px], 90 - 160 * (py + 0.5) / 1280)


def test_symmetric_about_forward():
    az, _ = pixel_directions(S)
    assert np.allclose(az[:, 1239], -az[:, 1240])


def test_solid_angle_matches_band():
    sa = pixel_solid_angle(S)
    expected = np.deg2rad(310) * (np.sin(np.deg2rad(90)) - np.sin(np.deg2rad(-70)))
    assert np.isclose(sa.sum(), expected, rtol=1e-6) and (sa > 0).all()


def test_roundtrip():
    taz, tel = np.array([0.0, 90.0, -120.0, 154.0]), np.array([0.0, 30.0, -60.0, 85.0])
    x, y = direction_to_pixel(taz, tel, S)
    az, el = pixel_directions(S)
    xi, yi = np.floor(x).astype(int), np.floor(y).astype(int)
    assert np.allclose(az[yi, xi], taz, atol=0.2) and np.allclose(el[yi, xi], tel, atol=0.2)


def test_to_unit_axes():
    assert np.allclose(to_unit(np.array(0.0), np.array(0.0)), [1, 0, 0])  # forward
    assert np.allclose(to_unit(np.array(90.0), np.array(0.0)), [0, 1, 0])  # right
    assert np.allclose(to_unit(np.array(0.0), np.array(90.0)), [0, 0, 1])  # up


def test_projection_protocol():
    r = S.rays()
    assert r.shape == (1280, 2480, 3) and np.allclose(np.linalg.norm(r, axis=-1), 1.0)
    assert S.solid_angle().shape == (1280, 2480)
    assert S.key() != honey_factory_panorama(1240, 640).key()
```

- [ ] **Step 2: Run** `just test fly-vision -k projection` — Expected: FAIL.
- [ ] **Step 3: Implement** as in Interfaces. Cite the contract (`schemas/honey-factory/README.md`, `equirectangular-310x160-v1`) in `honey_factory_panorama`'s docstring.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Commit** `feat(fly-vision): add pixel-to-ray projection for the panorama contract`

---

### Task 3: Eye lattice with complete subtypes, and hex plot

**Files:**
- Create: `packages/fly-vision/src/fly_vision/lattice.py`, `packages/fly-vision/src/fly_vision/plot.py`, `packages/fly-vision/src/fly_vision/lattices/generated_v1.npz` (written by a script step)
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_lattice.py`, `tests/fly-vision/test_plot.py`

**Interfaces:**
- Produces: `SUBTYPES = ("pale", "yellow", "dorsal_yellow", "dra")`; `Lattice` dataclass: `ommatidium_id (N,) int32` (0…N−1), `eye (N,) "<U1"` (`"L"`/`"R"`), `hex_uv (N, 2) int32` (row, column; columns numbered by increasing azimuth within a row of one eye), `direction_deg (N, 2) float64` (az, el in the eye frame of the Global Constraints), `subtype (N,) "<U13"`, `column_id (N,) int32` (all `-1`), `version: str` (`"generated-v1"`); `generate_hex_lattice(spacing_deg=5.0, seed=0) -> Lattice`; `validate_lattice(l) -> None` (raises `ValueError`: shapes, dtypes, unique ids, known subtypes and eyes, directions in range); `save_lattice(l, path)`, `load_lattice(path) -> Lattice`; `lattice_hash(l) -> str` (sha256 over every array's bytes and `version`); `default_lattice() -> Lattice` (loads the packaged `lattices/generated_v1.npz` via `importlib.resources`).
- Produces: `plot_hex(lattice, values: np.ndarray (N,), valid: np.ndarray (N,) | None = None, title: str = "") -> matplotlib.figure.Figure` (two panels L / R, azimuth / elevation axes in degrees, hexagon markers coloured by value, invalid in grey, a colour bar).

- [ ] **Step 1: Write the failing tests**

```python
# tests/fly-vision/test_lattice.py
import numpy as np
from fly_vision.lattice import (
    default_lattice,
    generate_hex_lattice,
    lattice_hash,
    load_lattice,
    save_lattice,
    validate_lattice,
)
from fly_vision.projection import to_unit

L = generate_hex_lattice(seed=0)


def test_counts_per_eye():
    for e in "LR":
        assert 600 <= (L.eye == e).sum() <= 1000


def test_neighbour_spacing():
    u = to_unit(L.direction_deg[:, 0], L.direction_deg[:, 1])[L.eye == "R"]
    ang = np.degrees(np.arccos(np.clip(u @ u.T, -1, 1)))
    np.fill_diagonal(ang, 1e9)
    assert np.allclose(np.median(ang.min(1)), 5.0, atol=0.5)


def test_coverage_ranges():
    az = L.direction_deg[:, 0]
    assert az[L.eye == "R"].min() >= -15 - 1e-9 and az[L.eye == "R"].max() <= 155 + 1e-9
    assert az[L.eye == "L"].max() <= 15 + 1e-9 and az[L.eye == "L"].min() >= -155 - 1e-9


def test_columns_increase_with_azimuth():
    for e in "LR":
        m = L.eye == e
        for row in np.unique(L.hex_uv[m, 0]):
            r = m & (L.hex_uv[:, 0] == row)
            order = np.argsort(L.hex_uv[r, 1])
            assert np.all(np.diff(L.direction_deg[r, 0][order]) > 0)


def test_subtypes():
    assert set(L.subtype) <= {"pale", "yellow", "dorsal_yellow", "dra"}
    main = np.isin(L.subtype, ["pale", "yellow", "dorsal_yellow"])
    assert 0.25 <= (L.subtype[main] == "pale").mean() <= 0.35
    assert (L.subtype == "dra").any() and (L.subtype == "dorsal_yellow").any()
    el = L.direction_deg[:, 1]
    assert el[L.subtype == "dra"].min() > el[L.subtype != "dra"].mean()


def test_seed_determinism_and_roundtrip(tmp_path):
    assert lattice_hash(generate_hex_lattice(seed=0)) == lattice_hash(L)
    assert lattice_hash(generate_hex_lattice(seed=1)) != lattice_hash(L)
    save_lattice(L, tmp_path / "l.npz")
    L2 = load_lattice(tmp_path / "l.npz")
    validate_lattice(L2)
    assert lattice_hash(L2) == lattice_hash(L)


def test_packaged_lattice_matches_generator():
    validate_lattice(default_lattice())
    assert lattice_hash(default_lattice()) == lattice_hash(L)
```

```python
# tests/fly-vision/test_plot.py
import matplotlib

matplotlib.use("Agg")
import numpy as np
from fly_vision.lattice import generate_hex_lattice
from fly_vision.plot import plot_hex


def test_plot_hex():
    L = generate_hex_lattice()
    valid = np.ones(L.ommatidium_id.size, bool)
    valid[:20] = False
    fig = plot_hex(L, np.random.default_rng(0).random(L.ommatidium_id.size), valid, title="t")
    assert len(fig.axes) >= 2
```

- [ ] **Step 2: Run** `just test fly-vision -k "lattice or plot"` — Expected: FAIL.
- [ ] **Step 3: Implement.** Right eye: az ∈ [−15°, 155°], el ∈ [−75°, 75°]. Rows at `el_k = el_min + k · d·√3/2` while `el_k ≤ el_max`; within row k, azimuth step `d / cos(el_k)`, positions `az_lo + (j + 0.5·(k mod 2)) · step` while `≤ az_hi`; `hex_uv = (k, j)`. Left eye = mirror of the right (az → −az), columns renumbered by increasing azimuth. Ids in order: eye L then R, then row, then column. Subtypes per eye (L first, then R, from one `np.random.default_rng(seed)`): top row → `dra`; remaining ommatidia with `el ≥ el_max − (el_max − el_min)/3` → `pale` with p = 0.3 else `dorsal_yellow`; rest → `pale` with p = 0.3 else `yellow`. Then write the packaged file: `uv run python -c "from fly_vision.lattice import generate_hex_lattice, save_lattice; save_lattice(generate_hex_lattice(seed=0), 'packages/fly-vision/src/fly_vision/lattices/generated_v1.npz')"` and commit it; check it is included in the built wheel (`uv build --package fly-vision` and list the wheel).
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Document** in `assumptions.md`: eye ranges (chosen, to match the 310° panorama; check against FlyGym/literature), elevation range ±75° (chosen; below −70° falls outside the panorama and is invalid), DRA = top row (chosen), dorsal third rule (chosen), spacing 5° (measured, approximate), pale/yellow 30/70 (measured, Wernet & Desplan 2004), eye frame = level panorama frame (chosen, A1).
- [ ] **Step 6: Commit** `feat(fly-vision): add generated hex eye lattice and hex plot`
- [ ] **Step 7: Milestone M0 (controller).** Render `plot_hex` of subtypes and elevation for the default lattice; show the user; wait for go-ahead.

---

### Task 4: Sampling matrix

**Files:**
- Create: `packages/fly-vision/src/fly_vision/sampler.py`
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_sampler.py`

**Interfaces:**
- Consumes: `Projection`, `EquirectSpec`, `honey_factory_panorama`, `pixel_directions`, `to_unit` (Task 2); `Lattice`, `lattice_hash` (Task 3); `FlyVisionConfig` (Task 1).
- Produces: `SamplingMatrix` dataclass: `W: scipy.sparse.csr_matrix (N, H·W)`, `valid (N,) bool`, `coverage_fraction (N,) float64`, `key: str`, `width: int`, `height: int`; `matrix_key(lattice, projection, cfg) -> str`; `build_sampling_matrix(lattice, projection, cfg) -> SamplingMatrix`; `load_or_build(lattice, projection, cfg, cache_dir: Path) -> SamplingMatrix`; `apply_sampling(sm, image: np.ndarray) -> np.ndarray` (`(H, W, C)` → `(N, C)`; raises `ValueError` if `image.shape[:2] != (sm.height, sm.width)`).

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np
import pytest
from fly_vision.config import FlyVisionConfig
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import EquirectSpec, honey_factory_panorama, pixel_directions, to_unit
from fly_vision.sampler import apply_sampling, build_sampling_matrix, load_or_build, matrix_key

C, L = FlyVisionConfig(), generate_hex_lattice()
S = honey_factory_panorama(width=620, height=320)  # quarter of the default size, for speed
SM = build_sampling_matrix(L, S, C)


def dot_image(az0, el0, radius_deg=0.5):
    az, el = pixel_directions(S)
    ang = np.degrees(np.arccos(np.clip(to_unit(az, el) @ to_unit(az0, el0), -1, 1)))
    return (ang < radius_deg).astype(float)[..., None]


def test_rows_normalised_and_invalid_zero():
    s = np.asarray(SM.W.sum(1)).ravel()
    assert np.allclose(s[SM.valid], 1.0) and np.allclose(s[~SM.valid], 0.0)


def test_uniform_including_poles():
    out = apply_sampling(SM, np.full((S.height, S.width, 1), 0.7))
    assert np.allclose(out[SM.valid], 0.7, atol=1e-9)
    assert SM.valid[L.direction_deg[:, 1] > 70].all()  # dorsal rows are inside the image


def test_below_image_invalid():
    assert not SM.valid[L.direction_deg[:, 1] < -73].any()  # bottom edge of the panorama is -70


def test_dot_hits_nearest_ommatidium_and_falloff():
    out = apply_sampling(SM, dot_image(40.0, 10.0))[:, 0]
    d = np.degrees(np.arccos(np.clip(to_unit(*L.direction_deg.T) @ to_unit(40.0, 10.0), -1, 1)))
    assert d[np.argmax(out)] < 3.0
    far = (d > 8.0) & SM.valid
    assert out[far].max() < 0.01 * out.max()


def test_no_wrap_at_310():
    out = apply_sampling(SM, dot_image(-154.0, 0.0))[:, 0]
    assert out[(L.direction_deg[:, 0] > 140) & SM.valid].max() == 0.0


def test_shift_by_one_column():
    az, el = pixel_directions(S)

    def scene(shift_deg):
        return (
            0.5 + 0.5 * np.cos(2 * np.pi * (az - shift_deg) / 40.0) * np.cos(2 * np.pi * el / 60.0)
        )[..., None]

    r = L.eye == "R"
    row = L.hex_uv[r, 0][np.argmin(np.abs(L.direction_deg[r, 1]))]  # row nearest the horizon
    idx = np.flatnonzero(r & (L.hex_uv[:, 0] == row))
    idx = idx[np.argsort(L.hex_uv[idx, 1])]
    step = float(np.diff(L.direction_deg[idx, 0]).mean())
    a, b = apply_sampling(SM, scene(0.0))[:, 0], apply_sampling(SM, scene(step))[:, 0]
    ok = SM.valid[idx[:-1]] & SM.valid[idx[1:]]
    assert ok.sum() > 20
    assert np.allclose(b[idx[1:]][ok], a[idx[:-1]][ok], atol=0.02)
    assert np.abs(b[idx] - a[idx]).max() > 0.1  # the shift is visible


def test_rear_and_reduced_fov_invalid():
    S90 = EquirectSpec(width=200, height=200, fov_h_deg=90.0, fov_v_deg=90.0)
    SM90 = build_sampling_matrix(L, S90, C)
    assert SM90.W.shape[0] == SM.W.shape[0] and SM90.valid.sum() < SM.valid.sum()
    assert not SM90.valid[np.abs(L.direction_deg[:, 0]) > 60].any()


def test_grating_cutoff():
    az, _ = pixel_directions(S)
    fine = (np.sin(2 * np.pi * az / 6) > 0).astype(float)[..., None]  # 6° period
    coarse = (np.sin(2 * np.pi * az / 40) > 0).astype(float)[..., None]  # 40° period
    v = SM.valid & (np.abs(L.direction_deg[:, 1]) < 30)
    assert apply_sampling(SM, fine)[v].std() < 0.15
    assert apply_sampling(SM, coarse)[v].std() > 0.3


def test_cache_key_and_shape_guard(tmp_path):
    assert matrix_key(L, S, C) != matrix_key(L, honey_factory_panorama(1240, 640), C)
    assert matrix_key(L, S, C) != matrix_key(L, S, FlyVisionConfig(acceptance_fwhm_deg=4.0))
    first = load_or_build(L, S, C, tmp_path)
    assert len(list(tmp_path.iterdir())) == 1
    second = load_or_build(L, S, C, tmp_path)
    assert first.key == second.key and (first.W != second.W).nnz == 0
    with pytest.raises(ValueError):
        apply_sampling(SM, np.zeros((10, 10, 3)))
```

- [ ] **Step 2: Run** `just test fly-vision -k sampler` — Expected: FAIL.
- [ ] **Step 3: Implement.** σ = FWHM / 2.3548 (radians). Flatten `projection.rays()` to `(H·W, 3)`, build a `scipy.spatial.cKDTree`, and for each ommatidium query pixels within angle `cutoff_sigmas · σ` (chord radius `2 sin(θ/2)`). No azimuth bounding boxes: working in direction space means a 310° image cannot wrap. Weight = `exp(−θ²/2σ²) · Ω_pixel` with θ from unit-vector dot products. `coverage_fraction = Σweight / (2πσ²)`; `valid = coverage_fraction ≥ cfg.valid_coverage_min`; valid rows normalised to 1, invalid rows zero. `matrix_key` = sha256 of lattice hash, `projection.key()`, `acceptance_fwhm_deg`, `cutoff_sigmas`, `valid_coverage_min`, `__version__`. Cache as `<key>.npz` (CSR arrays + `valid`, `coverage_fraction`, `width`, `height`, `key`); a cached file whose stored key differs from the requested one is rebuilt. At 2480 × 1280 expect roughly 4·10⁷ non-zeros; keep memory in mind (no dense `(N, H·W)` intermediates).
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Document** acceptance FWHM 5° (measured, approximate), 3σ cutoff and valid threshold 0.5 (chosen) in `assumptions.md`.
- [ ] **Step 6: Commit** `feat(fly-vision): add cached sparse Gaussian sampling matrix`
- [ ] **Step 7: Milestone M1 (controller).** Sample the mean of the linearised RGB channels of a real capture the user provides and of synthetic dot/grating images through `plot_hex`; show the user; wait for go-ahead.

---

### Task 5: Spectral model

**Files:**
- Create: `packages/fly-vision/src/fly_vision/spectral.py`
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_spectral.py`

**Interfaces:**
- Produces: `RHODOPSIN_PEAKS_NM = {"Rh1": 478, "Rh3": 345, "Rh4": 375, "Rh5": 437, "Rh6": 508}`; `WAVELENGTHS_NM = np.arange(300, 801)`; `govardovskii_a1(peak_nm, wl) -> np.ndarray` (peak-normalised to 1); `PrimaryConfig` (frozen dataclass): `peaks_nm=(610, 545, 465)`, `fwhm_nm=(30, 40, 25)`; `srgb_to_linear(x: np.ndarray) -> np.ndarray` (float in [0, 1]); `SpectralModel` with `catch_matrix: np.ndarray (5, 3)` rows `("Rh1", "Rh3", "Rh4", "Rh5", "Rh6")`, `hash: str`, and `receptors(linear_rgb: np.ndarray (N, 3), subtype: np.ndarray (N,)) -> np.ndarray (N, 3)` in `CHANNELS` order; `build_spectral_model(primaries=PrimaryConfig()) -> SpectralModel`.

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np
from fly_vision.spectral import (
    RHODOPSIN_PEAKS_NM,
    WAVELENGTHS_NM,
    PrimaryConfig,
    build_spectral_model,
    govardovskii_a1,
    srgb_to_linear,
)

M = build_spectral_model()
lin = lambda rgb8: srgb_to_linear(np.array([rgb8], float) / 255)


def test_template_peaks():
    for p in RHODOPSIN_PEAKS_NM.values():
        s = govardovskii_a1(p, WAVELENGTHS_NM)
        assert abs(WAVELENGTHS_NM[np.argmax(s)] - p) <= 2 and np.isclose(s.max(), 1.0, atol=1e-3)


def test_white_normalisation_and_r7_small():
    r = M.receptors(np.ones((2, 3)), np.array(["pale", "yellow"]))
    assert np.allclose(r[:, 0], 1.0)
    assert np.all(r[:, 1] > 0) and np.all(r[:, 1] < 0.25 * r[:, 2])


def test_r7_blue_more_than_honey_and_red():
    sub = np.array(["pale"])
    blue, red, honey = (
        M.receptors(lin(c), sub)[0, 1] for c in [(0, 0, 255), (255, 0, 0), (251, 186, 51)]
    )
    assert blue > honey and blue > red


def test_subtype_mapping():
    x = np.ones((4, 3))
    r = M.receptors(x, np.array(["pale", "yellow", "dorsal_yellow", "dra"]))
    i = {k: n for n, k in enumerate(["Rh1", "Rh3", "Rh4", "Rh5", "Rh6"])}
    c = M.catch_matrix @ np.ones(3) / (M.catch_matrix @ np.ones(3))[i["Rh1"]]
    assert np.allclose(r[0, 1:], [c[i["Rh3"]], c[i["Rh5"]]])
    assert np.allclose(r[1, 1:], [c[i["Rh4"]], c[i["Rh6"]]])
    assert np.allclose(r[2, 1:], [(c[i["Rh3"]] + c[i["Rh4"]]) / 2, c[i["Rh6"]]])
    assert np.allclose(r[3, 1:], [c[i["Rh3"]], c[i["Rh3"]]])


def test_never_negative_or_nan():
    r = M.receptors(np.random.default_rng(0).random((1000, 3)), np.array(["yellow"] * 1000))
    assert np.isfinite(r).all() and (r >= 0).all()


def test_hash_changes_with_primaries():
    assert build_spectral_model(PrimaryConfig(peaks_nm=(610, 545, 455))).hash != M.hash
```

- [ ] **Step 2: Run** `just test fly-vision -k spectral` — Expected: FAIL.
- [ ] **Step 3: Implement.** Govardovskii et al. (2000) A1 template, x = λmax/λ:
  α(x) = 1 / (exp(A(a − x)) + exp(B(b − x)) + exp(C(c − x)) + D), A = 69.7, a = 0.8795 + 0.0459·exp(−(λmax − 300)²/11940), B = 28, b = 0.922, C = −14.9, c = 1.104, D = 0.674;
  β(λ) = 0.26·exp(−((λ − λmβ)/bβ)²), λmβ = 189 + 0.315·λmax, bβ = −40.5 + 0.195·λmax; template = α + β, peak-normalised.
  Primaries: Gaussians from `PrimaryConfig`, each scaled so ∫V·E = sRGB luminance weight (V = Gaussian, peak 555 nm, FWHM 100 nm). `catch_matrix[r, c] = Σ_λ S_r·E_c`, then divide the whole matrix by the Rh1 catch of white (one constant for all receptors). Standard piecewise sRGB linearisation. `hash` = sha256 of `catch_matrix.round(10)` bytes.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Document** template source, primaries and V(λ) approximation (chosen), single-constant normalisation (chosen; ignores differences in absolute receptor sensitivity), and "reflectance-upsampling model: later option" in `assumptions.md`. Verify the five peaks against Salcedo et al. 1999 (J. Neurosci. 19(24):10716–10726) and cite in `spectral.py`; if you cannot access the paper, say so in your report and mark the peaks "provisional, unverified" in `assumptions.md`.
- [ ] **Step 6: Commit** `feat(fly-vision): add RGB to rhodopsin spectral model`

---

### Task 6: Adaptation

**Files:**
- Create: `packages/fly-vision/src/fly_vision/adaptation.py`
- Test: `tests/fly-vision/test_adaptation.py`

**Interfaces:**
- Produces: `adapt(activations: np.ndarray (N, 3), valid: np.ndarray (N,), mode: Literal["none", "frame_mean"]) -> np.ndarray (N, 3)`. `frame_mean`: per channel `y = x / (x + m_c + 1e-12)`, `m_c` = mean of channel c over valid ommatidia; invalid rows returned as 0.

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np
from fly_vision.adaptation import adapt

x = np.random.default_rng(0).random((500, 3)) + 0.01
v = np.ones(500, bool)
v[:50] = False


def test_none_is_identity():
    assert np.array_equal(adapt(x, v, "none"), x)


def test_frame_mean_brightness_invariant():
    assert np.allclose(adapt(x, v, "frame_mean"), adapt(x * 0.01, v, "frame_mean"), atol=1e-6)


def test_frame_mean_range_and_invalid():
    y = adapt(x, v, "frame_mean")
    assert (y[v] >= 0).all() and (y[v] < 1).all() and (y[~v] == 0).all()


def test_uses_only_valid():
    x2 = x.copy()
    x2[~v] = 1e6
    assert np.allclose(adapt(x2, v, "frame_mean")[v], adapt(x, v, "frame_mean")[v])
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** `adapt` as in Interfaces; pure function, no module-level state.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Document** adaptation default `none` and the `frame_mean` form (chosen, experimental; decision 11) in `assumptions.md`.
- [ ] **Step 6: Commit** `feat(fly-vision): add per-frame adaptation`

---

### Task 7: FlyEye, frames, labels and provenance

**Files:**
- Create: `packages/fly-vision/src/fly_vision/frame.py`, `packages/fly-vision/src/fly_vision/eye.py`
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_eye.py`

**Interfaces:**
- Consumes: Tasks 1–6.
- Produces:
  - `SourceRef` dataclass: `scene_id: str`, `frame_id: str`, `image_path: str | None = None`, `capture_meta: dict[str, Any] = {}` (honey-factory metadata such as its sample record, copied verbatim).
  - `FlyFrame` dataclass: `activations (N, 3) float32`, `valid (N,) bool`, `meta: dict`.
  - `FlyLabels` dataclass: `honey_coverage (N,) float32`, `max_coverage: float`, `visibility: Literal["visible_to_fly", "in_image_not_fly", "not_in_image"]`, `meta: dict` (ids only).
  - `save_frame(out_dir, frame, labels | None) -> tuple[Path, Path | None]` writing `<frame_id>.fly.npz` and `<frame_id>.labels.npz` (meta as JSON string); `load_frame(path) -> FlyFrame`; `load_labels(path) -> FlyLabels`.
  - `load_panorama(path) -> np.ndarray` (uint8 or uint16, `(H, W, 3)`; a 16-bit PNG must keep its 16 bits — Pillow truncates 16-bit RGB, so use a reader that does not, or raise `ValueError` for 16-bit files; never truncate silently); `load_mask(path) -> np.ndarray` (`(H, W)` float, 1.0 where the file is `> 0`, else 0.0; honey-factory masks are 0/255).
  - `FlyEye(lattice, projection, cfg=FlyVisionConfig(), spectral=None, cache_dir=None)` with public attributes `lattice`, `projection`, `cfg`, `sm: SamplingMatrix`, `spectral: SpectralModel`; `FlyEye.sample(panorama, mask=None, source=None) -> tuple[FlyFrame, FlyLabels | None]`; `FlyEye.reset() -> None` (no-op in v0, documented as the hook for temporal state).
  - Frame `meta` keys (exact): `fly_vision_version, lattice_version, lattice_hash, matrix_key, spectral_hash, config_hash, adaptation, uv_modelled, channels, input_dtype, input_levels, low_bit_depth, image_sha256, source`.

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np, pytest
from fly_vision.eye import FlyEye
from fly_vision.frame import SourceRef, load_frame, load_labels, save_frame
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import honey_factory_panorama, pixel_directions, to_unit
from fly_vision.sampler import apply_sampling

S = honey_factory_panorama(width=620, height=320)
EYE = FlyEye(generate_hex_lattice(), S)
REF = SourceRef(scene_id="s1", frame_id="f1", capture_meta={"weather": "snow"})


def disc(az0, el0, r):
    az, el = pixel_directions(S)
    return (np.degrees(np.arccos(np.clip(to_unit(az, el) @ to_unit(az0, el0), -1, 1))) < r).astype(
        float
    )


def img(v=128):
    return np.full((S.height, S.width, 3), v, np.uint8)


def test_shapes_types_meta():
    f, lab = EYE.sample(img(), None, REF)
    assert (
        f.activations.shape == (EYE.lattice.ommatidium_id.size, 3)
        and f.activations.dtype == np.float32
    )
    assert (
        lab is None
        and f.meta["uv_modelled"] is False
        and f.meta["source"]["capture_meta"]["weather"] == "snow"
    )
    for k in [
        "lattice_hash",
        "matrix_key",
        "spectral_hash",
        "config_hash",
        "image_sha256",
        "fly_vision_version",
    ]:
        assert isinstance(f.meta[k], str) and f.meta[k]


def test_labels_use_same_matrix():
    m = disc(30, 0, 3)
    _, lab = EYE.sample(img(), m, REF)
    assert np.allclose(
        lab.honey_coverage, apply_sampling(EYE.sm, m[..., None])[:, 0].astype(np.float32)
    )
    assert (lab.honey_coverage >= 0).all() and (lab.honey_coverage <= 1 + 1e-6).all()


def test_visibility_states():
    assert EYE.sample(img(), disc(30, 0, 6), REF)[1].visibility == "visible_to_fly"
    assert EYE.sample(img(), np.zeros((S.height, S.width)), REF)[1].visibility == "not_in_image"
    tiny = disc(30, 0, 0.5)  # a few pixels, far smaller than one cone
    assert EYE.sample(img(), tiny, REF)[1].visibility == "in_image_not_fly"


def test_block_sizes():
    near = EYE.sample(img(), disc(0, 0, 11.3 / 2), REF)[1].honey_coverage  # 1 m block at 5 m
    far = EYE.sample(img(), disc(0, 0, 2.9 / 2), REF)[1].honey_coverage  # 1 m block at 20 m
    assert 2 <= (near >= 0.25).sum() <= 12
    assert 0.05 < far.max() < 0.6


def test_low_bit_depth_and_uint16():
    dark = (np.random.default_rng(0).integers(0, 10, (S.height, S.width, 3))).astype(np.uint8)
    assert EYE.sample(dark, None, REF)[0].meta["low_bit_depth"] is True
    assert EYE.sample(img(), None, REF)[0].meta["low_bit_depth"] is True  # flat image: 1 level
    f16, _ = EYE.sample((img().astype(np.uint16) * 257), None, REF)
    f8, _ = EYE.sample(img(), None, REF)
    assert np.allclose(f16.activations, f8.activations, atol=1e-4)


def test_shape_mismatch_raises():
    with pytest.raises(ValueError):
        EYE.sample(np.zeros((100, 200, 3), np.uint8), None, REF)


def test_determinism_and_roundtrip(tmp_path):
    a, lab = EYE.sample(img(), disc(30, 0, 6), REF)
    b, _ = EYE.sample(img(), disc(30, 0, 6), REF)
    assert np.array_equal(a.activations, b.activations)
    pf, pl = save_frame(tmp_path, a, lab)
    assert pf != pl and np.array_equal(load_frame(pf).activations, a.activations)
    assert load_labels(pl).visibility == "visible_to_fly"
    EYE.reset()


def test_png_loaders(tmp_path):
    from PIL import Image
    from fly_vision.eye import load_mask, load_panorama

    Image.fromarray(img(77)).save(tmp_path / "p.png")
    m = np.zeros((S.height, S.width), np.uint8)
    m[10:20, 10:20] = 255
    Image.fromarray(m).save(tmp_path / "m.png")
    assert load_panorama(tmp_path / "p.png").dtype == np.uint8
    mask = load_mask(tmp_path / "m.png")
    assert mask.shape == (S.height, S.width) and set(np.unique(mask)) == {0.0, 1.0}
```

- [ ] **Step 2: Run** `just test fly-vision -k eye` — Expected: FAIL.
- [ ] **Step 3: Implement.** `FlyEye.__init__` builds `self.sm` via `load_or_build` (or `build_sampling_matrix` when `cache_dir is None`) and `self.spectral = spectral or build_spectral_model()`. `sample`: check shape (raise `ValueError`), record dtype and distinct levels per channel (`low_bit_depth = min(levels) < cfg.low_bit_depth_levels`), scale to [0, 1] by 255 or 65535, `srgb_to_linear`, `apply_sampling`, `spectral.receptors(..., lattice.subtype)`, `adapt(...)`, zero invalid rows, cast float32. Labels: `apply_sampling(self.sm, mask[..., None])`, `max_coverage` over valid rows; visibility per decision 1 using `cfg.visible_coverage_threshold`. `image_sha256` = sha256 of the array bytes.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Document** visible threshold 0.05 and low-bit-depth threshold 32 (chosen) in `assumptions.md`.
- [ ] **Step 6: Commit** `feat(fly-vision): add FlyEye sampling with fly-space labels and provenance`
- [ ] **Step 7: Milestones M2 + M3 (controller).** On the user's real capture (and its mask), plot R1_6, R7, R8 and `honey_coverage`; show the user; wait for go-ahead.

---

### Task 8: Diagnostics

**Files:**
- Create: `packages/fly-vision/src/fly_vision/diagnostics.py`
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_diagnostics.py`

**Interfaces:**
- Produces: `coverage_report(sm, lattice) -> dict` with keys `fraction_ommatidia_valid`, `fraction_image_used` (pixels with non-zero column weight / all pixels), `per_eye_valid` (`{"L": float, "R": float}`); `receptor_table(colors_srgb8: dict[str, tuple[int, int, int]], model) -> dict[str, dict[str, np.ndarray]]` (per colour, per subtype `pale`/`yellow` → `(3,)` receptors); `chromatic_distance(a: np.ndarray, b: np.ndarray, eps=1e-9) -> float` (Euclidean distance between `log(x + eps) − log(x[0] + eps)` vectors, i.e. brightness-free); `metamer_report(colors_srgb8, target="honey", model=None) -> list[tuple[str, float]]` (every colour except `target`, sorted ascending by its minimum distance to `target` across the two subtypes); `r7_blue_sensitivity(shifts_nm=(-10, 0, 10)) -> dict[int, float]` (R7/R8 for white, pale, per blue-peak shift).

- [ ] **Step 1: Write the failing tests**

```python
import numpy as np
from fly_vision.config import FlyVisionConfig
from fly_vision.diagnostics import (
    chromatic_distance,
    coverage_report,
    metamer_report,
    r7_blue_sensitivity,
)
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import honey_factory_panorama
from fly_vision.sampler import build_sampling_matrix

L = generate_hex_lattice()
SM = build_sampling_matrix(L, honey_factory_panorama(620, 320), FlyVisionConfig())


def test_coverage_report():
    r = coverage_report(SM, L)
    assert 0.9 <= r["fraction_ommatidia_valid"] <= 1.0 and 0 < r["fraction_image_used"] <= 1.0
    assert set(r["per_eye_valid"]) == {"L", "R"}


def test_metamers():
    rep = dict(
        metamer_report({"honey": (251, 186, 51), "same": (251, 186, 51), "blue": (40, 60, 220)})
    )
    assert rep["same"] < 1e-9 and rep["blue"] > 0.1


def test_brightness_free_distance():
    a = np.array([1.0, 0.1, 0.5])
    assert chromatic_distance(a, 0.2 * a) < 1e-6


def test_r7_sensitivity_reports():
    s = r7_blue_sensitivity()
    assert set(s) == {-10, 0, 10} and all(np.isfinite(v) and v > 0 for v in s.values())
```

- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: PASS.
- [ ] **Step 5: Run** `uv run python -c "from fly_vision.diagnostics import r7_blue_sensitivity; print(r7_blue_sensitivity())"` and record the values in `assumptions.md` next to the primaries row. Also record `coverage_report` for `honey_factory_panorama(2480, 1280)` and the default lattice.
- [ ] **Step 6: Commit** `feat(fly-vision): add coverage, metamer and spectral-sensitivity diagnostics`

---

### Task 9: CLI, interface doc, calibration test and repo docs

**Files:**
- Create: `packages/fly-vision/src/fly_vision/cli.py`, `packages/fly-vision/docs/interface.md`, `tests/fly-vision/test_cli.py`, `tests/fly-vision/test_calibration.py`, `docs/adr/0016-fly-vision-v0-eye-model.md`
- Modify: `packages/fly-vision/pyproject.toml` (console script `fly-vision = "fly_vision.cli:main"`), `packages/fly-vision/docs/fly-vision-agent-brief.md` (sync decisions 1–11 and amendments A1–A8), `packages/fly-vision/README.md`, `docs/architecture.md`, `docs/adr/README.md`, `tests/README.md`

**Interfaces:**
- Consumes: `FlyEye`, `SourceRef`, `save_frame`, `load_panorama`, `load_mask`, `load_lattice`, `default_lattice`, `honey_factory_panorama`, `EquirectSpec`, `plot_hex`.
- Produces: `main(argv: list[str] | None = None) -> int`; command `fly-vision convert --panorama P [--mask M] [--fov-h DEG --fov-v DEG [--center-az DEG] [--center-el DEG]] [--lattice PATH] --scene-id S --frame-id F --out DIR [--cache DIR] [--plot]`. Without `--fov-h/--fov-v` the projection is `honey_factory_panorama(W, H)` from the image size; `--fov-h` and `--fov-v` must be given together. Without `--lattice`, `default_lattice()`. Writes the two npz files and, with `--plot`, `<frame_id>.png` (R1_6 hex plot).

- [ ] **Step 1: Write the failing tests**

```python
# tests/fly-vision/test_cli.py
import numpy as np
from PIL import Image
from fly_vision.cli import main


def test_convert(tmp_path):
    Image.fromarray(np.full((206, 400, 3), 100, np.uint8)).save(tmp_path / "p.png")
    m = np.zeros((206, 400), np.uint8)
    m[90:110, 190:210] = 255
    Image.fromarray(m).save(tmp_path / "m.png")
    rc = main(
        [
            "convert",
            "--panorama",
            str(tmp_path / "p.png"),
            "--mask",
            str(tmp_path / "m.png"),
            "--scene-id",
            "s",
            "--frame-id",
            "f",
            "--out",
            str(tmp_path / "out"),
            "--plot",
        ]
    )
    assert rc == 0
    for n in ["f.fly.npz", "f.labels.npz", "f.png"]:
        assert (tmp_path / "out" / n).exists()
```

```python
# tests/fly-vision/test_calibration.py
import json, numpy as np, pytest
from pathlib import Path
from fly_vision.eye import FlyEye, load_mask, load_panorama
from fly_vision.lattice import default_lattice
from fly_vision.projection import EquirectSpec, to_unit

FIX = Path(__file__).parent / "fixtures" / "calibration"


@pytest.mark.skipif(
    not (FIX / "targets.json").exists(), reason="calibration capture not provided yet"
)
def test_known_directions():
    # {"fov_h": .., "fov_v": .., "center_el": .., "targets": [{"az": .., "el": ..}]}
    t = json.loads((FIX / "targets.json").read_text())
    pano = load_panorama(FIX / "panorama.png")
    eye = FlyEye(
        default_lattice(),
        EquirectSpec(
            pano.shape[1], pano.shape[0], t["fov_h"], t["fov_v"], center_el_deg=t["center_el"]
        ),
    )
    lab = eye.sample(pano, load_mask(FIX / "mask.png"))[1]
    assert lab is not None
    dirs = to_unit(*eye.lattice.direction_deg.T)
    for tg in t["targets"]:
        near = np.degrees(np.arccos(np.clip(dirs @ to_unit(tg["az"], tg["el"]), -1, 1))) < 5.0
        assert lab.honey_coverage[near].max() >= 0.2, tg
```

- [ ] **Step 2: Run** `just test fly-vision -k "cli or calibration"` — Expected: CLI FAIL, calibration SKIPPED.
- [ ] **Step 3: Implement** `cli.py` with argparse; the mask path is optional.
- [ ] **Step 4: Run** tests, lint, typecheck — Expected: CLI PASS, calibration SKIPPED.
- [ ] **Step 5: Write docs.**
  - `packages/fly-vision/docs/interface.md` for honey-factory: input (equirectangular PNG per `equirectangular-310x160-v1`, linked, not restated; 8-bit sRGB, 16-bit accepted), mask (same projection, `> 0` = honey; honey-factory's visible mask), what fly-vision reads from the sample record (ids, `target.target_status`, conditions; copied verbatim into `meta["source"]`), the calibration request (honey blocks at az/el (0, 0), (90, 0), (−90, 0), (0, 45) at 5 blocks' distance, weather off, daylight, plus `targets.json`; note honey-factory's M5 known-direction gate fixtures may already serve), and the output format with all meta keys.
  - Sync decisions 1–11 and amendments A1–A8 into `fly-vision-agent-brief.md` (answer its open questions 1, 3, 4, 5; mark the brief as superseded where the plan differs).
  - `docs/adr/0016-fly-vision-v0-eye-model.md` (use `docs/adr/template.md`): level contract frame, generated lattice, Gaussian sparse sampler, spectral model with R7 tails, adaptation off, npz frames with provenance. Add it to `docs/adr/README.md`.
  - `docs/architecture.md`: replace the proposed `Scene` / `Eye` / `Receptor` / `VisionMode` / `FlyVision` table with what v0 implements (`FlyEye`, projection, lattice, sampler, spectral) and keep the rest as proposed. Update the opening sentence that says fly-vision is version metadata only.
  - `packages/fly-vision/README.md` (usage: CLI and `FlyEye`) and `tests/README.md` "Current coverage".
- [ ] **Step 6: Commit** `feat(fly-vision): add convert CLI, interface doc and calibration test`
- [ ] **Step 7: Milestone M4 (controller).** Smoke test on real frames the user provides (a day and a night frame): `uv run fly-vision convert --panorama … --scene-id … --frame-id … --out <scratch> --plot`; check the forest structure is recognisable (trees as dark vertical bands, floor below the horizon, forward in the middle of the plot). Print, over valid ommatidia, min / max / std per channel and the size of one 8-bit input step after linearisation and spectral conversion; run both adaptation modes. Record in `assumptions.md` whether R7's variation exceeds that step (decision 10). Show the user.

---

### Task 10 (optional, not scheduled): FlyGym lattice import

**Files:**
- Create: `packages/fly-vision/src/fly_vision/flygym_lattice.py`, `packages/fly-vision/scripts/export_flygym_lattice.py`
- Test: `tests/fly-vision/test_flygym_lattice.py` (skip if `flygym` not installed)

**Interfaces:**
- Consumes: `Lattice`, `validate_lattice`, `save_lattice`, `SUBTYPES`.
- Produces: `load_flygym_lattice() -> Lattice` (directions converted to this package's az/el convention; FlyGym pale/yellow kept; DRA and dorsal third assigned by the Task 3 rules if FlyGym lacks them; `version = "flygym-<flygym version>"`).

- [ ] **Step 1: Write the failing test**: `pytest.importorskip("flygym")`; `L = load_flygym_lattice(); validate_lattice(L); assert 600 <= (L.eye == "R").sum() <= 1000`; and the right eye's mean azimuth is positive (convention check).
- [ ] **Step 2: Run** — Expected: FAIL (or SKIP without flygym).
- [ ] **Step 3: Implement.** First determine which FlyGym version (1.x `flygym-gymnasium` or 2.x) exposes ommatidia directions; record the version and source file in `assumptions.md`.
- [ ] **Step 4: Run** — Expected: PASS. Export to `src/fly_vision/lattices/flygym_v1.npz`; run `coverage_report` against the contract panorama and record the result.
- [ ] **Step 5: Commit** `feat(fly-vision): add optional FlyGym-derived eye lattice`

---

## Out of scope for this plan (tracked for later)

- **Configurable eye resolution (deferred, keep the design open).** Compare the biological eye with hypothetical resolutions. Use two separate settings, not one "n×m degrees": spacing Δφ (`generate_hex_lattice(spacing_deg=…)`, sets the number of inputs, ~1/Δφ²) and acceptance Δρ (`FlyVisionConfig.acceptance_fwhm_deg`, sets blur). Both already exist as parameters in v0; do not hard-code 5° anywhere else. When implemented, add three warnings: aliasing (Δρ/Δφ well below 1), image-limited (Δρ under ~2–3 panorama pixels, ~0.25–0.4° at 0.125°/px), and no connectome match (lattice is not the biological one, so no MaleCNS columns; only baseline models can consume it). Evaluate every resolution against honey-factory's fixed ground truth; the fly-space visibility label is recomputed per configuration. Anisotropic spacing (different horizontal and vertical) is a possible later extension.
- Cube-map and perspective `Projection` adapters (A2 leaves room for them).
- Cross-check against FlyGym's rendered retina; flyvis T4/T5 probe; baseline-model information check (brief section 5, "Later").
- Temporal adaptation across frames (uses `FlyEye.reset()` hook).
- Synthetic UV experiment (`uv_modelled = True`, behind a flag).
- Reflectance-upsampling spectral model.
- MaleCNS `column_id` mapping (phase 2).
