package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the Java algorithms against vectors produced by an independent implementation. */
class GoldenVectorsTest {
	@Test
	void canonicalJsonMatchesVectors() {
		JsonObject vectors = Fixtures.json(Fixtures.vectors().resolve("canonical-json.json"));
		for (JsonElement element : vectors.getAsJsonArray("cases")) {
			JsonObject testCase = element.getAsJsonObject();
			JsonElement input = JsonParser.parseString(testCase.get("input").getAsString());
			assertEquals(testCase.get("canonical").getAsString(), CanonicalJson.serialize(input), testCase.get("input").getAsString());
		}

		for (JsonElement rejected : vectors.getAsJsonArray("rejected")) {
			JsonElement input = JsonParser.parseString(rejected.getAsString());
			assertThrows(IllegalArgumentException.class, () -> CanonicalJson.serialize(input), rejected.getAsString());
		}
	}

	@Test
	void seedsMatchVectors() {
		for (JsonElement element : Fixtures.json(Fixtures.vectors().resolve("seeds.json")).getAsJsonArray("cases")) {
			JsonObject c = element.getAsJsonObject();
			SceneSeeds seeds = SceneSeeds.derive(c.get("dataset_seed").getAsLong(), c.get("scene_index").getAsInt(), c.get("attempt_index").getAsInt());
			assertEquals(new SceneSeeds(c.get("layout").getAsLong(), c.get("target").getAsLong(), c.get("viewpoint").getAsLong()), seeds);
		}
	}

	@Test
	void idsShardsAndPathsMatchVectors() {
		JsonObject vectors = Fixtures.json(Fixtures.vectors().resolve("ids.json"));
		for (JsonElement element : vectors.getAsJsonArray("samples")) {
			JsonObject c = element.getAsJsonObject();
			String scene = Ids.sceneId(c.get("scene_index").getAsInt());
			String sample = Ids.sampleId(scene, c.get("viewpoint_index").getAsInt());
			assertEquals(c.get("scene_id").getAsString(), scene);
			assertEquals(c.get("sample_id").getAsString(), sample);
			assertEquals(c.get("shard").getAsString(), Ids.shard(sample));
			assertEquals(c.get("rgb_path_train").getAsString(), Ids.rgbPath(Split.TRAIN, sample));
			assertEquals(c.get("mask_path_test").getAsString(), Ids.maskPath(Split.TEST, sample));
		}

		for (JsonElement element : vectors.getAsJsonArray("datasets")) {
			JsonObject c = element.getAsJsonObject();
			assertEquals(c.get("dataset_id").getAsString(), Ids.datasetId(c.get("name").getAsString(), c.get("config_sha256").getAsString()));
		}
	}

	@Test
	void sceneSlotsMatchVectors() {
		for (JsonElement element : Fixtures.json(Fixtures.vectors().resolve("scene-slots.json")).getAsJsonArray("cases")) {
			JsonObject c = element.getAsJsonObject();
			var ratios = c.getAsJsonArray("ratios");
			String config = """
				{"dataset": {"name": "vectors", "seed": %d, "difficulty": 1},
				 "target": {"present_fraction": %s},
				 "quotas": {"accepted_scenes": %d},
				 "splits": {"train": %s, "validation": %s, "test": %s},
				 "output": {"root": "/srv/datasets"}}
				""".formatted(
					c.get("dataset_seed").getAsLong(),
					c.get("target_present_scenes").getAsDouble() / c.get("accepted_scenes").getAsInt(),
					c.get("accepted_scenes").getAsInt(),
					ratios.get(0), ratios.get(1), ratios.get(2)
				);
			var resolved = io.github.thedeadbolt.honeyfactory.config.ConfigResolver.resolve(config);
			assertEquals(c.get("target_present_scenes").getAsInt(), resolved.quotas().targetPresentScenes());
			List<SceneSlots.Slot> slots = SceneSlots.plan(resolved);
			var expected = c.getAsJsonArray("slots");
			for (int i = 0; i < slots.size(); i++) {
				JsonObject slot = expected.get(i).getAsJsonObject();
				assertEquals(slot.get("target_present").getAsBoolean(), slots.get(i).targetPresent(), "slot " + i);
				assertEquals(slot.get("split").getAsString(), slots.get(i).split().jsonName(), "slot " + i);
			}
		}
	}

	@Test
	void patchSlotsMatchVectors() {
		for (JsonElement element : Fixtures.json(Fixtures.vectors().resolve("patch-slots.json")).getAsJsonArray("cases")) {
			JsonObject c = element.getAsJsonObject();
			var ratios = c.getAsJsonArray("ratios");
			List<SceneSlots.Slot> slots = SceneSlots.planPatches(c.get("dataset_seed").getAsLong(), c.get("accepted_scenes").getAsInt(), c.get("target_present_scenes").getAsInt(),
				c.get("scenes_per_patch").getAsInt(), new double[] {ratios.get(0).getAsDouble(), ratios.get(1).getAsDouble(), ratios.get(2).getAsDouble()});
			var expected = c.getAsJsonArray("slots");
			assertEquals(expected.size(), slots.size());
			for (int i = 0; i < slots.size(); i++) {
				JsonObject slot = expected.get(i).getAsJsonObject();
				assertEquals(slot.get("target_present").getAsBoolean(), slots.get(i).targetPresent(), "slot " + i);
				assertEquals(slot.get("split").getAsString(), slots.get(i).split().jsonName(), "slot " + i);
				assertEquals(slot.get("patch").getAsInt(), slots.get(i).group(), "slot " + i);
			}
		}
	}
}
