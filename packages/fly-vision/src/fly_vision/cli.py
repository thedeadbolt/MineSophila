"""Command line: `fly-vision convert` turns a panorama (and mask) into fly-space files."""

import argparse
import sys
import zipfile
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt

from fly_vision.eye import FlyEye
from fly_vision.frame import SourceRef, load_mask, load_panorama, save_frame
from fly_vision.lattice import default_lattice, load_lattice
from fly_vision.placement import (
    DEFAULT_FOV_H_DEG,
    FOV_AGREEMENT_DEG,
    perspective_fov_for_object,
)
from fly_vision.plot import plot_hex
from fly_vision.projection import (
    EquirectSpec,
    PerspectiveSpec,
    Projection,
    honey_factory_panorama,
)

_ASPECT_TOLERANCE = 0.01  # relative; the default path expects a honey-factory capture


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fly-vision", description="Virtual Drosophila eye.")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser(
        "convert",
        help="convert an image (and optional honey mask) into fly-space files",
        description=(
            "Projections: panorama (default) reads honey-factory's equirectangular-310x160-v1 "
            "(level, longitude -155..+155, elevation -70..+90); equirect needs --fov-h and "
            "--fov-v; perspective reads a pinhole photo. Perspective placement is optional: "
            "give --fov-h, or all of --object-width-px/--object-size-m/--distance-m, or both "
            f"(they must agree within {FOV_AGREEMENT_DEG:g} deg). With neither, the field of "
            f"view defaults to {DEFAULT_FOV_H_DEG:g} deg, a warning is printed and the frame "
            "is marked assumed."
        ),
    )
    src = c.add_mutually_exclusive_group(required=True)
    src.add_argument("--image", type=Path, help="input PNG (8-bit RGB)")
    src.add_argument("--panorama", dest="image", type=Path, help=argparse.SUPPRESS)
    c.add_argument(
        "--projection",
        choices=["panorama", "equirect", "perspective"],
        help="how pixels map to directions (default: panorama; equirect if --fov-* given)",
    )
    c.add_argument("--mask", type=Path, help="honey mask, same size as the image (> 0 = honey)")
    c.add_argument("--fov-h", type=float, metavar="DEG", help="horizontal field of view")
    c.add_argument("--fov-v", type=float, metavar="DEG", help="vertical field of view (equirect)")
    c.add_argument("--center-az", type=float, default=0.0, metavar="DEG")
    c.add_argument("--center-el", type=float, default=0.0, metavar="DEG")
    c.add_argument("--object-width-px", type=float, metavar="PX", help="perspective: object span")
    c.add_argument("--object-size-m", type=float, metavar="M", help="perspective: object size")
    c.add_argument("--distance-m", type=float, metavar="M", help="perspective: camera distance")
    c.add_argument("--lattice", type=Path, help="lattice .npz (default: the generated lattice)")
    c.add_argument("--scene-id", required=True)
    c.add_argument("--frame-id", required=True, help="names the output files")
    c.add_argument("--out", required=True, type=Path, help="output directory")
    c.add_argument("--cache", type=Path, help="directory for cached sampling matrices")
    c.add_argument("--plot", action="store_true", help="also write <frame_id>.png (R1_6 hex plot)")
    return parser


Placement = dict[str, str | bool | float]


