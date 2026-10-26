# fly-vision interface for honey-factory

This note is for the honey-factory side. It lists what fly-vision reads, what it writes, and
one calibration capture we would like to check the geometry on both sides.

fly-vision is a fixed virtual fly eye. It takes a panorama and a honey mask, samples both
through the same ommatidial sampling matrix, and writes per-ommatidium arrays. It does no
detection or classification.

## Input

### Panorama

One equirectangular PNG per frame, in the `equirectangular-310x160-v1` layout of the
[panoramic capture contract](../../../schemas/honey-factory/README.md#equirectangular-310x160-v1-and-cube-faces-v1-panoramic-capture)
(ADR 0010). That section is the reference; this note does not restate it. fly-vision reads
the image size from the file and applies the contract's field of view (longitude −155…+155°,
elevation −70…+90°, so the image centre is at elevation +10°).

- 8-bit RGB (alpha is dropped). The default 2480 × 1280 is fine; any size works.
- Values are treated as sRGB and linearised.
- The EXIF Orientation tag is applied to the image and the mask on load, so a photo stored
  sideways is sampled upright.
- 16-bit sRGB would be preferable at night, when few distinct levels are used. The Python
  API (`FlyEye.sample`) accepts `uint16` arrays, but `load_panorama` and the CLI reject
  16-bit PNG files for now because Pillow would silently truncate them to 8 bits.
- Weather, lighting and biome are left as rendered. fly-vision never corrects them.
- The panorama is never resized, filtered or cropped as an image. Every operation happens
  after turning pixels into directions.

### Mask

A single-channel PNG of the same size and projection as the panorama: honey-factory's
`masks/<split>/<shard>/<sample>.png` (8-bit gray, 0/255). A pixel is honey when its value is
`> 0`. Use honey-factory's visible mask (the one inside the occlusion). It goes
through the same sampling matrix as the colours and comes out as `honey_coverage`.

### Identifiers and provenance

`scene_id` and `frame_id` identify the frame; `frame_id` names the output files. When
fly-vision is called from Python, it also takes the sample record (`SourceRef.capture_meta`)
and copies it verbatim into `meta["source"]["capture_meta"]`. We use these fields from it to
split results later, and we never edit them:

- `sample_id`, `scene_id`, `split`, `viewpoint_index`;
- `target.target_status` (`absent`, `out_of_view`, `subpixel`, `fully_occluded`,
  `fully_visible`, `partially_visible`);
- `conditions` (weather, lighting and the like).

The command line records only `scene_id`, `frame_id` and the image path, and adds
`source_file_sha256` (the hash of the PNG file's bytes).

### Projections and placement

`fly-vision convert --projection` selects `panorama`, `equirect` or `perspective`. When it is
omitted, any `--fov-h`/`--fov-v` selects `equirect`; otherwise it is `panorama`. With
`panorama`, the image is read as a `PANORAMA_310` capture and the command exits with status 1
if its aspect ratio differs from 310/160 by more than 1%. An explicit `--projection panorama`
rejects `--fov-*` and `--center-*`; without it, `--fov-*` selects `equirect` and `--center-*`
alone is rejected. `equirect` needs `--fov-h` and `--fov-v` (`--center-az` and `--center-el`
optional). `perspective` takes `--fov-h`, or `--object-width-px`, `--object-size-m` and
`--distance-m`, or both (they must agree within 1°), or neither (60° assumed, with a
warning). `--fov-v` is rejected because it follows from the aspect ratio.

honey-factory's `PERSPECTIVE_90` frames are 90° wide at any resolution and aspect (the
default capture is square, 1280 × 1280). Read them with
`--projection perspective --fov-h 90 --center-el <-pitch>`. Minecraft pitch is positive
looking down, while fly-vision elevation is positive up, hence the sign flip. Azimuth is
relative to the camera yaw, so `--center-az` stays 0. Take the pitch from the camera in the
sample record.

**Image size.** Images are sampled at full resolution; there is no resizing, by design.
Building a sampling matrix for a 3 MP photo (2000 × 1500) takes about 13 s and about 1.4 GB of
RAM, and both grow roughly with the pixel count (a 12 MP photo needs about 5 to 6 GB). Pass
`--cache` to build the matrix once per image size and projection.

A lattice file passed with `--lattice` is validated on load; a corrupt or inconsistent file
gives a one-line error.

## Output

Per frame, written to a chosen directory with `fly-vision convert`:

