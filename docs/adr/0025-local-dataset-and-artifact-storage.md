# 0025: Local dataset and large-artifact storage

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

Honey Factory datasets, derived receptor observations and upstream connectome releases can be much
larger than the source repository. The primary development machine has a dedicated 2 TB disk, and
the current project does not need shared hosting or automated acquisition of complete datasets.
Git still needs enough provenance to reproduce an experiment and CI needs small, dependable test
inputs.

## Decision

- Keep production datasets, downloaded connectome releases, derived caches and model artifacts on
  local storage outside the Git worktree. The current conventional dataset root is
  `/mnt/fastvault/minesophila/datasets`, but every consumer accepts a configured path and does not
  depend on that machine-specific location.
- Keep `data/` ignored. Do not use Git LFS, DVC or a hosted dataset service unless collaboration or
  backup requirements later justify one.
- Store portable manifests in Git or experiment outputs. A manifest pins relevant source hashes,
  release and generator versions, configuration hashes, schema versions and relative artifact
  paths; absolute local paths are not reproducibility identifiers.
- Keep only deliberately small synthetic or derived fixtures in the repository for unit tests and
  CI. Fixtures record their origin and may not contain a full upstream dataset.
- Load MaleCNS and other external data from explicit local paths and verify caller-supplied SHA-256
  checksums. Loading code does not silently download large artifacts.
- Continue using the existing Honey Factory dataset layout: portable image and metadata paths are
  relative to each dataset directory, and its validators remain the authority for that contract.

## Consequences

Large files do not consume Git history, hosted quotas or routine CI bandwidth. Experiments can use
the available local disk immediately, while checksums and manifests preserve identity independently
of where files are mounted.

The local disk is initially the only complete copy, so backup and sharing are operator concerns.
If collaboration requires remote distribution, a later ADR can add object storage, DVC or a dataset
hub without changing package APIs that already accept paths and manifests.
