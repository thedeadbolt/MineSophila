from pathlib import Path

import numpy as np
import pytest
from numpy.typing import NDArray

from fly_vision.config import FlyVisionConfig
from fly_vision.lattice import generate_hex_lattice
from fly_vision.projection import (
    EquirectSpec,
    honey_factory_panorama,
    pixel_directions,
    to_unit,
)
from fly_vision.sampler import (
    _save,  # pyright: ignore[reportPrivateUsage]
    apply_sampling,
    build_sampling_matrix,
    load_or_build,
    matrix_key,
)

C, L = FlyVisionConfig(), generate_hex_lattice()
S = honey_factory_panorama(width=620, height=320)  # quarter of the default size, for speed
SM = build_sampling_matrix(L, S, C)


def _unit(az: float, el: float) -> NDArray[np.float64]:
    return to_unit(np.asarray(az, dtype=np.float64), np.asarray(el, dtype=np.float64))


def dot_image(az0: float, el0: float, radius_deg: float = 0.5) -> NDArray[np.float64]:
    az, el = pixel_directions(S)
    ang = np.degrees(np.arccos(np.clip(to_unit(az, el) @ _unit(az0, el0), -1, 1)))
    return (ang < radius_deg).astype(float)[..., None]


def test_rows_normalised_and_invalid_zero() -> None:
    s = np.asarray(SM.W.sum(1)).ravel()
    assert np.allclose(s[SM.valid], 1.0)
    assert np.allclose(s[~SM.valid], 0.0)


def test_uniform_including_poles() -> None:
    out = apply_sampling(SM, np.full((S.height, S.width, 1), 0.7))
    assert np.allclose(out[SM.valid], 0.7, atol=1e-9)
    assert SM.valid[L.direction_deg[:, 1] > 70].all()  # dorsal rows are inside the image


def test_below_image_invalid() -> None:
    spec = EquirectSpec(620, 320, 310.0, 120.0, center_el_deg=30.0)  # bottom edge is -30
    sm = build_sampling_matrix(L, spec, C)
    assert not sm.valid[L.direction_deg[:, 1] < -33].any()


def test_dot_hits_nearest_ommatidium_and_falloff() -> None:
    out = apply_sampling(SM, dot_image(40.0, 10.0))[:, 0]
    d = np.degrees(np.arccos(np.clip(to_unit(*L.direction_deg.T) @ _unit(40.0, 10.0), -1, 1)))
    assert d[np.argmax(out)] < 3.0
    far = (d > 8.0) & SM.valid
    assert out[far].max() < 0.01 * out.max()


def test_no_wrap_at_310() -> None:
    out = apply_sampling(SM, dot_image(-154.0, 0.0))[:, 0]
    assert out[(L.direction_deg[:, 0] > 140) & SM.valid].max() == 0.0


def test_shift_by_one_column() -> None:
    az, el = pixel_directions(S)

    def scene(shift_deg: float) -> NDArray[np.float64]:
        return (
            0.5 + 0.5 * np.cos(2 * np.pi * (az - shift_deg) / 40.0) * np.cos(2 * np.pi * el / 60.0)
        )[..., None]

    r = L.eye == "R"
    row = L.hex_uv[r, 0][np.argmin(np.abs(L.direction_deg[r, 1]))]  # row nearest the horizon
    idx = np.flatnonzero(r & (L.hex_uv[:, 0] == row))
    idx = idx[np.argsort(L.hex_uv[idx, 1])]
    step = float(np.diff(L.direction_deg[idx, 0]).mean())
    a, b = apply_sampling(SM, scene(0.0))[:, 0], apply_sampling(SM, scene(step))[:, 0]
    full = SM.coverage_fraction >= 0.95  # cones cut by the image edge are not shift-equivariant
    ok = full[idx[:-1]] & full[idx[1:]]
    assert ok.sum() > 20
    assert np.allclose(b[idx[1:]][ok], a[idx[:-1]][ok], atol=0.02)
    assert np.abs(b[idx] - a[idx]).max() > 0.1  # the shift is visible


def test_rear_and_reduced_fov_invalid() -> None:
    s90 = EquirectSpec(width=200, height=200, fov_h_deg=90.0, fov_v_deg=90.0)
    sm90 = build_sampling_matrix(L, s90, C)
    assert sm90.W.shape[0] == SM.W.shape[0]
    assert sm90.valid.sum() < SM.valid.sum()
    assert not sm90.valid[np.abs(L.direction_deg[:, 0]) > 60].any()


