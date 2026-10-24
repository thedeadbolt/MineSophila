package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;

/** Selects the scene generator for a resolved configuration's scene recipe. */
public final class SceneGenerators {
	private SceneGenerators() {
	}

	public static SceneGenerator forConfig(ResolvedConfig config) {
		return config.scene().base() instanceof SceneBase.NaturalTerrain ? new NaturalGenerator(config) : new RecipeGenerator(config);
	}
}
