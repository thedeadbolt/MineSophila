import dataclasses
from pathlib import Path

import numpy as np
import pytest

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


def test_counts_per_eye() -> None:
    for e in "LR":
        assert 600 <= (L.eye == e).sum() <= 1000


def test_neighbour_spacing() -> None:
    u = to_unit(L.direction_deg[:, 0], L.direction_deg[:, 1])[L.eye == "R"]
    ang = np.degrees(np.arccos(np.clip(u @ u.T, -1, 1)))
    np.fill_diagonal(ang, 1e9)
    assert np.allclose(np.median(ang.min(1)), 5.0, atol=0.5)


def test_coverage_ranges() -> None:
    az = L.direction_deg[:, 0]
    assert az[L.eye == "R"].min() >= -15 - 1e-9
    assert az[L.eye == "R"].max() <= 155 + 1e-9
    assert az[L.eye == "L"].max() <= 15 + 1e-9
    assert az[L.eye == "L"].min() >= -155 - 1e-9


def test_columns_increase_with_azimuth() -> None:
    for e in "LR":
        m = L.eye == e
        for row in np.unique(L.hex_uv[m, 0]):
            r = m & (L.hex_uv[:, 0] == row)
            order = np.argsort(L.hex_uv[r, 1])
            assert np.all(np.diff(L.direction_deg[r, 0][order]) > 0)


def test_subtypes() -> None:
    assert set(L.subtype) <= {"pale", "yellow", "dorsal_yellow", "dra"}
    main = np.isin(L.subtype, ["pale", "yellow", "dorsal_yellow"])
    assert 0.25 <= (L.subtype[main] == "pale").mean() <= 0.35
    assert (L.subtype == "dra").any()
    assert (L.subtype == "dorsal_yellow").any()
    el = L.direction_deg[:, 1]
    assert el[L.subtype == "dra"].min() > el[L.subtype != "dra"].mean()


def test_seed_determinism_and_roundtrip(tmp_path: Path) -> None:
    assert lattice_hash(generate_hex_lattice(seed=0)) == lattice_hash(L)
    assert lattice_hash(generate_hex_lattice(seed=1)) != lattice_hash(L)
    save_lattice(L, tmp_path / "l.npz")
    loaded = load_lattice(tmp_path / "l.npz")
    validate_lattice(loaded)
    assert lattice_hash(loaded) == lattice_hash(L)


def test_packaged_lattice_matches_generator() -> None:
    validate_lattice(default_lattice())
    assert lattice_hash(default_lattice()) == lattice_hash(L)


def test_load_lattice_validates(tmp_path: Path) -> None:
    subtype = L.subtype.copy()
    subtype[0] = "bogus"
    save_lattice(dataclasses.replace(L, subtype=subtype), tmp_path / "bad.npz")
    with pytest.raises(ValueError, match="subtype"):
        load_lattice(tmp_path / "bad.npz")