def _resolve(args: argparse.Namespace, w: int, h: int) -> tuple[Projection, Placement]:
    """Projection and placement record for the arguments; ValueError on contradictions."""
    has_fov = args.fov_h is not None or args.fov_v is not None
    kind: str = args.projection or ("equirect" if has_fov else "panorama")
    objs = {
        "object_width_px": args.object_width_px,
        "object_size_m": args.object_size_m,
        "distance_m": args.distance_m,
    }
    if kind != "perspective" and any(v is not None for v in objs.values()):
        raise ValueError(
            "--object-width-px/--object-size-m/--distance-m need --projection perspective"
        )
    if kind == "panorama":
        if (
            args.fov_h is not None
            or args.fov_v is not None
            or args.center_az != 0.0
            or args.center_el != 0.0
        ):
            raise ValueError(
                "--fov-h/--fov-v/--center-az/--center-el need --projection equirect or "
                "--projection perspective (the panorama contract fixes the field and centre)"
            )
        projection = honey_factory_panorama(w, h)
        contract_aspect = projection.fov_h_deg / projection.fov_v_deg
        if abs(w / h / contract_aspect - 1.0) > _ASPECT_TOLERANCE:
            raise ValueError(
                f"the image is {w}x{h} and does not look like a PANORAMA_310 capture "
                f"(aspect {contract_aspect:.4f} within 1%); pass --fov-h and --fov-v "
                "for other equirectangular images or --projection perspective for photos"
            )
        return projection, {"method": "honey_factory_contract", "assumed": False}
    if kind == "equirect":
        if args.fov_h is None or args.fov_v is None:
            raise ValueError("equirect needs both --fov-h and --fov-v")
        projection = EquirectSpec(w, h, args.fov_h, args.fov_v, args.center_az, args.center_el)
        return projection, {"method": "fov", "assumed": False}
    if args.fov_v is not None:
        raise ValueError("--fov-v is not used by perspective (it follows from the aspect ratio)")
    given = [k for k, v in objs.items() if v is not None]
    if given and len(given) != 3:
        raise ValueError(
            "the object flags go together: also give "
            + ", ".join("--" + k.replace("_", "-") for k in objs if k not in given)
        )
    placement: Placement
    fov: float
    if given:
        derived = perspective_fov_for_object(
            w, args.object_width_px, args.object_size_m, args.distance_m
        )
        object_fields: Placement = {k: float(v) for k, v in objs.items()}
        if args.fov_h is None:
            fov = derived
            placement = {"method": "object", "assumed": False, **object_fields}
        else:
            if abs(derived - args.fov_h) > FOV_AGREEMENT_DEG:
                raise ValueError(
                    f"--fov-h {args.fov_h:g} deg and the object geometry ({derived:.2f} deg) "
                    f"disagree by more than {FOV_AGREEMENT_DEG:g} deg"
                )
            fov = args.fov_h
            placement = {
                "method": "fov_h+object",
                "assumed": False,
                **object_fields,
                "object_fov_h_deg": derived,
            }
    elif args.fov_h is not None:
        fov = args.fov_h
        placement = {"method": "fov_h", "assumed": False}
    else:
        fov = DEFAULT_FOV_H_DEG
        placement = {"method": "default", "assumed": True}
    spec = PerspectiveSpec(w, h, fov, args.center_az, args.center_el)
    if placement["assumed"]:
        print(
            f"fly-vision: warning: no placement given; assuming a {DEFAULT_FOV_H_DEG:g} deg "
            "horizontal field of view (pass --fov-h or the object flags)",
            file=sys.stderr,
        )
    return spec, placement


def _convert(args: argparse.Namespace) -> int:
    try:
        pano = load_panorama(args.image)
        mask = None if args.mask is None else load_mask(args.mask)
        if mask is not None and mask.shape != pano.shape[:2]:
            raise ValueError(
                f"mask shape {mask.shape} does not match panorama shape {pano.shape[:2]}"
            )
        h, w = pano.shape[:2]
        projection, placement = _resolve(args, w, h)
        lattice = default_lattice() if args.lattice is None else load_lattice(args.lattice)
        eye = FlyEye(lattice, projection, cache_dir=args.cache)
        source = SourceRef(
            scene_id=args.scene_id, frame_id=args.frame_id, image_path=str(args.image)
        )
        frame, labels = eye.sample(pano, mask, source, placement)
        if not frame.valid.any():
            print(
                "fly-vision: note: no ommatidium sees this image (check its placement)",
                file=sys.stderr,
            )
        fly_path, labels_path = save_frame(args.out, frame, labels)
        print(fly_path)
        if labels_path is not None:
            print(labels_path)
        if args.plot:
            fig = plot_hex(lattice, frame.activations[:, 0], frame.valid, f"{args.frame_id}: R1_6")
            png = args.out / f"{args.frame_id}.png"
            try:
                fig.savefig(png, dpi=120)  # pyright: ignore[reportUnknownMemberType]
            finally:
                plt.close(fig)
            print(png)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as e:
        print(f"fly-vision: error: {e}", file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    if args.command == "convert":
        return _convert(args)
    return 2  # pragma: no cover


if __name__ == "__main__":
    raise SystemExit(main())
