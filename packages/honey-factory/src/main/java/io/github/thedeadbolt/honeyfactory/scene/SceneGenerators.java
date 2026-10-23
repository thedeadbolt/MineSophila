package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;

/** Selects the scene generator for a resolved configuration's scene recipe. */
public final class SceneGenerators {
	private SceneGenerators() {
	}

	/** @throws UnsupportedOperationException for a level whose generator is not implemented yet */
	public static SceneGenerator forConfig(ResolvedConfig config) {
		if (config.scene() == null) {
			throw new UnsupportedOperationException("level " + config.difficulty() + " has no scene generator yet");
		}

		return new RecipeGenerator(config);
	}
}
