"""fly-vision: convert images and Minecraft scenes into Drosophila vision.

Planned public interface (see docs/architecture.md):

- ``Scene``: an input image plus optional metadata (depth, block ids, sky mask, sun).
- ``FlyVision``: facade that renders a scene in one vision mode, compares a pair of
  modes, or yields all pairwise combinations (4 choose 2).
"""

from importlib.metadata import version

__version__ = version("fly-vision")

__all__ = ["__version__"]
