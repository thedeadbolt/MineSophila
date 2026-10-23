package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;

/**
 * A generated scene plan and the arena-relative block its viewpoints aim at: the target
 * in a target-present scene, or the cell a target would occupy in a target-absent one, so
 * both classes share one viewpoint distribution.
 */
public record GeneratedScene(int sceneIndex, int attemptIndex, SceneSeeds seeds, ScenePlan plan, int[] aimBlock) {
}
