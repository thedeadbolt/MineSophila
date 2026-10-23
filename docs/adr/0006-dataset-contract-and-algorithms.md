# 0006: honey-factory dataset contract and deterministic algorithms

- **Status:** Accepted
- **Date:** 2026-10-23

## Context

Milestone M1 needs Minecraft-independent configuration, record types and the rules that
make generation reproducible, before world mutation or capture starts. The specification
left open the exact identifier and hash algorithms, the canonical layout serialization
and how the Java mod and outside Python tools share one contract without sharing code.

## Decision

- Publish the contract as JSON Schema Draft 2020-12 under `schemas/honey-factory/1.0/`,
  with exact algorithm definitions in its README. Schemas allow unknown members, for
  minor-version compatibility.
- Validate independently on each side. The mod parses records into Java types with its
  own invariant checks (Gson, already shipped by Minecraft, so no new dependency). The
  Python `honey-factory-tools` package validates with `jsonschema`. Both consume shared
  valid fixtures, patch-based invalid cases and golden vectors.
- Hash resolved configurations as RFC 8785 canonical JSON (`jcs-sha256-v1`). The hashed
  object is the manifest's own `difficulty`, `target`, `generator`, `capture` and
  `splits` (without final counts), so any reader can recompute it.
- Form dataset IDs from the dataset name and the first 12 hex digits of the
  configuration hash (`ids-v1`). Scene and sample IDs come from slot and viewpoint
  indices; shards are the first two hex digits of the sample ID's SHA-256.
- Derive layout, target and viewpoint seeds per scene attempt from SHA-256 (`seed-v1`).
  Limit dataset and derived seeds to 0 to 2^53 - 1 so they stay exact JSON integers.
- Fix each slot's target class and split before generation (`scene-slots-v1`): exact
  present/absent quotas, then largest-remainder split counts per class.
- Version the canonical block-layout text hashed into `layout_sha256` (`layout-v1`).
- Require an explicit, absolute output root; it never enters the hash or manifest.
- Apply the specification's defaults, plus a 4-block arena margin. Level 5 requires
  explicit `natural_region` settings until M6 benchmarks their defaults.

## Consequences

- A configuration change always yields a new dataset ID, and identical configurations
  collide on purpose: the writer must refuse an existing directory. Regenerating a
  dataset to check reproducibility uses a different output root.
- Java and Python can disagree only where a fixture fails, which makes either
  implementation's bugs visible. Rules needing cross-member arithmetic are marked
  `semantic` and are not in the schemas; the M4 outside validator must add them.
- Changing any algorithm or the hashed configuration shape needs a new algorithm
  version, recorded in `generator.algorithms`, not a silent edit.
- Minecraft world seeds are 64-bit and may exceed 2^53; level-5 source-patch records
  keep them as JSON integers, which some languages cannot represent exactly. Revisit
  when M6 defines source-patch IDs and world-seed grouping.
- The Python tools locate schemas from the repository; packaging them for installed use
  is deferred until the tools are distributed.
