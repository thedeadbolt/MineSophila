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
from fly_vision.plot import plot_hex
from fly_vision.projection import EquirectSpec, Projection, honey_factory_panorama

_ASPECT_TOLERANCE = 0.01  # relative; the default path expects a honey-factory capture


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fly-vision", description="Virtual Drosophila eye.")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser(
        "convert",
        help="convert a panorama (and optional honey mask) into fly-space files",
        description=(
            "Without --fov-h/--fov-v the panorama is read as honey-factory's "
            "equirectangular-310x160-v1 (level, longitude -155..+155, elevation -70..+90)."
        ),
    )
    c.add_argument("--panorama", required=True, type=Path, help="equirectangular PNG (8-bit RGB)")
    c.add_argument("--mask", type=Path, help="honey mask, same size as the panorama (> 0 = honey)")
    c.add_argument("--fov-h", type=float, metavar="DEG", help="horizontal field of view")
    c.add_argument("--fov-v", type=float, metavar="DEG", help="vertical field of view")
    c.add_argument("--center-az", type=float, default=0.0, metavar="DEG")
    c.add_argument("--center-el", type=float, default=0.0, metavar="DEG")
    c.add_argument("--lattice", type=Path, help="lattice .npz (default: the generated lattice)")
    c.add_argument("--scene-id", required=True)
    c.add_argument("--frame-id", required=True, help="names the output files")
    c.add_argument("--out", required=True, type=Path, help="output directory")
    c.add_argument("--cache", type=Path, help="directory for cached sampling matrices")
    c.add_argument("--plot", action="store_true", help="also write <frame_id>.png (R1_6 hex plot)")
    return parser


def _convert(args: argparse.Namespace) -> int:
    if (args.fov_h is None) != (args.fov_v is None):
        print("fly-vision: error: --fov-h and --fov-v must be given together", file=sys.stderr)
        return 2
    if args.fov_h is None and (args.center_az != 0.0 or args.center_el != 0.0):
        print(
            "fly-vision: error: --center-az/--center-el need --fov-h and --fov-v "
            "(the default honey-factory contract fixes the centre)",
            file=sys.stderr,
        )
        return 1
    try:
        pano = load_panorama(args.panorama)
        mask = None if args.mask is None else load_mask(args.mask)
        if mask is not None and mask.shape != pano.shape[:2]:
            raise ValueError(
                f"mask shape {mask.shape} does not match panorama shape {pano.shape[:2]}"
            )
        h, w = pano.shape[:2]
        projection: Projection
        if args.fov_h is None:
            projection = honey_factory_panorama(w, h)
            contract_aspect = projection.fov_h_deg / projection.fov_v_deg
            if abs(w / h / contract_aspect - 1.0) > _ASPECT_TOLERANCE:
                raise ValueError(
                    f"the image is {w}x{h} and does not look like a PANORAMA_310 capture "
                    f"(aspect {contract_aspect:.4f} within 1%); pass --fov-h and --fov-v "
                    "for other equirectangular images (perspective images are not supported)"
                )
        else:
            projection = EquirectSpec(w, h, args.fov_h, args.fov_v, args.center_az, args.center_el)
        lattice = default_lattice() if args.lattice is None else load_lattice(args.lattice)
        eye = FlyEye(lattice, projection, cache_dir=args.cache)
        source = SourceRef(
            scene_id=args.scene_id, frame_id=args.frame_id, image_path=str(args.panorama)
        )
        frame, labels = eye.sample(pano, mask, source)
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
