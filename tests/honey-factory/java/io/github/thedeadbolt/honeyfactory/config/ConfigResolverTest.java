package io.github.thedeadbolt.honeyfactory.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfigResolverTest {
	private static final String MINIMAL = """
		{"dataset": {"name": "honey-l1", "seed": 7, "difficulty": 1}, "output": {"root": "/srv/datasets"}}
		""";

	private static String with(String section, String json) {
		return """
			{"dataset": {"name": "honey-l1", "seed": 7, "difficulty": 1}, "output": {"root": "/srv/datasets"}, "%s": %s}
			""".formatted(section, json);
	}

	@Test
	void appliesSpecificationDefaults() {
		ResolvedConfig config = ConfigResolver.resolve(MINIMAL);
		assertEquals("minecraft:honey_block", config.target().blockId());
		assertEquals(new ResolvedConfig.Arena(32, 32, 16, 4), config.arena());
		assertEquals(new ResolvedConfig.Viewpoints(8, 3.0, 16.0, 15.0, 10.0, 50, 1.62), config.viewpoints());
		assertEquals(new ResolvedConfig.Lighting(6000, Weather.CLEAR), config.lighting());
		assertEquals(new ResolvedConfig.Capture(CaptureMode.PERSPECTIVE_90, 1280, 1280, null), config.capture());
		assertEquals(new ResolvedConfig.Quotas(1000, 800, 200, 10_000), config.quotas());
		assertEquals(new ResolvedConfig.Splits(0.8, 0.1, 0.1, SplitGrouping.SCENE), config.splits());
		assertEquals(VisibilityRule.FULLY_VISIBLE, config.visibility().rule());
		assertNull(config.naturalRegion());
		assertEquals(Path.of("/srv/datasets"), config.outputRoot());
		assertEquals(10L << 30, config.output().freeSpaceReserveBytes());
	}

	@Test
	void panoramaDefaults() {
		ResolvedConfig config = ConfigResolver.resolve(with("capture", "{\"mode\": \"PANORAMA_310\"}"));
		assertEquals(new ResolvedConfig.Capture(CaptureMode.PANORAMA_310, 2480, 1280, 1024), config.capture());
	}

	@Test
	void hashAndIdIgnoreTheOutputRootButFollowEveryOtherSetting() {
		ResolvedConfig base = ConfigResolver.resolve(MINIMAL);
		ResolvedConfig otherRoot = ConfigResolver.resolve(MINIMAL.replace("\"/srv/datasets\"", "\"/mnt/elsewhere\", \"free_space_reserve_bytes\": 0"));
		ResolvedConfig otherSeed = ConfigResolver.resolve(MINIMAL.replace("\"seed\": 7", "\"seed\": 8"));
		assertEquals(base.sha256(), otherRoot.sha256());
		assertEquals(0, otherRoot.output().freeSpaceReserveBytes());
		assertNotEquals(base.sha256(), otherSeed.sha256());
		assertEquals("honey-l1-" + base.sha256().substring(0, 12), base.datasetId());
	}

	@Test
	void quotasRoundHalfToEven() {
		assertEquals(2, ConfigResolver.resolve(with("quotas", "{\"accepted_scenes\": 5}").replace("}, \"output\"", "}, \"target\": {\"present_fraction\": 0.5}, \"output\"")).quotas().targetPresentScenes());
		assertEquals(8, ConfigResolver.resolve(with("quotas", "{\"accepted_scenes\": 10}")).quotas().targetPresentScenes());
	}

	@Test
	void level5NeedsARegionAndGroupsBySourceWorld() {
		String level5 = MINIMAL.replace("\"difficulty\": 1", "\"difficulty\": 5");
		ContractException missing = assertThrows(ContractException.class, () -> ConfigResolver.resolve(level5));
		assertEquals("/natural_region", missing.path());
		ResolvedConfig config = ConfigResolver.resolve(level5.replace("\"output\"", "\"natural_region\": {\"core_width\": 64, \"core_length\": 64, \"height\": 96, \"context_padding\": 128}, \"output\""));
		assertEquals(SplitGrouping.SOURCE_WORLD, config.splits().grouping());
		assertEquals(VisibilityRule.AT_LEAST_PARTLY_VISIBLE, config.visibility().rule());
	}

	/** Every invalid input names the offending setting and changes nothing outside the resolver. */
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
		unknown section                 | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "extra": {}}                                    | /extra
		unknown setting                 | {"dataset": {"name": "a", "seed": 1, "difficulty": 1, "colour": 1}, "output": {"root": "/x"}}                                     | /dataset/colour
		missing seed                    | {"dataset": {"name": "a", "difficulty": 1}, "output": {"root": "/x"}}                                                              | /dataset/seed
		seed beyond 2^53                | {"dataset": {"name": "a", "seed": 9007199254740992, "difficulty": 1}, "output": {"root": "/x"}}                                    | /dataset/seed
		bad dataset name                | {"dataset": {"name": "Honey L1", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}}                                            | /dataset/name
		level 6                         | {"dataset": {"name": "a", "seed": 1, "difficulty": 6}, "output": {"root": "/x"}}                                                   | /dataset/difficulty
		relative output root            | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "datasets"}}                                             | /output/root
		missing output root             | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}}                                                                             | /output
		distance range reversed         | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "viewpoints": {"distance_min_blocks": 20}}        | /viewpoints/distance_min_blocks
		too many viewpoints             | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "viewpoints": {"count": 100}}                     | /viewpoints/count
		level 1 rain                    | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "lighting": {"weather": "rain"}}                  | /lighting/weather
		level 1 night                   | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "lighting": {"time_of_day_ticks": 18000}}         | /lighting/time_of_day_ticks
		level 2 ratio bound             | {"dataset": {"name": "a", "seed": 1, "difficulty": 2}, "output": {"root": "/x"}, "visibility": {"min_ratio": 0.5}}                 | /visibility/min_ratio
		level 3 bound at 1              | {"dataset": {"name": "a", "seed": 1, "difficulty": 3}, "output": {"root": "/x"}, "visibility": {"max_ratio": 1.0}}                 | /visibility/max_ratio
		level 3 bounds reversed         | {"dataset": {"name": "a", "seed": 1, "difficulty": 3}, "output": {"root": "/x"}, "visibility": {"min_ratio": 0.6, "max_ratio": 0.4}} | /visibility/min_ratio
		cube faces in perspective       | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "capture": {"cube_face_size": 512}}               | /capture/cube_face_size
		unknown capture mode            | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "capture": {"mode": "FISHEYE"}}                   | /capture/mode
		tiny capture                    | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "capture": {"width": 8}}                          | /capture/width
		splits not summing to one       | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "splits": {"train": 0.7}}                         | /splits/train
		no training split               | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "splits": {"train": 0.0, "validation": 0.5, "test": 0.5}} | /splits/train
		fractional scene count          | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "quotas": {"accepted_scenes": 2.5}}               | /quotas/accepted_scenes
		arena too tall with its margin  | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "arena": {"height": 256, "margin": 4}}           | /arena/height
		region outside level 5          | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "natural_region": {}}                             | /natural_region
		arena without a target layer    | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "arena": {"height": 1}}                         | /arena/height
		negative free-space reserve     | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x", "free_space_reserve_bytes": -1}}                 | /output/free_space_reserve_bytes
		foreign block state             | {"dataset": {"name": "a", "seed": 1, "difficulty": 1}, "output": {"root": "/x"}, "target": {"allowed_block_states": ["minecraft:stone"]}} | /target/allowed_block_states
		""")
	void rejectsInvalidInputWithItsPath(String name, String json, String path) {
		ContractException error = assertThrows(ContractException.class, () -> ConfigResolver.resolve(json));
		assertEquals(path, error.path(), error.getMessage());
	}

	@Test
	void visibilityRatioBoundsDefaultFromTheM3Evidence() {
		ResolvedConfig.Visibility level3 = ConfigResolver.resolve("""
			{"dataset": {"name": "a", "seed": 1, "difficulty": 3}, "output": {"root": "/x"}}
			""").visibility();
		assertEquals(new ResolvedConfig.Visibility(VisibilityRule.PARTIALLY_VISIBLE, 0.2, 0.8), level3);
		ResolvedConfig.Visibility level4 = ConfigResolver.resolve("""
			{"dataset": {"name": "a", "seed": 1, "difficulty": 4}, "output": {"root": "/x"}, "visibility": {"max_ratio": 0.9}}
			""").visibility();
		assertEquals(new ResolvedConfig.Visibility(VisibilityRule.AT_LEAST_PARTLY_VISIBLE, 0.1, 0.9), level4);
		ResolvedConfig.Visibility unbounded = ConfigResolver.resolve("""
			{"dataset": {"name": "a", "seed": 1, "difficulty": 3}, "output": {"root": "/x"}, "visibility": {"min_ratio": null, "max_ratio": null}}
			""").visibility();
		assertNull(unbounded.minRatio());
		assertNull(unbounded.maxRatio());
	}
}
