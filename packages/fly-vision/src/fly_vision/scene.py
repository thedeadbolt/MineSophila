"""Scene: the input to every vision mode.

Planned: an RGB image plus optional metadata (depth, per-pixel block ids, sky mask,
sun position). Plain images fill only the image; scenes from honey-factory fill the
metadata, which lets UV, polarisation and ocelli channels be derived from what is
actually in the scene instead of estimated from RGB.
"""
