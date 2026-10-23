package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;

/**
 * Geometry of the reusable arena in the dedicated void dimension.
 *
 * <p>The core is the configured footprint and build height above {@link #ORIGIN}; its
 * floor is the core's lowest layer. The reset volume adds the configured margin on the
 * four horizontal sides and above the core, but not below the floor, which every
 * template restores. Every arena write must stay inside {@link #OWNED}; the staging
 * platform lies outside it.
 */
public record ArenaLayout(BlockBox core, BlockBox resetVolume, int margin) {
	/** The void arena dimension's ID. */
	public static final String DIMENSION_ID = "honey_factory:arena";

	/** World position of the core's minimum corner. */
	public static final int[] ORIGIN = {0, 64, 0};

	/** Region a dataset world owns for arena scenes; covers the largest valid configuration. */
	public static final BlockBox OWNED = new BlockBox(-64, 64, -64, 319, 319, 319);

	/** Platform players wait on while the arena resets; owned, but outside {@link #OWNED}. */
	public static final BlockBox STAGING_PLATFORM = new BlockBox(-130, 63, -130, -126, 63, -126);

	/** Feet position at the centre of the staging platform. */
	public static final double[] STAGING_POSITION = {-127.5, 64.0, -127.5};

	public static ArenaLayout of(ResolvedConfig.Arena arena) {
		BlockBox core = new BlockBox(
			ORIGIN[0], ORIGIN[1], ORIGIN[2],
			ORIGIN[0] + arena.width() - 1, ORIGIN[1] + arena.height() - 1, ORIGIN[2] + arena.length() - 1
		);
		int margin = arena.margin();
		BlockBox reset = new BlockBox(
			core.minX() - margin, core.minY(), core.minZ() - margin,
			core.maxX() + margin, core.maxY() + margin, core.maxZ() + margin
		);
		if (!OWNED.contains(reset)) {
			throw new IllegalArgumentException("Arena reset volume " + reset + " exceeds the owned region " + OWNED);
		}

		return new ArenaLayout(core, reset, margin);
	}
}
