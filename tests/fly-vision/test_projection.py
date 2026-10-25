import numpy as np

from fly_vision.projection import (
    direction_to_pixel,
    honey_factory_panorama,
    pixel_directions,
    pixel_solid_angle,
    to_unit,
)

S = honey_factory_panorama(width=2480, height=1280)


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
    r = S.rays()
    assert r.shape == (1280, 2480, 3)
    assert np.allclose(np.linalg.norm(r, axis=-1), 1.0)
    assert S.solid_angle().shape == (1280, 2480)
    assert S.key() != honey_factory_panorama(1240, 640).key()