| File | Content |
|---|---|
| `<frame_id>.fly.npz` | model input: `activations`, `valid`, `meta` |
| `<frame_id>.labels.npz` | `honey_coverage`, `meta` (only when a mask is given) |
| `<frame_id>.png` | optional debug hex plot of R1_6 (`--plot`) |

Inputs and labels are in separate files on purpose. Arrays are indexed by ommatidium in the
lattice order (default lattice: 863 per eye).

`.fly.npz`:

- `activations`: float32, `(N, 3)`, channel order `R1_6`, `R7`, `R8`. Invalid rows are 0.
- `valid`: bool, `(N,)`. False when too little of the ommatidium's cone falls inside the
  image.
- `meta`: JSON string with the keys below.

| `meta` key | Meaning |
|---|---|
| `fly_vision_version` | installed package version |
| `lattice_version`, `lattice_hash` | which eye and its content hash |
| `matrix_key` | identifies the sampling matrix (lattice, projection, acceptance settings) |
| `projection` | the projection as a JSON dict: `type` plus every field (equirect: `width`, `height`, `fov_h_deg`, `fov_v_deg`, `center_az_deg`, `center_el_deg`; perspective: `width`, `height`, `fov_h_deg`, `center_az_deg`, `center_el_deg`) |
| `placement` | how the image was placed, or `null` when the caller gave none. `method` is `honey_factory_contract` (panorama), `fov` (equirect), `fov_h`, `object`, `fov_h+object` or `default` (perspective); `assumed` is `true` only for `default` (60°). `object` and `fov_h+object` add `object_width_px`, `object_size_m`, `distance_m`; `fov_h+object` also `object_fov_h_deg` |
| `spectral_hash` | hash of the spectral model (rhodopsin peaks, RGB primaries) |
| `config_hash` | hash of the run configuration and package version |
| `adaptation` | `none` (default) or `frame_mean` (experimental) |
| `uv_modelled` | always `false`: RGB has no UV; R7 comes from the visible tails of Rh3/Rh4 |
| `channels` | `["R1_6", "R7", "R8"]` |
| `input_dtype` | `uint8` or `uint16` |
| `input_levels` | number of distinct values in each colour channel |
| `low_bit_depth` | true when any channel has fewer than 32 distinct values |
| `image_sha256` | SHA-256 of the pixel array |
| `source_file_sha256` | SHA-256 of the source image file's bytes (joins frames to honey-factory sample records, which hash the PNG); `null` when there is no readable `image_path` |
| `source` | `{scene_id, frame_id, image_path, capture_meta}` or `null` |

`.labels.npz`:

- `honey_coverage`: float32, `(N,)`, in [0, 1]: the honey mask through the same sampling
  matrix as the colours. This is a transformation only; fly-vision does not decide whether
  honey is "visible".
- `meta`: `scene_id`, `frame_id`, `image_sha256`, plus `fly_vision_version`,
  `lattice_hash`, `matrix_key` (the same values as the frame's) and `mask_sha256` (SHA-256
  of the validated float64 mask array).

Your own `target_status` stays in `meta["source"]`, untouched.

## Calibration capture

Orientation and sign mistakes on either side would silently shift everything, so we
would like one capture with honey at known directions. Honey-factory's M5 known-direction
gate fixtures may already serve; if so, please point us to them.

Request:

- Honey blocks centred at azimuth / elevation (0, 0), (60, 0), (−90, 0), (0, 45) and
  (−45, 20) degrees, each 5 blocks from the camera. Azimuth is positive to the right, elevation
  positive up, in the level frame of the contract.
- Weather off, daylight.
- A panorama PNG, the matching visible mask PNG, and a `targets.json`:

```json
{
  "fov_h": 310,
  "fov_v": 160,
  "center_el": 10,
  "targets": [
    {"az": 0, "el": 0},
    {"az": 60, "el": 0},
    {"az": -90, "el": 0},
    {"az": 0, "el": 45},
    {"az": -45, "el": 20}
  ]
}
```

Place the files in `tests/fly-vision/fixtures/calibration/` as `panorama.png`, `mask.png`
and `targets.json`. `tests/fly-vision/test_calibration.py` builds the eye from the panorama
size with the `equirectangular-310x160-v1` preset (the path the CLI uses) and requires
`targets.json` to agree with it. For each target, the coverage-weighted centroid of the valid
ommatidia within 8° must lie within 2° of the target, and (for |azimuth| of 10° or more)
the coverage within 5° of the azimuth-mirrored position must stay below 0.05. The targets are
asymmetric in azimuth on purpose. This catches sign flips and offsets of about 2° or more;
it does not resolve half-pixel errors. The test skips until the files exist; a synthetic
capture exercises the same check meanwhile.
