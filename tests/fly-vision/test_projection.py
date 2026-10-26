import json

import numpy as np
import pytest

from fly_vision.projection import (
    EquirectSpec,
    PerspectiveSpec,
    Projection,
    direction_to_pixel,
    honey_factory_panorama,
    pixel_directions,
    pixel_solid_angle,
    to_unit,
)

S = honey_factory_panorama(width=2480, height=1280)
P = PerspectiveSpec(101, 81, 50.0)


def test_preset_fields() -> None:
    assert (S.fov_h_deg, S.fov_v_deg, S.center_az_deg, S.center_el_deg) == (310.0, 160.0, 0.0, 10.0)


def test_matches_honey_factory_formula() -> None:
    # schemas/honey-factory/README.md, equirectangular-310x160-v1
    az, el = pixel_directions(S)
    for px, py in [(0, 0), (7, 11), (1239, 639), (2479, 1279)]:
        assert np.isclose(az[py, px], -155 + 310 * (px + 0.5) / 2480)
        assert np.isclose(el[py, px], 90 - 160 * (py + 0.5) / 1280)


def test_symmetric_about_forward() -> None:
    az, _ = pixel_directions(S)
    assert np.allclose(az[:, 1239], -az[:, 1240])


def test_solid_angle_matches_band() -> None:
    sa = pixel_solid_angle(S)
    expected = np.deg2rad(310) * (np.sin(np.deg2rad(90)) - np.sin(np.deg2rad(-70)))
    assert np.isclose(sa.sum(), expected, rtol=1e-6)
    assert (sa > 0).all()


def test_roundtrip() -> None:
    taz, tel = np.array([0.0, 90.0, -120.0, 154.0]), np.array([0.0, 30.0, -60.0, 85.0])
    x, y = direction_to_pixel(taz, tel, S)
    az, el = pixel_directions(S)
    xi, yi = np.floor(x).astype(int), np.floor(y).astype(int)
    assert np.allclose(az[yi, xi], taz, atol=0.2)
    assert np.allclose(el[yi, xi], tel, atol=0.2)


def test_to_unit_axes() -> None:
    assert np.allclose(to_unit(np.array(0.0), np.array(0.0)), [1, 0, 0])  # forward
    assert np.allclose(to_unit(np.array(90.0), np.array(0.0)), [0, 1, 0])  # right
    assert np.allclose(to_unit(np.array(0.0), np.array(90.0)), [0, 0, 1])  # up


def test_projection_protocol() -> None:
    p: Projection = S
    r = p.rays()
    assert r.shape == (1280, 2480, 3)
    assert np.allclose(np.linalg.norm(r, axis=-1), 1.0)
    assert S.solid_angle().shape == (1280, 2480)
    assert S.key() != honey_factory_panorama(1240, 640).key()


@pytest.mark.parametrize(
    ("kwargs", "match"),
    [
        ({"width": 0}, "width"),
        ({"height": -1}, "height"),
        ({"fov_h_deg": 0.0}, "fov_h"),
        ({"fov_h_deg": -10.0}, "fov_h"),
        ({"fov_h_deg": 361.0}, "fov_h"),
        ({"fov_v_deg": 0.0}, "fov_v"),
        ({"fov_v_deg": -5.0}, "fov_v"),
        ({"fov_v_deg": 181.0}, "fov_v"),
        ({"fov_v_deg": 180.0, "center_el_deg": 10.0}, "elevation"),
        ({"fov_v_deg": 90.0, "center_el_deg": -50.0}, "elevation"),
    ],
)
def test_equirect_spec_rejects_invalid(kwargs: dict[str, float], match: str) -> None:
    base: dict[str, float] = {"width": 100, "height": 50, "fov_h_deg": 90.0, "fov_v_deg": 90.0}
    with pytest.raises(ValueError, match=match):
        EquirectSpec(**{**base, **kwargs})  # pyright: ignore[reportArgumentType]


def test_equirect_spec_accepts_full_sphere_and_contract() -> None:
    EquirectSpec(10, 5, 360.0, 180.0)
    EquirectSpec(10, 5, 310.0, 160.0, center_el_deg=10.0)


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


def test_perspective_protocol_and_centre() -> None:
    p: Projection = P
    r = p.rays()
    assert r.shape == (81, 101, 3)
    assert np.allclose(np.linalg.norm(r, axis=-1), 1.0)
    assert np.allclose(r[40, 50], [1.0, 0.0, 0.0])  # centre pixel looks straight ahead


def test_perspective_orientation() -> None:
    r = P.rays()
    assert r[0, 0, 1] < 0  # top-left: left and up
    assert r[0, 0, 2] > 0
    assert r[80, 100, 1] > 0  # bottom-right: right and down
    assert r[80, 100, 2] < 0
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
    tilt = 20.0 + np.degrees(np.arctan(40.0 / p.focal_px))  # half a pixel above row 0 is 40
    assert abs(np.degrees(np.arcsin(top[2])) - tilt) < 1e-9


def test_perspective_key_covers_placement() -> None:
    assert P.key() != PerspectiveSpec(101, 81, 50.0, center_az_deg=1.0).key()
    assert P.describe()["type"] == "perspective"


def test_perspective_validation() -> None:
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 0.0)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 180.0)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(0, 81, 50.0)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, -1, 50.0)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 50.0, center_el_deg=90.5)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 50.0, center_el_deg=-91.0)
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 50.0, center_az_deg=float("nan"))
    with pytest.raises(ValueError, match="must be"):
        PerspectiveSpec(101, 81, 50.0, center_el_deg=float("inf"))
    PerspectiveSpec(101, 81, 50.0, center_el_deg=90.0)
    PerspectiveSpec(101, 81, 50.0, center_el_deg=-90.0)


def test_specs_coerce_numpy_and_int_fields() -> None:
    assert (
        PerspectiveSpec(np.int64(300), np.int64(400), np.float32(50)).key()  # pyright: ignore[reportArgumentType]
        == PerspectiveSpec(300, 400, 50.0).key()
    )
    assert PerspectiveSpec(300, 400, 50).key() == PerspectiveSpec(300, 400, 50.0).key()
    assert (
        EquirectSpec(np.int64(8), np.int64(4), np.float32(90), np.float32(45)).key()  # pyright: ignore[reportArgumentType]
        == EquirectSpec(8, 4, 90.0, 45.0).key()
    )
    assert EquirectSpec(8, 4, 90, 45).key() == EquirectSpec(8, 4, 90.0, 45.0).key()


def test_specs_reject_fractional_size() -> None:
    with pytest.raises((TypeError, ValueError)):
        PerspectiveSpec(300.5, 400, 50.0)  # pyright: ignore[reportArgumentType]
    with pytest.raises((TypeError, ValueError)):
        EquirectSpec(8, 4.5, 90.0, 45.0)  # pyright: ignore[reportArgumentType]
