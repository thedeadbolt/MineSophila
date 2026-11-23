# First MaleCNS visual network

The first reviewed structural network uses four named components from MaleCNS v1.0:

| Component | Selection | Status | Neurons |
|---|---|---|---:|
| `photoreceptors` | All curated R1-R6, R7, R8 and HB eyelet types | anatomical | 6,098 |
| `lamina_relays` | L1-L5 | anatomical | 8,884 |
| `medulla_relays` | Mi1/Mi4/Mi9 and Tm1/2/3/4/6/9/20 | approximation | 17,646 |
| `visual_projection` | Curated `visual_projection` superclass | anatomical | 9,201 |

The medulla set is deliberately bounded. It is not claimed to be a complete optic-lobe model.
It is the first executable relay candidate and can be expanded through named component changes.

Internal and cross-component edges with at least five anatomical synapses produce 41,829 neurons,
313,471 directed edges and 4,393,043 represented synapses. No component is disconnected. The
canonical graph SHA-256 is
`31926f63193e2850933e6a2b1caf86d0ff5ae4ca60f57d14ae1b1428c3e33341`.

The resolved manifest, including all exact neuron IDs, selectors, biological statuses and edge
policy, is stored outside Git at
`/mnt/fastvault/minesophila/connectomes/male-cns-v1.0/first-visual-network.json`. Its SHA-256 is
`e5b393baa5d382d2f04b00530d95ccb3f051c55ce4e437745c2e26ef9bc15322`. The manifest is 734,635
bytes and can be regenerated from the pinned source tables and component definitions.

The external `fly-vision` receptor sites are not asserted to be one-to-one MaleCNS photoreceptor
IDs. Slice 6 must use an explicit approximate input port to inject the three receptor channels
into this network.

## Optional visual Kenyon expansion

Slice 9 adds `visual_kenyon` as an opt-in memory component; the four-component default remains
unchanged so earlier experiments stay reproducible. The selector takes annotated `Kenyon_Cell`
neurons receiving a direct edge of at least the configured synapse threshold from an annotated
`visual_projection` neuron. It is graph-derived and serialized in the network manifest rather
than maintained as a hand-written ID list.

At the five-synapse threshold, MaleCNS v1.0 selects 284 of 4,064 annotated Kenyon cells. They
receive 672 represented visual-projection edges containing 9,410 anatomical synapses. The
expanded five-component network contains 42,113 neurons, 318,418 directed edges and 4,431,091
represented synapses. This establishes anatomical membership and connectivity only; whether the
component improves learned temporal localisation must be established by the Slice 9 ablation.

`visual_kenyon_inputs` names the matched upstream side of those same thresholded edges: 103
visual-projection neurons. It overlaps the broader `visual_projection` component and therefore
does not add neurons or edges to the compiled graph. Its purpose is experimental control: temporal
readouts can compare the immediate 103-neuron input representation with the 284-neuron Kenyon
expansion without using all 9,201 visual-projection neurons.
