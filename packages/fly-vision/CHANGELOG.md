# Changelog

## [0.1.0](https://github.com/thedeadbolt/MineSophila/compare/fly-vision-v0.0.1...fly-vision-v0.1.0) (2026-10-26)


### Features

* **fly-vision:** add a perspective projection for placed images ([d362df1](https://github.com/thedeadbolt/MineSophila/commit/d362df1165e53af383bf8e66eafaaf83ea758b1b))
* **fly-vision:** add cached sparse Gaussian sampling matrix ([f394cf7](https://github.com/thedeadbolt/MineSophila/commit/f394cf7cf1b5591c5fd6f931487e3f9b3b5b74e0))
* **fly-vision:** add config, hashing and dependencies ([1089a5c](https://github.com/thedeadbolt/MineSophila/commit/1089a5c0828378d7501073f678d2bedc3c334b60))
* **fly-vision:** add convert CLI, interface doc and calibration test ([e77e8de](https://github.com/thedeadbolt/MineSophila/commit/e77e8de94df428fca089d905903eb9cfadc83eaa))
* **fly-vision:** add coverage, metamer and spectral-sensitivity diagnostics ([1d50647](https://github.com/thedeadbolt/MineSophila/commit/1d50647761ac369a73d62809ae59b18f926e5152))
* **fly-vision:** add FlyEye sampling with fly-space labels and provenance ([2145c8f](https://github.com/thedeadbolt/MineSophila/commit/2145c8f60faf4c023ce5bf2587efc7f4a9385a9f))
* **fly-vision:** add generated hex eye lattice and hex plot ([8ee4101](https://github.com/thedeadbolt/MineSophila/commit/8ee4101d7d0beb82b4c0741e9ba2e746233d2b87))
* **fly-vision:** add per-frame adaptation ([c96c5f3](https://github.com/thedeadbolt/MineSophila/commit/c96c5f3ce59d770f98566b591490f6ebabe78200))
* **fly-vision:** add pixel-to-ray projection for the panorama contract ([7cbcc91](https://github.com/thedeadbolt/MineSophila/commit/7cbcc91bd52b053eae2ea63ca8a946b9e2e87fef))
* **fly-vision:** add RGB to rhodopsin spectral model ([86e324f](https://github.com/thedeadbolt/MineSophila/commit/86e324f95f6d765f12a1ab4473a488c4fd3a0ff7))
* **fly-vision:** add the v0 virtual fly eye with image placement ([82707c5](https://github.com/thedeadbolt/MineSophila/commit/82707c50f85a7a9cd48e037c88bc8d1d6b3350f2))
* **fly-vision:** bound perspective placement to a level, finite camera ([4f8a422](https://github.com/thedeadbolt/MineSophila/commit/4f8a422908c69d8642c98ac5b417ef010c16ed23))
* **fly-vision:** derive a photo's field of view from object size and distance ([9559526](https://github.com/thedeadbolt/MineSophila/commit/955952636b61d10a51a8ade5b17ec2f683573b4d))
* **fly-vision:** follow the measured Drosophila field of view in the generated lattice ([efd9c79](https://github.com/thedeadbolt/MineSophila/commit/efd9c79167ab5e92378dc08845f3f3c8a256a1fc))
* **fly-vision:** guard projection, lattice loading and CLI inputs ([78f9f15](https://github.com/thedeadbolt/MineSophila/commit/78f9f15016c3942822619a29e7fe77acb5f6c6eb))
* **fly-vision:** place perspective images from the CLI, opt-in ([2e8c3b9](https://github.com/thedeadbolt/MineSophila/commit/2e8c3b96f6b4cd24952f98def444407b8d340777))
* **fly-vision:** record provenance in label metadata ([9e5cf40](https://github.com/thedeadbolt/MineSophila/commit/9e5cf40053034b257c898a0f663bc28f0f339f9e))
* **fly-vision:** record the projection in frame metadata ([f62ca05](https://github.com/thedeadbolt/MineSophila/commit/f62ca058ddd10a9563d562690c1e88aff27ab7fe))


### Bug Fixes

* **fly-vision:** apply EXIF orientation when loading images and masks ([65328db](https://github.com/thedeadbolt/MineSophila/commit/65328dbb03b1dbdb327bd5903a4a30961bcc9c9e))
* **fly-vision:** coerce spec fields, tidy CLI warnings and messages ([029764c](https://github.com/thedeadbolt/MineSophila/commit/029764c44d6491edad45e0d5696ce908ab7e34e3))
* **fly-vision:** make Projection size attributes read-only ([bc62fcc](https://github.com/thedeadbolt/MineSophila/commit/bc62fcc144b91849cb1b04fc5f28d7a3c1e63777))
* **fly-vision:** validate honey mask before labelling ([fb9d9f5](https://github.com/thedeadbolt/MineSophila/commit/fb9d9f574443a1d2269fe6905fa9a47020ee69bc))


### Documentation

* **fly-vision:** add v0 agent brief and implementation plan ([8493180](https://github.com/thedeadbolt/MineSophila/commit/8493180f5a460998363f9c4b8f726970cb322d3e))
* **fly-vision:** amend plan with label and overlap decisions ([7f65c99](https://github.com/thedeadbolt/MineSophila/commit/7f65c99cc0beb6d3ab379a37d67c8a62999312bf))
* **fly-vision:** correct the R7 explanation and refresh stale entries ([d9b43d6](https://github.com/thedeadbolt/MineSophila/commit/d9b43d64e409dc1bbdbc70296c39281e184499b2))
* **fly-vision:** document the implicit projection rule ([12b5ef3](https://github.com/thedeadbolt/MineSophila/commit/12b5ef3cfab365f8b21ff8fd9fa328494e74cf51))
* **fly-vision:** make perspective placement opt-in in the plan ([128a334](https://github.com/thedeadbolt/MineSophila/commit/128a3346ac2eda081a999cc5a6b1d981670da3fb))
* **fly-vision:** measured edge truncation, default-FOV caveats, size note ([0783139](https://github.com/thedeadbolt/MineSophila/commit/078313946b9bd48c6a892266512e54cf417dcd6a))
* **fly-vision:** plan the image placement adapter ([58fc524](https://github.com/thedeadbolt/MineSophila/commit/58fc5247bc6a73cdff915c57b0808c718693ca29))
* **fly-vision:** re-measure edge truncation with lattice v2 ([324348c](https://github.com/thedeadbolt/MineSophila/commit/324348cf93be180d174f98ab1c25125a2d864d6a))
* **fly-vision:** record the implicit projection rule in the placement plan ([73340d6](https://github.com/thedeadbolt/MineSophila/commit/73340d6518684779bfc9fd1c7ef0f908a0f00c94))
* **fly-vision:** record the R7 information check on example frames ([4abbdc2](https://github.com/thedeadbolt/MineSophila/commit/4abbdc288b656b4a359c9f9515f7e7bfa09eede1))
* **fly-vision:** renumber the eye-model ADR to 0019 ([758b425](https://github.com/thedeadbolt/MineSophila/commit/758b425b36502a7d1096d7ddabaa13c5a494b8c2))
* **fly-vision:** require a level, finite camera in the placement plan ([5c57462](https://github.com/thedeadbolt/MineSophila/commit/5c57462a0da0ffc077093f223e303878ba0c0a92))
* **fly-vision:** update the overlap figures for lattice v2 ([2a273b8](https://github.com/thedeadbolt/MineSophila/commit/2a273b8e1f8c78292ec0d1465b0b5110da33c28b))

## Changelog

All notable changes to `fly-vision` are documented here. This file is generated by
[release-please](https://github.com/googleapis/release-please) from Conventional Commits
scoped `fly-vision`. Don't edit it by hand.
