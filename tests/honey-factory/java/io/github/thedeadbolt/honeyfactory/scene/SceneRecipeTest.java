package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Editing recipes in Java and through the configuration's {@code scene} section. */
class SceneRecipeTest {
	private static ResolvedConfig resolve(int level, String scene) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "recipe-test", "seed": 5, "difficulty": %d}, "scene": %s, "output": {"root": "/tmp/honey"}}
			""".formatted(level, scene));
	}

	@Test
	void aRecipeIsEditedThenFrozen() {
		SceneRecipe recipe = SceneRecipe.of("level2");
		SceneSpec before = recipe.build();
		recipe.addPart(NearAimBlocksPart.DEFAULT).lighting(18000, Weather.RAIN).sight(SightRule.SOME_CLEAR);
		recipe.parts().removeFirst();
		SceneSpec after = recipe.build();
		assertEquals(List.of(ClutterPart.DEFAULT), before.parts(), "a built spec ignores later edits");
		assertEquals(List.of(NearAimBlocksPart.DEFAULT), after.parts());
		assertEquals(Lighting.fixed(18000, Weather.RAIN), after.lighting());
		assertEquals(SightRule.SOME_CLEAR, after.sight());
		assertEquals("level2", after.preset());
		assertEquals(ScenePresets.spec("level2"), SceneRecipe.of("level2").build(), "presets are not changed by edits to a copy");
	}

	@Test
	void everyPresetRoundTripsThroughJson() {
		for (String name : ScenePresets.names()) {
			SceneSpec spec = ScenePresets.spec(name);
			assertEquals(spec, SceneSpec.read(JsonFields.of(spec.toJson()), "level1"));
		}
	}

	@Test
	void theConfigurationPicksThePresetOfItsLevelAndOverridesIt() {
		assertEquals(ScenePresets.spec("level3"), resolve(3, "{}").scene());
		ResolvedConfig mixed = resolve(3, """
			{"parts": [{"type": "hiding_structure", "kinds": ["wall"]}, {"type": "constructed_setting", "kinds": ["courtyard"]}],
			 "lighting": {"time_of_day_ticks": [1000, 11000], "weathers": ["clear", "rain"]}}
			""");
		SceneSpec spec = mixed.scene();
		assertEquals("level3", spec.preset());
		assertEquals(List.of(new HidingStructurePart(List.of("wall")), new ConstructedSettingPart(List.of("courtyard"))), spec.parts());
		assertEquals(SightRule.PARTLY_HIDDEN, spec.sight(), "unset members keep the preset's");
		assertEquals(Lighting.of(new IntRange(1000, 11000), Weather.CLEAR, Weather.RAIN), spec.lighting());
		assertNotEquals(resolve(3, "{}").sha256(), mixed.sha256(), "the recipe is part of the configuration hash");
		assertEquals(ScenePresets.spec("level4"), resolve(1, "{\"preset\": \"level4\"}").scene(), "a level can use another level's preset");
		assertEquals(new ClutterPart(new IntRange(3, 3), 2), resolve(2, "{\"parts\": [{\"type\": \"clutter\", \"items\": 3}]}").scene().parts().getFirst());
		assertEquals(Lighting.fixed(14000, Weather.THUNDER), resolve(1, "{\"lighting\": {\"time_of_day_ticks\": 14000, \"weather\": \"thunder\"}}").scene().lighting(),
			"level 1 may use other lighting too");
	}

	@Test
	void invalidRecipesNameTheSetting() {
		assertEquals("/scene/preset", assertThrows(ContractException.class, () -> resolve(1, "{\"preset\": \"level9\"}")).path());
		assertEquals("/scene/parts/0/type", assertThrows(ContractException.class, () -> resolve(1, "{\"parts\": [{\"type\": \"volcano\"}]}")).path());
		assertEquals("/scene/parts/0/kinds", assertThrows(ContractException.class, () -> resolve(1, "{\"parts\": [{\"type\": \"hiding_structure\", \"kinds\": [\"moat\"]}]}")).path());
		assertEquals("/scene/parts/0/items", assertThrows(ContractException.class, () -> resolve(1, "{\"parts\": [{\"type\": \"clutter\", \"items\": [5, 2]}]}")).path());
		assertEquals("/scene/lighting/time_of_day_ticks", assertThrows(ContractException.class, () -> resolve(1, "{\"lighting\": {\"time_of_day_ticks\": 24000}}")).path());
		assertEquals("/scene/lighting/weathers", assertThrows(ContractException.class, () -> resolve(1, "{\"lighting\": {\"weathers\": []}}")).path());
		assertEquals("/scene/sight", assertThrows(ContractException.class, () -> resolve(1, "{\"sight\": \"blind\"}")).path());
		assertEquals("/scene/base/floor", assertThrows(ContractException.class, () -> resolve(1, "{\"base\": {\"type\": \"platform\", \"floor\": \"minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]\"}}")).path());
	}

	@Test
	void aMissingSeedIsDrawnAndRecorded() {
		ResolvedConfig first = ConfigResolver.resolve("{\"dataset\": {\"name\": \"no-seed\", \"difficulty\": 1}, \"output\": {\"root\": \"/tmp/honey\"}}");
		ResolvedConfig second = ConfigResolver.resolve("{\"dataset\": {\"name\": \"no-seed\", \"difficulty\": 1}, \"output\": {\"root\": \"/tmp/honey\"}}");
		assertNotEquals(first.seed(), second.seed());
		assertEquals(first.seed(), first.toJson().getAsJsonObject("generator").get("seed").getAsLong(), "the drawn seed is recorded");
		assertTrue(first.seed() >= 0);
	}
}
