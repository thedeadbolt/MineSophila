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
    # Between the row spacing d*sqrt(3)/2 (4.33) and the in-row spacing d (5): away from the
    # equator the half-step offsets of adjacent rows drift, so the nearest neighbour is often
    # almost directly above or below.
    assert 4.3 <= np.median(ang.min(1)) <= 5.1


def test_coverage_ranges() -> None:
    az = L.direction_deg[:, 0]
    el = L.direction_deg[:, 1]
    assert az[L.eye == "R"].min() >= -10 - 1e-9
    assert az[L.eye == "R"].max() <= 155 + 1e-9
    assert az[L.eye == "L"].max() <= 10 + 1e-9
    assert az[L.eye == "L"].min() >= -155 - 1e-9
    assert el.min() >= -70 - 1e-9
    assert el.max() <= 90 + 1e-9


def test_top_reaches_near_zenith() -> None:
    el = L.direction_deg[:, 1]
    for e in "LR":
        assert el[L.eye == e].max() > 84  # the 5 degree cone then reaches the zenith


def test_columns_increase_with_azimuth() -> None:
    for e in "LR":
        m = L.eye == e
        for row in np.unique(L.hex_uv[m, 0]):
            r = m & (L.hex_uv[:, 0] == row)
            order = np.argsort(L.hex_uv[r, 1])
            assert np.all(np.diff(L.direction_deg[r, 0][order]) > 0)


def test_subtypes() -> None:
    assert set(L.subtype) <= {"pale", "yellow", "dorsal_yellow", "dra"}
    main = L.subtype != "dra"
    assert 0.25 <= (L.subtype[main] == "pale").mean() <= 0.35
    assert (L.subtype == "dorsal_yellow").any()
    el = L.direction_deg[:, 1]
    dra = L.subtype == "dra"
    assert (el[dra] >= 70).all()
    assert dra[el >= 70].all()
    for e in "LR":
        assert 15 <= (dra & (L.eye == e)).sum() <= 40


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
