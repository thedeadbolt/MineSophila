package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;

/**
 * Geometry of the reusable arena in the dedicated void dimension: the platform arena for
 * generated scenes, or the natural region for copies of source patches.
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

	/**
	 * Region a dataset world owns for level-5 terrain copies, far enough from the platform
	 * arena and the staging platform that a player on either never loads its chunks.
	 */
	public static final BlockBox NATURAL_OWNED = new BlockBox(4096, 64, 4096, 5375, 319, 5375);

	/** World position of a natural core's minimum corner; padding of up to 512 blocks fits around it. */
	public static final int[] NATURAL_ORIGIN = {4608, 64, 4608};

	/** The arena of {@code base}: the platform arena for a platform, the natural region for a source patch. */
	public static ArenaLayout of(SceneBase base, ResolvedConfig.Arena arena) {
		return base instanceof SceneBase.NaturalTerrain natural ? natural(natural) : of(arena);
	}

	/**
	 * A source patch's arena: the core at {@link #NATURAL_ORIGIN}, and a reset volume adding
	 * the context padding on the four horizontal sides, as tall as the copied window. Both
	 * are whole chunks horizontally.
	 */
	public static ArenaLayout natural(SceneBase.NaturalTerrain base) {
		int[] o = NATURAL_ORIGIN;
		BlockBox core = new BlockBox(o[0], o[1], o[2], o[0] + base.coreWidth() - 1, o[1] + base.height() - 1, o[2] + base.coreLength() - 1);
		int padding = base.contextPadding();
		BlockBox reset = new BlockBox(core.minX() - padding, core.minY(), core.minZ() - padding, core.maxX() + padding, core.maxY(), core.maxZ() + padding);
		if (!NATURAL_OWNED.contains(reset)) {
			throw new IllegalArgumentException("Natural reset volume " + reset + " exceeds the owned region " + NATURAL_OWNED);
		}

		return new ArenaLayout(core, reset, padding);
	}

	/** Whether this is a source patch's arena. */
	public boolean natural() {
		return NATURAL_OWNED.contains(this.resetVolume);
	}

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
