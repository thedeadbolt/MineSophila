# 0026: JAX as the first learning backend

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

`fly-brain` needs automatic differentiation, CPU development and a path to the development
machine's AMD Radeon RX 7600 (`gfx1102`). The host has ROCm 7.2.4 installed. Structural connectome,
task and experiment contracts are already framework-independent, so choosing a backend should not
make datasets or graph assembly depend on it.

Current JAX releases support CPU on Linux x86-64 and AMD GPUs through AMD's ROCm plugin. JAX's pure
array functions, explicit parameter/state trees and sequence scans match the model contracts in ADR
0024. Connectome propagation can use indexed segment accumulation without requiring a backend sparse
matrix object.

The coding sandbox does not expose `/dev/kfd`, so it cannot validate the physical GPU even though
the host ROCm installation is visible.

## Decision

- Use JAX for the first linear/recurrent baselines and connectome compiler.
- Keep observations, targets, graphs, tasks, splits, manifests and metrics NumPy-based and free of
  JAX types. Conversion happens at the model/training boundary.
- Make model parameters and recurrent state explicit immutable pytrees. Keep prediction and loss
  functions pure.
- Develop and test on the standard CPU wheel. Treat the ROCm plugin as a machine-specific runtime
  installation rather than embedding a GPU stack in the package's default dependencies.
- Before a substantial training run, execute a host-level smoke benchmark that lists a ROCm device,
  differentiates the representative model and compares CPU/GPU numerical results and runtime.
- Reconsider PyTorch or another backend if the host smoke test fails, required operations are
  unsupported, or measured training performance is materially worse.

## Consequences

The first training path can be built and tested without GPU access, and its functional form maps
cleanly to explicit brain state and temporal scans. AMD acceleration remains available without
forcing every developer or CI job to install ROCm.

JAX becomes a package dependency and introduces compilation behavior that tests must account for.
GPU readiness is provisional until the host, rather than the sandbox, completes the recorded smoke
test.
