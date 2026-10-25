# Image Placement (Perspective Adapter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let fly-vision sample ordinary photos (pinhole/perspective images, including honey-factory `PERSPECTIVE_90` frames) by declaring where the image sits in the fly's visual field, with the lattice fixed and the placement recorded in every frame.

**Architecture:** A new `PerspectiveSpec` implements the existing `Projection` protocol (pixel → unit ray, pixel solid angle, key), so the sampler, spectral model and labels are unchanged. Placement is an explicit input: a field of view, or object size + distance converted to one. Every frame records its projection as a readable dict in `meta["projection"]`.

**Tech Stack:** Python 3.13 (uv workspace member), numpy, existing fly-vision modules; pytest, ruff, pyright strict.

**Spec:** the Design section below, which records the decisions from the 2026-10-25 discussion. It builds on [ADR 0019](../../../../docs/adr/0019-fly-vision-v0-eye-model.md) ("the lattice is the fly and is fixed; an image is placed into the fly's visual field") and the v0 plan [2026-10-25-fly-vision-v0.md](2026-10-25-fly-vision-v0.md).

**Base:** branch from `feat/fly-vision-v0` (or `main` once v0 is merged). The open v0 residual (sampler `_save` temp-file collision between parallel writers) is independent of this plan and should be fixed first.

## Design

**Agreed (2026-10-25):**
- **D1. The lattice never changes for an input.** The same 1,754 ommatidia, in the same order, for every image.
- **D2. An image is placed, never stretched.** Where it sits and how big it appears are what the photographer knew (camera field of view, or object size and distance), not something fly-vision infers. Its pixels become rays at their true directions. More pixels over the same field change nothing (resolution invariance). A wider field covers more ommatidia because it shows more of the world.
- **D3. The world outside the image is no data.** Uncovered ommatidia are `valid = False`, never filled, padded or extrapolated.

