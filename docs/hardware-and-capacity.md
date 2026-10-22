# Local hardware and network capacity

Inspected 2026-10-22. Hardware observations are measured; network capacity below
is an analytical estimate, not a simulation or training benchmark. This informs
later roadmap phases without expanding the current mod/environment scope.

## Observed machine

| Component | Observation |
|---|---|
| CPU | AMD Ryzen 9 5950X, 16 physical cores / 32 threads, AVX2 |
| RAM | 49,239,516 KiB OS-visible (~47.0 GiB); ~38 GiB available at inspection |
| GPU | AMD Radeon RX 7600, gfx1102, 32 compute units; confirmed by rocminfo |
| VRAM | 8,573,157,376 bytes (~7.98 GiB); ~0.97 GiB occupied at inspection |
| Workspace storage | NVMe-backed filesystem, 932 GiB total, ~220 GiB free |
| Available dataset drive | Samsung SSD 990 EVO Plus 2TB, mounted at /mnt/fastvault; essentially empty (~1.8 TiB available) |
| Temporary storage | /tmp is tmpfs: RAM-backed, not independent disk capacity |
| OS | Linux 7.2.9-2-cachyos, x86_64 |
| Compute runtime | /opt/rocm/.info/version reports 7.2.4 |
| Rust | rustc and cargo 1.92.0 installed |

Sources: lscpu, /proc/meminfo, free, df, lspci, DRM sysfs, uname,
rocminfo and compiler version commands. Available memory/disk are snapshots.
No GPU serial numbers or machine identifiers are needed for this record.

The sandbox could not access /dev/kfd. An approved read-only rocminfo run outside
the sandbox successfully enumerated the RX 7600. This establishes runtime device
visibility, not working PyTorch/JAX kernels, sparse gradients or training speed.
No ML framework was installed or GPU training benchmark run for this inspection.
Do not assume CUDA-only code will work on this AMD GPU.

## Memory estimates

Count neurons N and directed computational edges E separately. Multiple anatomical
synapses may be aggregated into one weighted edge only if the selected model permits
it; aggregation loses individual synapse dynamics. These are synthetic sizing cases,
not assertions about the size of a specific connectome release.

Assume FP32 weights and 32-bit CSR column indices/row pointers:

- Graph storage: 8E + 4(N+1) bytes.
- With every edge trainable and FP32 Adam weights, gradients and two moments:
  20E + 4(N+1) bytes, before activations and workspaces.

| Neurons | Edges | Graph alone | Graph + edge gradients/Adam |
|---|---|---|---|
| 20,000 | 350,000 | 2.7 MiB | 6.8 MiB |
| 50,000 | 2 million | 15.4 MiB | 38.3 MiB |
| 150,000 | 10 million | 76.9 MiB | 191.3 MiB |
| 200,000 | 30 million | 229.6 MiB | 573.0 MiB |

These are lower-bound payload estimates. Frameworks may use 64-bit indices,
duplicate graph layouts, extra optimizer buffers, delays, plasticity traces or
per-edge temporary tensors. Each extra FP32 edge variable adds 4E bytes.
Conversely, training only a readout requires much less optimizer state.

Sparse storage is essential: a dense FP32 150,000-by-150,000 matrix is about
83.8 GiB before gradients, exceeding this machine's physical RAM.

Training history can dominate. One FP32 neuron-state array retained for batch B
and T simulation steps costs 4NBT bytes. At N=150,000, B=8 and T=1,000 this is
4.47 GiB for just one state variable. Multiple variables, backward computation and
edge intermediates add more. Frozen-weight inference can reuse current-state
buffers; backpropagation through a frozen recurrent circuit may still need history
if earlier trainable components require gradients.

Conclusion: sparse graphs with roughly 150,000–200,000 neurons and tens of millions
of edges fit in host memory under simple dynamics. This does not establish real-time
execution or feasible full-network training. No defensible maximum neuron count
exists until the dynamics, timestep, batch, learning rule and speed target are fixed.

## Speed and practical targets

For a clock-driven implementation visiting each edge once per substep, work scales
approximately as E × substeps/second × batch size. Ten million edges at 120
substeps/second implies 1.2 billion edge visits/second; a 1 ms timestep implies
10 billion. These are work counts, not FLOPS or measured throughput. A slow game
action rate does not automatically permit a coarse neural integration timestep.

