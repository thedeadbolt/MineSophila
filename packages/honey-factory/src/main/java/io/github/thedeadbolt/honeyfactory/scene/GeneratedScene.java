package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import org.jspecify.annotations.Nullable;

/**
 * A generated scene plan and the arena-relative block its viewpoints aim at: the target
 * in a target-present scene, or the cell a target would occupy in a target-absent one, so
 * both classes share one viewpoint distribution. {@code layoutParameters} records what the
 * generator drew for the layout, for the scene record. A level-5 scene also carries the
 * terrain of its source patch's core, for its viewpoints.
 */
public record GeneratedScene(SceneSeeds seeds, ScenePlan plan, int[] aimBlock, JsonObject layoutParameters, @Nullable PatchTerrain terrain) {
	public GeneratedScene(SceneSeeds seeds, ScenePlan plan, int[] aimBlock, JsonObject layoutParameters) {
		this(seeds, plan, aimBlock, layoutParameters, null);
	}

	@Override
	public JsonObject layoutParameters() {
		return this.layoutParameters.deepCopy();
	}
}
