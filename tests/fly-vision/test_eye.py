import struct
import zlib
from pathlib import Path

import numpy as np
import pytest
from numpy.typing import NDArray
from PIL import Image

from fly_vision.eye import FlyEye, load_mask, load_panorama
from fly_vision.frame import SourceRef, load_frame, load_labels, save_frame
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import honey_factory_panorama, pixel_directions, to_unit
from fly_vision.sampler import apply_sampling

S = honey_factory_panorama(width=620, height=320)
EYE = FlyEye(generate_hex_lattice(), S)
REF = SourceRef(scene_id="s1", frame_id="f1", capture_meta={"weather": "snow"})


def disc(az0: float, el0: float, r: float) -> NDArray[np.float64]:
    az, el = pixel_directions(S)
    centre = to_unit(np.array(az0), np.array(el0))
    angle = np.degrees(np.arccos(np.clip(to_unit(az, el) @ centre, -1, 1)))
    return (angle < r).astype(float)


def img(v: int = 128) -> NDArray[np.uint8]:
    return np.full((S.height, S.width, 3), v, np.uint8)


def test_shapes_types_meta() -> None:
    f, lab = EYE.sample(img(), None, REF)
    assert f.activations.shape == (EYE.lattice.ommatidium_id.size, 3)
    assert f.activations.dtype == np.float32
    assert lab is None
    assert f.meta["uv_modelled"] is False
    assert f.meta["source"]["capture_meta"]["weather"] == "snow"
    for k in [
        "lattice_hash",
        "matrix_key",
        "spectral_hash",
        "config_hash",
        "image_sha256",
        "fly_vision_version",
    ]:
        assert isinstance(f.meta[k], str)
        assert f.meta[k]


def test_labels_use_same_matrix() -> None:
    m = disc(30, 0, 3)
    _, lab = EYE.sample(img(), m, REF)
    assert lab is not None
    assert np.allclose(
        lab.honey_coverage, apply_sampling(EYE.sm, m[..., None])[:, 0].astype(np.float32)
    )
    assert (lab.honey_coverage >= 0).all()
    assert (lab.honey_coverage <= 1 + 1e-6).all()


def test_coverage_reflects_what_the_fly_sees() -> None:
    def cov(mask: NDArray[np.float64]) -> NDArray[np.float32]:
        _, lab = EYE.sample(img(), mask, REF)
        assert lab is not None
        return lab.honey_coverage

    assert cov(disc(30, 0, 6)).max() > 0.5
    assert not cov(np.zeros((S.height, S.width))).any()
    tiny = cov(disc(30, 0, 0.5))  # a few pixels, far smaller than one cone
    assert 0 < tiny.max() < 0.05
    edge = cov(disc(0, -69, 1))  # at the panorama's bottom edge
    assert edge[EYE.sm.valid].max() < 0.2


def test_block_sizes() -> None:
    def cov(r: float) -> NDArray[np.float32]:
        _, lab = EYE.sample(img(), disc(0, 0, r), REF)
        assert lab is not None
        return lab.honey_coverage

    near = cov(11.3 / 2)  # 1 m block at 5 m
    far = cov(2.9 / 2)  # 1 m block at 20 m
    assert 2 <= (near >= 0.25).sum() <= 12
    assert 0.05 < far.max() < 0.6


def test_low_bit_depth_and_uint16() -> None:
    dark = (np.random.default_rng(0).integers(0, 10, (S.height, S.width, 3))).astype(np.uint8)
    assert EYE.sample(dark, None, REF)[0].meta["low_bit_depth"] is True
    assert EYE.sample(img(), None, REF)[0].meta["low_bit_depth"] is True  # flat image: 1 level
    f16, _ = EYE.sample((img().astype(np.uint16) * 257), None, REF)
    f8, _ = EYE.sample(img(), None, REF)
    assert np.allclose(f16.activations, f8.activations, atol=1e-4)


def test_shape_mismatch_raises() -> None:
    with pytest.raises(ValueError, match="shape"):
        EYE.sample(np.zeros((100, 200, 3), np.uint8), None, REF)


def test_determinism_and_roundtrip(tmp_path: Path) -> None:
    a, lab = EYE.sample(img(), disc(30, 0, 6), REF)
    b, _ = EYE.sample(img(), disc(30, 0, 6), REF)
    assert lab is not None
    assert np.array_equal(a.activations, b.activations)
    pf, pl = save_frame(tmp_path, a, lab)
    assert pl is not None
    assert pf != pl
    loaded = load_frame(pf)
    assert np.array_equal(loaded.activations, a.activations)
    assert loaded.meta == a.meta
    assert np.array_equal(load_labels(pl).honey_coverage, lab.honey_coverage)
    EYE.reset()


def test_png_loaders(tmp_path: Path) -> None:
    Image.fromarray(img(77)).save(tmp_path / "p.png")
    m = np.zeros((S.height, S.width), np.uint8)
    m[10:20, 10:20] = 255
    Image.fromarray(m).save(tmp_path / "m.png")
    assert load_panorama(tmp_path / "p.png").dtype == np.uint8
    mask = load_mask(tmp_path / "m.png")
    assert mask.shape == (S.height, S.width)
    assert set(np.unique(mask)) == {0.0, 1.0}


def test_no_source_and_save_needs_frame_id(tmp_path: Path) -> None:
    f, lab = EYE.sample(img(), disc(30, 0, 6), None)
    assert lab is not None
    assert f.meta["source"] is None
    assert lab.meta["frame_id"] is None
    with pytest.raises(ValueError, match="frame_id"):
        save_frame(tmp_path, f, lab)


def test_loaders_reject_and_convert(tmp_path: Path) -> None:
    Image.fromarray(img(77)).save(tmp_path / "p.jpg")
    assert load_panorama(tmp_path / "p.jpg").shape == (S.height, S.width, 3)
    Image.fromarray(img(77)).convert("RGBA").save(tmp_path / "a.png")
    assert load_panorama(tmp_path / "a.png").shape == (S.height, S.width, 3)
    Image.fromarray(img(77)[..., 0]).save(tmp_path / "g.png")
    with pytest.raises(ValueError, match="RGB"):
        load_panorama(tmp_path / "g.png")

    # 16-bit RGB PNG: hand-built, must raise rather than truncate
    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    raw = b"".join(b"\x00" + bytes(2 * 3 * 4) for _ in range(4))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 4, 4, 16, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )
    (tmp_path / "w.png").write_bytes(png)
    with pytest.raises(ValueError, match="16-bit"):
        load_panorama(tmp_path / "w.png")


def test_mask_validation() -> None:
    bad_nan = disc(30, 0, 6)
    bad_nan[5, 5] = np.nan
    with pytest.raises(ValueError, match="NaN"):
        EYE.sample(img(), bad_nan, REF)
    with pytest.raises(ValueError, match="load_mask"):
        EYE.sample(img(), disc(30, 0, 6) * 255, REF)
    with pytest.raises(ValueError, match="2-D"):
        EYE.sample(img(), np.zeros((S.height, S.width, 3)), REF)