- First candidate: a task-relevant 20,000–50,000 neuron subgraph, with modest batches
  and short training windows. This is a benchmark starting point, not a hard cap.
- Larger candidate: 150,000–200,000 neurons for sparse inference/offline simulation,
  contingent on actual timing. Avoid promising interactive full-brain training.
- Provisional budgets while Minecraft runs: 16–24 GiB host RAM and 4–5 GiB VRAM for
  model work. Re-measure rendering usage before setting limits; these are planning
  budgets, not measured Minecraft requirements.
- Prefer separate dataset capture and offline training initially to avoid competing
  for the same GPU. CPU inference is also worth measuring on this 16-core machine.

## Optimizations and Rust

1. Keep graph/state data in contiguous sparse arrays; avoid a language object per
   neuron/synapse and avoid accidental dense matrices.
2. Use existing compiled numerical kernels first. Sparse speed and gradient support
   depend on layout, operation and backend; verify the exact operations required.
3. Keep buffers resident, reuse allocations and avoid transferring whole graphs
   between CPU and GPU per step. Benchmark thread counts instead of assuming all
   32 logical threads improve throughput.
4. For training, consider short windows/truncated backpropagation, checkpointing,
   smaller batches, or a frozen circuit with a learned readout. These change cost
   and sometimes the learning experiment; document the choice.
5. Consider mixed precision only after checking stability against FP32. Consider
   event-driven spiking updates only when the neuron model and activity justify it.
6. Rust is permitted for measured CPU bottlenecks: graph preprocessing, image/label
   transforms, sparse simulation loops or local plasticity updates. Compare it with
   the compiled baseline before committing. Rust alone does not accelerate GPU
   kernels, solve memory-bandwidth limits or supply automatic differentiation.
7. Keep Fabric integration in Java. Add a Rust module/process only when profiling
   justifies the integration and copy overhead; no Rust dependency is added now.

For the generator, prioritize bounded capture/write queues, correct frame alignment
and avoiding render-thread stalls. Benchmark ray casting versus an annotation render
pass on our scenes before choosing. Save static scene metadata once per scene.

Storage also needs a budget: 100,000 uncompressed 640×360 frames with RGB8, an 8-bit
mask, FP32 depth and uint32 instance IDs use about 257.5 GiB before metadata.
This exceeds free space on the workspace filesystem, but fits comfortably on the
separate 2 TB Samsung NVMe mounted at /mnt/fastvault. The initial inspection only
checked the workspace filesystem; a subsequent lsblk/df inspection confirmed this
additional, essentially empty drive. Use it as the planned dataset storage location;
the exact project directory and capture configuration remain to be defined.
RGB + mask alone is about 85.8 GiB per 100,000 frames. Reserve space for derived
fly-vision data, checkpoints and temporary files. Compression and optional channels
help, but compression ratios must be measured. Do not stage a large dataset in
/tmp because it consumes RAM. No data was moved or storage configuration changed.

## Validation gate before choosing the network size

In the later network phase, test the actual framework on gfx1102: allocation,
forward sparse operation, required gradients and numerical agreement with CPU.
Runtime discovery alone is insufficient. Check version-specific AMD compatibility;
newer documentation does not certify the locally installed 7.2.4 stack.

Benchmark candidate graphs with representative degree distributions and dynamics:
20k/350k, 50k/2M, 150k/10M and, if useful, 200k/30M neurons/edges. Measure CPU and
validated GPU implementations separately, then repeat the selected inference case
with Minecraft rendering. Include warm-up and GPU synchronization in timing.

Record peak RAM/VRAM, substeps per second, observation-to-action latency (median and
p95), training steps per second and numerical stability. Specify timestep, batch,
sequence length, precision, learning rule and software versions. For an illustrative
10 Hz controller the entire observation–inference–action loop must fit within 100 ms;
this is a proposed benchmark target, not a measured capability or fixed requirement.

## References

- [PyTorch sparse documentation](https://docs.pytorch.org/docs/2.14/sparse.html):
  layouts, operation coverage and backend limitations; not a local version claim.
- [AMD ROCm installation guidance](https://rocmdocs.amd.com/en/docs-7.2.4/how-to/rocm-for-ai/install.html):
  framework installation and validation are separate from runtime installation.
- [Fly-Saber](https://github.com/jackie-0110/Fly-Saber): reference for a reduced
  visual-to-motor circuit; its published timings cannot be transferred to this PC.
