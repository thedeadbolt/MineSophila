package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import org.jspecify.annotations.Nullable;

/**
 * One target-present scene of a recipe for looking at in the game, with the default arena,
 * target and viewpoint settings: scene {@code index} of seed {@code seed}, attempt 0, at the
 * first placeable draw of viewpoint 0.
 */
public record ScenePreview(ArenaLayout arena, GeneratedScene scene, @Nullable ViewpointPlan viewpoint) {
	private static final ResolvedConfig DEFAULTS = ConfigResolver.resolve("""
		{"dataset": {"name": "preview", "seed": 0, "difficulty": 1}, "output": {"root": "/honey-factory-preview"}}
		""");

	public static ScenePreview of(SceneSpec spec, long seed, int index) {
		RecipeGenerator generator = new RecipeGenerator(spec, DEFAULTS.arena(), DEFAULTS.target().allowedBlockStates(), DEFAULTS.viewpoints());
		GeneratedScene scene = generator.scene(SceneSeeds.derive(seed, index, 0), true);
		ViewpointPlan viewpoint = null;
		for (int attempt = 0; attempt < DEFAULTS.viewpoints().attemptsPerViewpoint() && viewpoint == null; attempt++) {
			if (generator.viewpoint(scene, 0, attempt) instanceof ViewpointCandidate.Placeable placeable) {
				viewpoint = placeable.plan();
			}
		}

		return new ScenePreview(ArenaLayout.of(DEFAULTS.arena()), scene, viewpoint);
	}
}
