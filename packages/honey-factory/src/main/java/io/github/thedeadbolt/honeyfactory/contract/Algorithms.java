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

	private Algorithms() {
	}

	/** The scene generator for a difficulty level, or null while that level has none. */
	public static @Nullable String sceneGenerator(int difficulty) {
		return difficulty == 1 ? LEVEL1_GENERATOR : null;
	}
}
