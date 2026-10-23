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
	/** Level-1 open-platform scene and viewpoint generation. */
	public static final String LEVEL1_GENERATOR = "level1-open-platform-v1";
	/** Level-2 clutter and distractor generation. */
	public static final String LEVEL2_GENERATOR = "level2-clutter-v1";
	/** Level-3 partial-hiding generation. */
	public static final String LEVEL3_GENERATOR = "level3-partial-hiding-v1";
	/** Level-4 constructed-environment generation. */
	public static final String LEVEL4_GENERATOR = "level4-constructed-v1";

	private Algorithms() {
	}

	/** The scene generator for a difficulty level, or null while that level has none. */
	public static @Nullable String sceneGenerator(int difficulty) {
		return switch (difficulty) {
			case 1 -> LEVEL1_GENERATOR;
			case 2 -> LEVEL2_GENERATOR;
			case 3 -> LEVEL3_GENERATOR;
			case 4 -> LEVEL4_GENERATOR;
			default -> null;
		};
	}
}
