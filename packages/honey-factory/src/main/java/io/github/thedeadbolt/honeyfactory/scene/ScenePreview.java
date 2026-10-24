package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import org.jspecify.annotations.Nullable;

/**
 * One target-present scene of a recipe for looking at in the game, with the default arena,
 * target and viewpoint settings: scene {@code index} of seed {@code seed}, attempt 0, at the
 * first placeable draw of viewpoint 0. On a natural base it stands on the source patch that
 * scene would use in a dataset with that seed.
 */
public record ScenePreview(ArenaLayout arena, GeneratedScene scene, @Nullable ViewpointPlan viewpoint) {
	/** The default target block, for patch requests. */
	public static String targetBlockId() {
		return DEFAULTS.target().blockId();
	}

	private static final ResolvedConfig DEFAULTS = ConfigResolver.resolve("""
		{"dataset": {"name": "preview", "seed": 0, "difficulty": 1}, "output": {"root": "/honey-factory-preview"}}
		""");

	/** A platform recipe's preview; see {@link #of(SceneSpec, PreparedPatch, long, int)} for natural ones. */
	public static ScenePreview of(SceneSpec spec, long seed, int index) {
		RecipeGenerator generator = new RecipeGenerator(spec, DEFAULTS.arena(), DEFAULTS.target().allowedBlockStates(), DEFAULTS.viewpoints());
		return first(generator, generator.scene(SceneSeeds.derive(seed, index, 0), true), ArenaLayout.of(DEFAULTS.arena()));
	}

	/** A natural recipe's preview on {@code patch}, which must have been prepared for the spec's base. */
	public static ScenePreview of(SceneSpec spec, PreparedPatch patch, long seed, int index) {
		NaturalGenerator generator = new NaturalGenerator(spec, DEFAULTS.target().allowedBlockStates(), DEFAULTS.viewpoints());
		return first(generator, generator.scene(SceneSeeds.derive(seed, index, 0), true, patch), ArenaLayout.natural(generator.base()));
	}

	/** The patch request a natural recipe's preview needs: its scene's patch, as a dataset with this seed would use. */
	public static int patchIndex(SceneSpec spec, int index) {
		return spec.base() instanceof SceneBase.NaturalTerrain natural ? index / natural.scenesPerPatch() : 0;
	}

	private static ScenePreview first(SceneGenerator generator, GeneratedScene scene, ArenaLayout arena) {
		ViewpointPlan viewpoint = null;
		for (int attempt = 0; attempt < DEFAULTS.viewpoints().attemptsPerViewpoint() && viewpoint == null; attempt++) {
			if (generator.viewpoint(scene, 0, attempt) instanceof ViewpointCandidate.Placeable placeable) {
				viewpoint = placeable.plan();
			}
		}

		return new ScenePreview(arena, scene, viewpoint);
	}
}
