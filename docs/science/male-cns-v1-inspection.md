# MaleCNS v1.0 inspection

The first `fly-brain` structural source is the official Janelia MaleCNS v1.0 flat connectome at
minimum synapse confidence 0.5. The source is CC BY 4.0 and is documented on the
[official download page](https://male-cns.janelia.org/download/).

The raw artifacts live outside Git under
`/mnt/fastvault/minesophila/connectomes/male-cns-v1.0/`. `MaleCNSLoader` pins and verifies these
files:

| Artifact | SHA-256 |
|---|---|
| `body-annotations-male-cns-v1.0-minconf-0.5.feather` | `2177e246113e4cfbf1e7772ec37c6da1955ff22e8063d0b1f833101f99a9a3b2` |
| `body-neurotransmitters-male-cns-v1.0.feather` | `95c9289220663abeb3409f3ad9e5a7f8a53f8093f5139d15502cd08da8879621` |
| `connectome-weights-male-cns-v1.0-minconf-0.5.feather` | `e35da783d1c686b2b58b3b87cd6a403ae43bfcfba8bff28e08ef752c1a56afc1` |

The connection table contains 151,856,684 rows. Materializing every segment is not the initial
model. Selection and minimum anatomical synapse count are explicit loader inputs. A reproducible
whole-curated-annotation inspection at minimum count 50 produced:

| Measurement | Value |
|---|---:|
| Curated neurons | 211,577 |
| Directed edges | 228,209 |
| Anatomical synapses represented | 20,459,410 |
| In-memory canonical graph | 78,259,504 bytes |
| Graph SHA-256 | `228426d24d4447ee8db040908100db67816949eb8b29126e59881021f20c00ea` |

Annotation gaps are 47,071 cell types, 44,877 classes, 189,757 soma neuropils, 24,561
neurotransmitters and 42,951 sides. Empty annotations remain empty; the loader does not invent
biological labels. Slice 4 therefore selects only reviewed annotations and saves the resulting
exact IDs in its component manifest.
