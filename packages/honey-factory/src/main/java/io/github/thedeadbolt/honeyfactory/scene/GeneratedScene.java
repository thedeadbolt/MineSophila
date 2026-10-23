package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;

/**
 * A generated scene plan and the arena-relative block its viewpoints aim at: the target
 * in a target-present scene, or the cell a target would occupy in a target-absent one, so
 * both classes share one viewpoint distribution. {@code layoutParameters} records what the
 * generator drew for the layout, for the scene record.
 */
public record GeneratedScene(SceneSeeds seeds, ScenePlan plan, int[] aimBlock, JsonObject layoutParameters) {
	@Override
	public JsonObject layoutParameters() {
		return this.layoutParameters.deepCopy();
	}
}