**Decided in this plan (confirm in review):**
- **D4. Pinhole model only.** No lens distortion and no roll. The image's vertical axis stays in the vertical plane through its centre, so the image is level, as honey-factory's camera is in perspective mode with pitch applied.
- **D5. Placement is required for perspective images.** There is no default field of view. The CLI demands exactly one of `--fov-h`, or the three object-geometry flags.
- **D6. Out of scope:** cube maps (honey-factory's cube faces are debug-only), fisheye lenses, stretching an image to fill the eye, and synthetic surrounds or backgrounds.

## Global Constraints

- The lattice is never modified, resampled or subset per input (D1); output is always `(1754, 3)` in lattice order.
- No 2D image operations before sampling (no resize, warp, pad or crop); perspective geometry lives only in `rays()` and `solid_angle()`.
- Head frame and axes unchanged: `to_unit(az, el) = (cos el cos az, cos el sin az, sin el)` = (forward, right, up); azimuth positive right, elevation positive up.
- Image pixel (x, y): x grows to the right, y grows downward, pixel centres at `+ 0.5`.
- No default field of view for perspective images (D5); `honey_factory_panorama` stays the only implicit projection, and only for images whose aspect is within 1% of 310/160 (existing CLI guard).
- Every frame's meta records the projection as a JSON dict (`meta["projection"]`); `Projection.key()` is derived from the same dict so the two cannot drift.
- Repository rules: `just lint`, `just typecheck` (pyright strict, includes `tests/`) and `just test fly-vision` pass before each commit; tests in `tests/fly-vision/`; commits `feat(fly-vision): …` / `docs(fly-vision): …` with the session's Co-Authored-By trailer; never edit `CHANGELOG.md` or versions.
- Every new assumption gets a row in `packages/fly-vision/docs/assumptions.md` in the task that introduces it.

## Review Focus

1. **Mirrored or upside-down perspective rays.** Uniform and centre-pixel tests pass with a flipped x or y. Expected: the top-left pixel looks up-left (az < 0, el > 0 when centred at (0, 0)), and a dot at a known pixel excites the ommatidium pointing there. Pinned: Task 2, `test_perspective_orientation` and `test_perspective_dot_reaches_expected_ommatidium`.
2. **Wrong pixel solid angle at wide fields.** A per-pixel `1/F²` without `cos³θ` biases edge weights. Expected: summed pixel solid angle equals the exact rectangular-pyramid solid angle, including at 120°. Pinned: Task 2, `test_perspective_solid_angle_total`.
3. **Stale cache after moving the image.** Two placements of the same image differing only in `center_az` must not share a sampling matrix. Pinned: Task 2, `test_perspective_key_covers_placement`.
4. **Conflicting or missing placement in the CLI.** Both `--fov-h` and object geometry, partial object flags, or none at all for `--projection perspective`. Expected: exit 1 with one stderr line, no output files. Pinned: Task 4, `test_perspective_placement_errors`.
5. **An image placed where the fly has no ommatidia** (straight behind, centre azimuth 180°). Expected: no crash, every ommatidium `valid = False`, all activations 0. Pinned: Task 2, `test_perspective_behind_fly_is_all_invalid`.

---

### Task 1: Readable projection record in every frame

**Files:**
- Modify: `packages/fly-vision/src/fly_vision/projection.py` (protocol + `EquirectSpec`)
- Modify: `packages/fly-vision/src/fly_vision/eye.py` (frame meta)
- Modify: `packages/fly-vision/docs/interface.md` (meta key list)
- Test: `tests/fly-vision/test_projection.py`, `tests/fly-vision/test_eye.py`

**Interfaces:**
- Produces: `Projection.describe() -> dict[str, str | int | float]`, which holds `"type"` plus every field. `EquirectSpec.describe()` gives `{"type": "equirect", "width", "height", "fov_h_deg", "fov_v_deg", "center_az_deg", "center_el_deg"}`. `key()` returns `json.dumps(self.describe(), sort_keys=True)` for every projection. Frame `meta["projection"]` = `describe()` of the eye's projection, so the frame meta has 16 keys.

- [ ] **Step 1: Write the failing tests**

```python
# tests/fly-vision/test_projection.py
def test_describe_and_key_agree() -> None:
    s = honey_factory_panorama(2480, 1280)
    d = s.describe()
    assert d == {
        "type": "equirect",
        "width": 2480,
        "height": 1280,
        "fov_h_deg": 310.0,
        "fov_v_deg": 160.0,
        "center_az_deg": 0.0,
        "center_el_deg": 10.0,
    }
    assert json.loads(s.key()) == d


# tests/fly-vision/test_eye.py
def test_meta_records_projection() -> None:
    f, _ = EYE.sample(img(), None, REF)
    assert f.meta["projection"] == S.describe()
    assert len(f.meta) == 16
```

- [ ] **Step 2: Run** `just test fly-vision`. Expected: the two new tests FAIL (`describe` missing).
- [ ] **Step 3: Implement.** Add `describe()` to the `Projection` protocol and to `EquirectSpec`; make `key()` return `json.dumps(self.describe(), sort_keys=True)`; add `"projection": self.projection.describe()` to the frame meta in `FlyEye.sample`. Add `projection` to the meta key list in `interface.md`. Changing `key()` changes `matrix_key`, so caches rebuild once; that's expected.
- [ ] **Step 4: Run** `just test fly-vision`, `just lint`, `just typecheck`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(fly-vision): record the projection in frame metadata`

---

### Task 2: `PerspectiveSpec`

**Files:**
- Modify: `packages/fly-vision/src/fly_vision/projection.py`
- Create: `docs/adr/0020-image-placement.md`; Modify: `docs/adr/README.md`
- Modify: `packages/fly-vision/docs/assumptions.md`
- Test: `tests/fly-vision/test_projection.py`, `tests/fly-vision/test_eye.py`

**Interfaces:**
- Consumes: `Projection`, `to_unit` (projection.py); `FlyEye`, `default_lattice`, `apply_sampling` (existing).
- Produces: `PerspectiveSpec` (frozen dataclass, implements `Projection`). Fields: `width: int`, `height: int`, `fov_h_deg: float`, `center_az_deg: float = 0.0`, `center_el_deg: float = 0.0`. Properties: `focal_px: float = (width / 2) / tan(fov_h / 2)`, and `fov_v_deg: float = 2·atan((height / 2) / focal_px)` in degrees. `describe()` gives `{"type": "perspective", "width", "height", "fov_h_deg", "center_az_deg", "center_el_deg"}`.

- [ ] **Step 1: Write the failing tests** in `tests/fly-vision/test_projection.py`. `P = PerspectiveSpec(101, 81, 50.0)` uses odd sizes, so a centre pixel exists.

```python
def test_perspective_protocol_and_centre() -> None:
    p: Projection = P
    r = p.rays()
    assert r.shape == (81, 101, 3) and np.allclose(np.linalg.norm(r, axis=-1), 1.0)
    assert np.allclose(r[40, 50], [1.0, 0.0, 0.0])  # centre pixel looks straight ahead


def test_perspective_orientation() -> None:
    r = P.rays()
    assert r[0, 0, 1] < 0 and r[0, 0, 2] > 0  # top-left: left and up
    assert r[80, 100, 1] > 0 and r[80, 100, 2] < 0  # bottom-right: right and down
    az_edge = np.degrees(np.arctan2(r[40, 0, 1], r[40, 0, 0]))
    assert np.isclose(az_edge, -np.degrees(np.arctan(50.0 / P.focal_px)))  # pixel-centre convention


def test_perspective_solid_angle_total() -> None:
    for fov in (50.0, 120.0):
        p = PerspectiveSpec(400, 300, fov)
        a, b = np.radians(fov / 2), np.radians(p.fov_v_deg / 2)
        exact = 4 * np.arcsin(np.sin(a) * np.sin(b))  # rectangular pyramid
        assert np.isclose(p.solid_angle().sum(), exact, rtol=1e-4)


def test_perspective_rotated_placement() -> None:
    p = PerspectiveSpec(101, 81, 50.0, center_az_deg=30.0, center_el_deg=20.0)
    r = p.rays()
    assert np.allclose(r[40, 50], to_unit(np.array(30.0), np.array(20.0)))
    top = r[0, 50]  # top-centre: same azimuth, higher
    assert np.isclose(np.degrees(np.arctan2(top[1], top[0])), 30.0)
    assert np.degrees(np.arcsin(top[2])) > 20.0


def test_perspective_key_covers_placement() -> None:
    assert P.key() != PerspectiveSpec(101, 81, 50.0, center_az_deg=1.0).key()
    assert P.describe()["type"] == "perspective"


def test_perspective_validation() -> None:
    for bad in (dict(fov_h_deg=0.0), dict(fov_h_deg=180.0), dict(width=0), dict(height=-1)):
        with pytest.raises(ValueError):
            PerspectiveSpec(**{"width": 101, "height": 81, "fov_h_deg": 50.0, **bad})
```

In `tests/fly-vision/test_eye.py`:

```python
def test_perspective_dot_reaches_expected_ommatidium() -> None:
    p = PerspectiveSpec(301, 401, 50.0)
    eye = FlyEye(default_lattice(), p)
    img = np.zeros((401, 301, 3), np.uint8)
    img[100:106, 220:226] = 255  # dot right of centre, above
    f, _ = eye.sample(img)
    target = p.rays()[103, 223]
    hit = int(np.argmax(f.activations[:, 0]))
    d = to_unit(*eye.lattice.direction_deg[hit])
    assert np.degrees(np.arccos(np.clip(d @ target, -1, 1))) < 4.0


def test_perspective_behind_fly_is_all_invalid() -> None:
    p = PerspectiveSpec(101, 81, 50.0, center_az_deg=180.0)
    f, _ = FlyEye(default_lattice(), p).sample(np.full((81, 101, 3), 128, np.uint8))
    assert not f.valid.any() and (f.activations == 0).all()
```

- [ ] **Step 2: Run** `just test fly-vision`. Expected: FAIL (`PerspectiveSpec` missing).
- [ ] **Step 3: Implement `PerspectiveSpec` in `projection.py`.** `__post_init__` raises `ValueError` unless `width > 0`, `height > 0` and `0 < fov_h_deg < 180`. Rays use the camera basis from the placement, then map each pixel offset to a ray:

```text
F = focal_px;  cx = (x + 0.5) − width/2;  cy = (y + 0.5) − height/2     (cy grows downward)
forward = to_unit(center_az, center_el)
right   = to_unit(center_az + 90, 0)
up      = to_unit(center_az, center_el + 90)
ray     = normalise(F·forward + cx·right − cy·up)
Ω_pixel = cos³θ / F²,  cos θ = F / sqrt(F² + cx² + cy²)
```

- [ ] **Step 4: Run** `just test fly-vision`, `just lint`, `just typecheck`. Expected: PASS.
- [ ] **Step 5: Write `docs/adr/0020-image-placement.md`** (template `docs/adr/template.md`) with D1–D6, the pinhole maths reference and the no-data rule, and add it to `docs/adr/README.md`. Add `assumptions.md` rows for: the pinhole model with no distortion or roll (chosen, D4); the outside world as no data (chosen, D3); and a note that a perspective image's field of view is a user-supplied measurement, recorded in `meta["projection"]`.
- [ ] **Step 6: Commit** `feat(fly-vision): add a perspective projection for placed images`

---

### Task 3: Placement from object size and distance

**Files:**
- Create: `packages/fly-vision/src/fly_vision/placement.py`
- Test: `tests/fly-vision/test_placement.py`

**Interfaces:**
- Consumes: `PerspectiveSpec` (Task 2).
- Produces:
  - `angular_size_deg(size_m: float, distance_m: float) -> float`, computed as `2·atan(size / (2·distance))`.
  - `perspective_fov_for_object(image_width_px: int, object_width_px: float, object_size_m: float, distance_m: float) -> float`: the `fov_h_deg` at which an object spanning `object_width_px` pixels around the image centre subtends `angular_size_deg(object_size_m, distance_m)`.
  - Both raise `ValueError` for any non-positive argument.

- [ ] **Step 1: Write the failing tests**

```python
def test_angular_size() -> None:
    assert np.isclose(angular_size_deg(1.0, 5.0), 11.421186, atol=1e-6)  # 1 m block at 5 m


def test_fov_for_object() -> None:
    fov = perspective_fov_for_object(1000, 200, 1.0, 5.0)
    assert np.isclose(fov, 53.130102, atol=1e-6)  # F = 1000 px
    p = PerspectiveSpec(1000, 801, fov)
    r = p.rays()
    span = np.degrees(np.arccos(r[400, 400] @ r[400, 599]))  # 200-px object, centred
    assert np.isclose(span, angular_size_deg(1.0, 5.0), atol=0.1)


def test_placement_validation() -> None:
    for args in (
        (0, 200, 1.0, 5.0),
        (1000, 0, 1.0, 5.0),
        (1000, 200, -1.0, 5.0),
        (1000, 200, 1.0, 0.0),
    ):
        with pytest.raises(ValueError):
            perspective_fov_for_object(*args)
```

- [ ] **Step 2: Run** `just test fly-vision`. Expected: FAIL (module missing).
- [ ] **Step 3: Implement** the two functions in `placement.py`, using `F = (object_width_px / 2) / tan(half angle)`, then `fov_h = 2·atan((image_width_px / 2) / F)`.
- [ ] **Step 4: Run** `just test fly-vision`, `just lint`, `just typecheck`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(fly-vision): derive a photo's field of view from object size and distance`

---

### Task 4: CLI projections and docs

**Files:**
- Modify: `packages/fly-vision/src/fly_vision/cli.py`
- Modify: `packages/fly-vision/README.md`, `packages/fly-vision/docs/interface.md`, `docs/architecture.md`, `tests/README.md`
- Test: `tests/fly-vision/test_cli.py`

**Interfaces:**
- Consumes: `PerspectiveSpec`, `EquirectSpec`, `honey_factory_panorama`, `perspective_fov_for_object`.
- Produces: `fly-vision convert … --projection {panorama,equirect,perspective}`, default `panorama`. Rename `--panorama` to `--image`, and keep `--panorama` as a hidden alias so existing commands still work.
  - `panorama`: today's default path, including its aspect guard. `--fov-*` and `--center-*` are rejected.
  - `equirect`: requires `--fov-h` and `--fov-v`, which is today's explicit path.
  - `perspective`: requires exactly one of `--fov-h` or the full set `--object-width-px`, `--object-size-m`, `--distance-m`. `--fov-v` is rejected because it follows from the aspect ratio. Accepts `--center-az` and `--center-el`.

- [ ] **Step 1: Write the failing tests** in `tests/fly-vision/test_cli.py`.
  - `test_convert_perspective_fov`: a 300×400 photo with `--projection perspective --fov-h 50` gives rc 0, and the frame's `meta["projection"] == PerspectiveSpec(300, 400, 50.0).describe()`.
  - `test_convert_perspective_object`: object flags 120 px, 0.3 m, 1.0 m give rc 0, and `meta["projection"]["fov_h_deg"] == perspective_fov_for_object(300, 120, 0.3, 1.0)`.
  - `test_perspective_placement_errors`: each case gives rc 1, a single-line stderr message and no output directory. The cases are:
    - no placement;
    - both `--fov-h` and object flags;
    - only 2 of the 3 object flags;
    - `--fov-v` given with `--projection perspective`.
  - `test_panorama_alias_still_works`: an existing `--panorama` invocation still gives rc 0.
- [ ] **Step 2: Run** `just test fly-vision`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the argument handling in `cli.py`. Placement validation stays in one function that returns a `Projection` or an error message.
- [ ] **Step 4: Run** `just test fly-vision`, `just lint`, `just typecheck`, `uv run fly-vision convert --help`. Expected: PASS, and the help lists the three projections.
- [ ] **Step 5: Docs.**
  - README: one example per projection, including a phone photo placed by object size and distance.
  - `interface.md`: honey-factory `PERSPECTIVE_90` frames are square 1280×1280 at 90°. Read them with `--projection perspective --fov-h 90 --center-el <−pitch>`, because Minecraft pitch is positive looking down. Azimuth is relative to the camera yaw, so `--center-az` stays 0. Take the pitch from the sample record's camera, and state the sign convention in the doc.
  - `architecture.md`: replace "other projections are proposed" with the perspective adapter and ADR 0020.
  - `tests/README.md`: update coverage.
- [ ] **Step 6: Commit** `feat(fly-vision): place perspective images from the CLI`
