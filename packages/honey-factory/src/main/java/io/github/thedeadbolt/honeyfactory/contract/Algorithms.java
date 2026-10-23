package io.github.thedeadbolt.honeyfactory.contract;

import org.jspecify.annotations.Nullable;

/**
 * Versioned names of the contract's deterministic algorithms, as recorded in a manifest's
 * {@code generator.algorithms}. The schema README defines each one exactly.
 */
public final class Algorithms {
	public static final String CONFIG_HASH = CanonicalJson.ALGORITHM;
	public static final String SEEDS = SceneSeeds.ALGORITHM;
	public static final String IDS = Ids.ALGORITHM;
	public static final String SCENE_SLOTS = "scene-slots-v1";
	public static final String LAYOUT = BlockLayout.ALGORITHM;
	/** Scene generation from a recipe of base, layout parts, sight rule and lighting. */
	public static final String SCENE_RECIPE = "scene-recipe-v1";

	private Algorithms() {
	}

	/** The scene generator for a difficulty level, or null while that level has none. */
	public static @Nullable String sceneGenerator(int difficulty) {
		return difficulty <= 4 ? SCENE_RECIPE : null;
	}
}
