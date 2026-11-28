# Changelog

## [0.1.0](https://github.com/thedeadbolt/MineSophila/compare/honey-factory-v0.0.1...honey-factory-v0.1.0) (2026-11-28)


### Features

* **honey-factory-tools:** merge dataset slices ([6cb9708](https://github.com/thedeadbolt/MineSophila/commit/6cb970802c19b9149d46967876846ef63b60b421))
* **honey-factory-tools:** run a generation job on several game instances ([dfaaca9](https://github.com/thedeadbolt/MineSophila/commit/dfaaca9cf4ac4fa443e0f57250e0c2af6d75956c))
* **honey-factory:** add 310-degree panoramic capture (M5) ([#6](https://github.com/thedeadbolt/MineSophila/issues/6)) ([f25b654](https://github.com/thedeadbolt/MineSophila/commit/f25b654920f63616cc9b2758408938dc535343ac))
* **honey-factory:** add constructed and natural scenes with scene recipes (M6) ([#7](https://github.com/thedeadbolt/MineSophila/issues/7)) ([d9ade1d](https://github.com/thedeadbolt/MineSophila/commit/d9ade1d839533e65bb47c734f41f1bd8c4463992))
* **honey-factory:** add dataset runner, levels 2-3 and outside validator (M4) ([#5](https://github.com/thedeadbolt/MineSophila/issues/5)) ([0a14a06](https://github.com/thedeadbolt/MineSophila/commit/0a14a060313afb2b129498a198c982b087935a1f))
* **honey-factory:** add drift and the approach, occlusion, detour and no-honey clips ([790f246](https://github.com/thedeadbolt/MineSophila/commit/790f2464e1368b0bc330d3888e2e221dd71f6eaf))
* **honey-factory:** add level-1 perspective vertical slice (M3) ([#4](https://github.com/thedeadbolt/MineSophila/issues/4)) ([d5f3441](https://github.com/thedeadbolt/MineSophila/commit/d5f3441b3d94fc4e4d1ceae41389f59b8a0bb280))
* **honey-factory:** add lock-step animal-like control and a loopback control server (M11) ([#12](https://github.com/thedeadbolt/MineSophila/issues/12)) ([c35040e](https://github.com/thedeadbolt/MineSophila/commit/c35040eff22ee14aef64c7612e88f89969e54f21))
* **honey-factory:** add movement actions and complete the environment API (M7) ([#8](https://github.com/thedeadbolt/MineSophila/issues/8)) ([126da10](https://github.com/thedeadbolt/MineSophila/commit/126da10ce1abad734bd89f895b0bc5ba4e60f9c3))
* **honey-factory:** add re-acquire and frontier search to the teacher ([190affd](https://github.com/thedeadbolt/MineSophila/commit/190affdafc6889d3b2cd3c1090fdf2a06fbaa1a9))
* **honey-factory:** add rendering feasibility, data contract and environment (M0–M2) ([#3](https://github.com/thedeadbolt/MineSophila/issues/3)) ([c4968a9](https://github.com/thedeadbolt/MineSophila/commit/c4968a9d5b5cdc72bb8b3ecae7b3018103f536d7))
* **honey-factory:** add search clips, in which the teacher finds hidden honey and goes to it ([961c09d](https://github.com/thedeadbolt/MineSophila/commit/961c09d282afce4ad5fcd0e6521bb7938deea2f9))
* **honey-factory:** add the dataset inspector, sidecar reviews and exact replay (M8) ([#9](https://github.com/thedeadbolt/MineSophila/issues/9)) ([e257ad3](https://github.com/thedeadbolt/MineSophila/commit/e257ad3c954a3984e05db7514c9d9bb52c8535a3))
* **honey-factory:** add the M12-2 pilot configurations ([1e7be36](https://github.com/thedeadbolt/MineSophila/commit/1e7be367c951610c1da21e363bb3ad363047ea5f))
* **honey-factory:** add the M9 acceptance runs and plan the production datasets and active search ([#10](https://github.com/thedeadbolt/MineSophila/issues/10)) ([75a489b](https://github.com/thedeadbolt/MineSophila/commit/75a489b9170e5228abad87598030fde8a76ea5c4))
* **honey-factory:** announce each clip's type in the game's action bar ([14e53b0](https://github.com/thedeadbolt/MineSophila/commit/14e53b0af5c87a130ee142ffc4abad0d426455a6))
* **honey-factory:** capture several frames per control tick ([63fc561](https://github.com/thedeadbolt/MineSophila/commit/63fc56154fed1b86df246307bac9fc29807d3e1e))
* **honey-factory:** count clip falls and name why seeking clips missed the honey ([dc90881](https://github.com/thedeadbolt/MineSophila/commit/dc908816a5be2dfe7b0021ca009202678f81a458))
* **honey-factory:** drift towards ledges and walls so the data holds recoveries ([ee962e1](https://github.com/thedeadbolt/MineSophila/commit/ee962e1d8057c66de9b2d816ac45303b54a70b16))
* **honey-factory:** generate a slice of a dataset's scenes ([26eb514](https://github.com/thedeadbolt/MineSophila/commit/26eb514dda605584915ce25d3da683ac828efb90))
* **honey-factory:** generate motion clips with scripted scan and pass-by ([1ba4af8](https://github.com/thedeadbolt/MineSophila/commit/1ba4af86a085f03c3f644242295eb613c3ac6f11))
* **honey-factory:** give the teacher only what the fly could know ([347cb8d](https://github.com/thedeadbolt/MineSophila/commit/347cb8d20549b54a6993ba5ee3032bf649babe22))
* **honey-factory:** guard executed commands at ledges and keep pass-bys on the floor ([6b65a8d](https://github.com/thedeadbolt/MineSophila/commit/6b65a8dc2ef0c0830d904c8503cda2e6c35d36ee))
* **honey-factory:** halve the scripted share of the default clip mix ([a300fbd](https://github.com/thedeadbolt/MineSophila/commit/a300fbd1b859d75db2cc6656ec0b93fdd78b39f2))
* **honey-factory:** place the target anywhere in the image and set a visible-pixel minimum ([#11](https://github.com/thedeadbolt/MineSophila/issues/11)) ([5e2d859](https://github.com/thedeadbolt/MineSophila/commit/5e2d859c40627c8bf0c96f033b9c653d737092a6))
* **honey-factory:** record who drove each clip tick ([93cb74c](https://github.com/thedeadbolt/MineSophila/commit/93cb74ccc35824a685af853bca7c58cec9b55ded))
* **honey-factory:** slice, merge, replay and read clip datasets ([fd7dcc4](https://github.com/thedeadbolt/MineSophila/commit/fd7dcc442059ea19af5dab40e571dff927482c79))
* **honey-factory:** store clip frames as lossless video ([34d971f](https://github.com/thedeadbolt/MineSophila/commit/34d971fadb15290ccc54c2baa32a9f58b97b2f8b))
* **honey-factory:** store clip RGB as lossy H.264 at CRF 18 by default ([384b4cc](https://github.com/thedeadbolt/MineSophila/commit/384b4cca9775e9f9c00e8a939d7e2f8e048c6a7b))
* **honey-factory:** teach tracking with pathfinding around seen obstacles ([e6e8705](https://github.com/thedeadbolt/MineSophila/commit/e6e8705b487b78eaf4e96f6f0ca7729bb03fa49b))
* **honey-factory:** validate clip datasets, including the teacher's realizability ([5774571](https://github.com/thedeadbolt/MineSophila/commit/5774571064526beb139c4eedda50d4f2590d81f7))
* **schemas:** add the clip dataset kind ([b1e4182](https://github.com/thedeadbolt/MineSophila/commit/b1e41820f3cc2b769c8b977d23291b98eedc3896))


### Bug Fixes

* **honey-factory:** choose search frontiers only from what was seen ([f578318](https://github.com/thedeadbolt/MineSophila/commit/f578318ae5ab86665dfe32e6425f4433b4d4feb7))
* **honey-factory:** count one deep drop as a fall, not walking downhill ([22db4d1](https://github.com/thedeadbolt/MineSophila/commit/22db4d13c3a797fc19af9b386829b4212640c0f2))
* **honey-factory:** draw in-between clip frames from the interpolated camera ([f3a9eff](https://github.com/thedeadbolt/MineSophila/commit/f3a9eff62fb05a93c37736b5f58a7a863d5355c1))
* **honey-factory:** fail a clip whose encoder dies instead of blocking ([31926b6](https://github.com/thedeadbolt/MineSophila/commit/31926b6143409ef7b503bce76f85b41444c42ea0))
* **honey-factory:** give approach and detour clips 160 ticks ([cadedc1](https://github.com/thedeadbolt/MineSophila/commit/cadedc163fef5a707e9852b8d3546cc324711f44))
* **honey-factory:** give approach and detour clips 200 ticks ([dc761cc](https://github.com/thedeadbolt/MineSophila/commit/dc761ccca0ee6e10372d4a1786f119ae979c2607))
* **honey-factory:** give occlusion clips 160 ticks ([e1569dc](https://github.com/thedeadbolt/MineSophila/commit/e1569dc7731cac2df18136c5477890b96d2d105e))
* **honey-factory:** give occlusion clips time to return and blame the teacher only for ticks it drove ([3a4b4c0](https://github.com/thedeadbolt/MineSophila/commit/3a4b4c0b3aefc85ffc9b5a71f8b63ecdba9c111a))
* **honey-factory:** give up a way back that gets no nearer ([c2b916f](https://github.com/thedeadbolt/MineSophila/commit/c2b916fb3e323db5520cd7a2858ed72bce7cdfda))
* **honey-factory:** judge edges by the body's centre, as Minecraft does ([202b7c3](https://github.com/thedeadbolt/MineSophila/commit/202b7c3c1e0e565037f149599b4cf0192a904440))
* **honey-factory:** keep the teacher off ledges it has not seen to be safe ([27bda98](https://github.com/thedeadbolt/MineSophila/commit/27bda98b0f14f7e807086e038202fbf7c46eefa1))
* **honey-factory:** let detour starts hide the honey on every level ([2dd7064](https://github.com/thedeadbolt/MineSophila/commit/2dd70647303a51e7f805dc7573216f9b52160358))
* **honey-factory:** let the teacher stand anywhere within reach of the honey ([fc2bfa8](https://github.com/thedeadbolt/MineSophila/commit/fc2bfa8f28204cf6baa0b825f47562f9bf35128e))
* **honey-factory:** plan walks with fences 1.5 high and plants walked through ([d9975ea](https://github.com/thedeadbolt/MineSophila/commit/d9975ea53448b00779ddfce6ba0423cbd301a4af))
* **honey-factory:** record in-between frame yaws within ±180 degrees ([de9862f](https://github.com/thedeadbolt/MineSophila/commit/de9862fb7c36712d8390cb55978ff62790001565))
* **honey-factory:** retry a clip with no placeable start on fresh draws ([85a3980](https://github.com/thedeadbolt/MineSophila/commit/85a39808b5982ff40ebc4fef40580839655f5604))
* **honey-factory:** step down off a wall onto floor not yet seen ([0746d29](https://github.com/thedeadbolt/MineSophila/commit/0746d296e9e56fb350b808677a2dda9c51f1f357))
* **honey-factory:** time re-acquiring from the return, not the loss ([5d699f6](https://github.com/thedeadbolt/MineSophila/commit/5d699f6f1f12f8787a2ebb6139c4852c9f004e16))


### Performance Improvements

* **honey-factory:** check clip videos off the render thread, without decoding twice ([4e34661](https://github.com/thedeadbolt/MineSophila/commit/4e3466101584db2f484f521bbe72a1d9a91c5b9f))
* **honey-factory:** overlap capture readback with rendering ([dd14dc7](https://github.com/thedeadbolt/MineSophila/commit/dd14dc74989fee5ee270c1dc3766b80dce8a2d15))
* **honey-factory:** reproject panoramas on the GPU behind a setting ([0463c69](https://github.com/thedeadbolt/MineSophila/commit/0463c696560dee3ee3b7c3107759c0fef38b121c))
* **honey-factory:** reproject panoramas on worker threads ([9298211](https://github.com/thedeadbolt/MineSophila/commit/929821146adea8259c47419c025d8b6f9e93fb00))
* **honey-factory:** reset only what changed ([4272596](https://github.com/thedeadbolt/MineSophila/commit/4272596a7b2ece1a05f44dd1770db806b6b683a5))
* **honey-factory:** uncap the frame rate while generating ([b00e49f](https://github.com/thedeadbolt/MineSophila/commit/b00e49f688ad6e26e15f9e5a94cef7f0d3708f0b))
* **honey-factory:** validate dataset images in parallel ([#13](https://github.com/thedeadbolt/MineSophila/issues/13)) ([cd8224d](https://github.com/thedeadbolt/MineSophila/commit/cd8224db568811ad2bc43cbdf0a29901079ece74))


### Reverts

* **honey-factory:** drop GPU panorama reprojection ([1b036c3](https://github.com/thedeadbolt/MineSophila/commit/1b036c3d466d6afb8216225cb0043d68332cbb8a))
* **honey-factory:** reset by clearing and rebuilding again ([7c594d3](https://github.com/thedeadbolt/MineSophila/commit/7c594d342a7f7ff6289b3df6df72b390d66ed7a2))

## Changelog

All notable changes to `honey-factory` are documented here. This file is generated by
[release-please](https://github.com/googleapis/release-please) from Conventional Commits
scoped `honey-factory`. Don't edit it by hand.
