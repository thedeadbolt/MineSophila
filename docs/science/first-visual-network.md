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
