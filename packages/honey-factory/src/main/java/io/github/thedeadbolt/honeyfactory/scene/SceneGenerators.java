package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;

/** Selects the scene generator for a resolved configuration's difficulty level. */
public final class SceneGenerators {
	private SceneGenerators() {
	}

	/** @throws UnsupportedOperationException for a level whose generator is not implemented yet */
	public static SceneGenerator forConfig(ResolvedConfig config) {
		return switch (config.difficulty()) {
			case 1 -> new Level1Generator(config);
			case 2 -> new Level2Generator(config);
			case 3 -> new Level3Generator(config);
			case 4 -> new Level4Generator(config);
			default -> throw new UnsupportedOperationException("level " + config.difficulty() + " has no scene generator yet");
		};
	}
}
