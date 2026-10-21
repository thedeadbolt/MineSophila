"""Vision modes: the four ways a Scene can be seen.

Planned: a ``VisionMode`` base class and a registry, so new modes are picked up by
``FlyVision.pairs`` automatically. Modes:

- ``human``: normal RGB vision (baseline).
- ``compound``: ommatidial sampling only.
- ``photoreceptor``: compound vision resolved into R1-R6, R7 and R8 channels.
- ``ocelli``: the ocelli added on top.
"""
