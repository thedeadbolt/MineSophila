package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Level 1, open platform ({@value Algorithms#LEVEL1_GENERATOR}): the bare platform and, in
 * target-present scenes, the target block standing on it. Nothing else is placed, and
 * every viewpoint the platform allows is kept; see {@link PlatformGenerator}.
 */
public final class Level1Generator extends PlatformGenerator {
	public Level1Generator(ResolvedConfig config) {
		super(config, 1, Algorithms.LEVEL1_GENERATOR);
	}

	@Override
	protected List<Placement> layout(SeededRandom random, int[] aim, JsonObject parameters) {
		return List.of();
	}

	@Override
	protected @Nullable String checkSight(SolidGrid grid, double[] eye, int[] aim) {
		return null;
	}
}
