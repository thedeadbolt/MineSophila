"""Virtual Drosophila eye: the installed version and the fixed channel order."""

from importlib.metadata import version

__version__ = version("fly-vision")

CHANNELS: tuple[str, str, str] = ("R1_6", "R7", "R8")

__all__ = ["CHANNELS", "__version__"]
