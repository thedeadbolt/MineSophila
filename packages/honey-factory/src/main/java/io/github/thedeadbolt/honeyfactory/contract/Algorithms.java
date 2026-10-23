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
	public static final String LAYOUT = BlockLayout.ALGORITHM;

	private Algorithms() {
	}
}
