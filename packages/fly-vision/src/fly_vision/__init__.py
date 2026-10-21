"""Expose the installed fly-vision version."""

from importlib.metadata import version

__version__ = version("fly-vision")

__all__ = ["__version__"]
