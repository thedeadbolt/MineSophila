package io.github.thedeadbolt.honeyfactory.contract;

/**
 * Versioned names of the contract's deterministic algorithms, as recorded in a manifest's
 * {@code generator.algorithms}. The schema README defines each one exactly.
 */
public final class Algorithms {
	public static final String CONFIG_HASH = CanonicalJson.ALGORITHM;
	public static final String SEEDS = SceneSeeds.ALGORITHM;
	public static final String IDS = Ids.ALGORITHM;
	public static final String SCENE_SLOTS = "scene-slots-v1";
	/** Scene slots grouped by source patch. */
	public static final String PATCH_SLOTS = "patch-slots-v1";
	public static final String LAYOUT = BlockLayout.ALGORITHM;
	/** Scene generation from a recipe of base, layout parts, sight rule and lighting. */
	public static final String SCENE_RECIPE = "scene-recipe-v1";

	private Algorithms() {
	}

	/** The slot algorithm of a split grouping. */
	public static String slotsFor(SplitGrouping grouping) {
		return grouping == SplitGrouping.SCENE ? SCENE_SLOTS : PATCH_SLOTS;
	}

	/** The scene generator of every difficulty level. */
	public static String sceneGenerator(int difficulty) {
		return SCENE_RECIPE;
	}
}
