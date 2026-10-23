package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;

/**
 * Builds the scenes of one difficulty level from seeds alone. Generators never touch the
 * world; the same inputs always give the same plans and viewpoints.
 */
public interface SceneGenerator {
	/** Versioned identity, recorded as {@code generator.algorithms.scene_generator}. */
	String version();

	GeneratedScene scene(int sceneIndex, int attemptIndex, SceneSeeds seeds, boolean targetPresent);

	/**
	 * Draw {@code attemptIndex} for viewpoint {@code viewpointIndex}. Draws are independent,
	 * so re-sampling one viewpoint never changes another.
	 */
	ViewpointCandidate viewpoint(GeneratedScene scene, int viewpointIndex, int attemptIndex);
}