def test_grating_cutoff() -> None:
    az, _ = pixel_directions(S)
    fine = (np.sin(2 * np.pi * az / 6) > 0).astype(float)[..., None]  # 6° period
    coarse = (np.sin(2 * np.pi * az / 40) > 0).astype(float)[..., None]  # 40° period
    v = SM.valid & (np.abs(L.direction_deg[:, 1]) < 30)
    assert apply_sampling(SM, fine)[v].std() < 0.15
    assert apply_sampling(SM, coarse)[v].std() > 0.3


def test_cache_key_and_shape_guard(tmp_path: Path) -> None:
    assert matrix_key(L, S, C) != matrix_key(L, honey_factory_panorama(1240, 640), C)
    assert matrix_key(L, S, C) != matrix_key(L, S, FlyVisionConfig(acceptance_fwhm_deg=4.0))
    first = load_or_build(L, S, C, tmp_path)
    assert len(list(tmp_path.iterdir())) == 1
    second = load_or_build(L, S, C, tmp_path)
    assert first.key == second.key
    assert (first.W - second.W).count_nonzero() == 0
    with pytest.raises(ValueError, match="image shape"):
        apply_sampling(SM, np.zeros((10, 10, 3)))


def _cache_path(tmp_path: Path) -> Path:
    return tmp_path / f"{matrix_key(L, S, C)}.npz"


def test_truncated_cache_is_rebuilt(tmp_path: Path) -> None:
    load_or_build(L, S, C, tmp_path)
    path = _cache_path(tmp_path)
    path.write_bytes(path.read_bytes()[: path.stat().st_size // 2])
    rebuilt = load_or_build(L, S, C, tmp_path)
    assert (rebuilt.W - SM.W).count_nonzero() == 0
    assert rebuilt.key == SM.key
    assert (load_or_build(L, S, C, tmp_path).W - SM.W).count_nonzero() == 0  # file repaired
    assert [p.name for p in tmp_path.iterdir()] == [path.name]  # no temporary file left


def test_cache_with_other_key_is_rebuilt(tmp_path: Path) -> None:
    load_or_build(L, S, C, tmp_path)
    path = _cache_path(tmp_path)
    with np.load(path, allow_pickle=False) as d:
        arrays = {k: d[k] for k in d.files}
    arrays["key"] = np.array("not-the-key")
    with path.open("wb") as f:
        np.savez(f, **arrays)  # pyright: ignore[reportArgumentType]
    rebuilt = load_or_build(L, S, C, tmp_path)
    assert rebuilt.key == SM.key
    with np.load(path, allow_pickle=False) as d:
        assert str(d["key"]) == SM.key


def test_parallel_writers_do_not_collide(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    path = _cache_path(tmp_path)
    real_savez = np.savez
    interleaved = False

    def savez_with_rival(*args: object, **kwargs: object) -> None:
        nonlocal interleaved
        if not interleaved:  # writer A has opened its temp file; B runs to completion
            interleaved = True
            _save(SM, path)
        real_savez(*args, **kwargs)  # pyright: ignore[reportArgumentType]

    monkeypatch.setattr(np, "savez", savez_with_rival)
    _save(SM, path)
    monkeypatch.undo()
    assert interleaved
    assert (load_or_build(L, S, C, tmp_path).W - SM.W).count_nonzero() == 0
    assert [p.name for p in tmp_path.iterdir()] == [path.name]  # no temporary file left


def test_failed_save_leaves_no_temp_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def failing_savez(*args: object, **kwargs: object) -> None:
        raise OSError("disk full")

    monkeypatch.setattr(np, "savez", failing_savez)
    with pytest.raises(OSError, match="disk full"):
        _save(SM, _cache_path(tmp_path))
    assert list(tmp_path.glob("*.tmp")) == []
    assert list(tmp_path.iterdir()) == []


def test_matrix_key_includes_algorithm(monkeypatch: pytest.MonkeyPatch) -> None:
    before = matrix_key(L, S, C)
    monkeypatch.setattr("fly_vision.sampler._ALGORITHM", "other")
    assert matrix_key(L, S, C) != before
