package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Fixed scenes for debugging, reproducibility tests and the mask prototype gate, on the
 * default 32x32x16 arena: a honey block on a white concrete floor and one viewpoint each.
 *
 * <p>The occlusion fixtures share M0's geometry: the target at (16, 1, 16), seen from the
 * south, with occluders in the row z = 19 between them. Partial puts a stone column whose
 * edge projects through the target centre; full puts a 2 x 2 stone wall in front of it;
 * stairs, fence and leaves put a three-block row of those shapes there instead, and slab
 * a floating row of bottom slabs one block up, since a slab on the floor stays below the
 * line of sight.
 * Near, far, edge and out-of-view vary only the camera, or the target position for far.
 */
public final class FixturePlans {
	public static final int SIZE = 32;
	public static final int HEIGHT = 16;
	public static final String FLOOR = "minecraft:white_concrete";
	public static final Placement TARGET = new Placement(16, 1, 16, "minecraft:honey_block");
	public static final Placement FAR_TARGET = new Placement(16, 1, 1, "minecraft:honey_block");
	public static final ArenaLayout ARENA = ArenaLayout.of(new ResolvedConfig.Arena(SIZE, SIZE, HEIGHT, 4));

	private static final double EYE = ConfigResolver.EYE_HEIGHT_BLOCKS;
	private static final double FEET_X = 16 - 5.0 / 12.0;

	/** Viewpoint shared by the occlusion fixtures; the partial occluder's edge projects through the target centre. */
	public static final ViewpointPlan VIEWPOINT = ViewpointPlan.lookingAt(FEET_X, 1.0, 22.5, EYE, 16, 1, 16);

	public enum Fixture {
		EMPTY,
		PLATFORM,
		OPEN,
		PARTIAL,
		FULL,
		SLAB,
		STAIRS,
		FENCE,
		LEAVES,
		NEAR,
		FAR,
		EDGE,
		OUT_OF_VIEW;

		public String commandName() {
			return name().toLowerCase(Locale.ROOT);
		}

		/** Whether this fixture's visible mask must match an approved golden mask. */
		public boolean golden() {
			return this == PARTIAL || this == SLAB || this == STAIRS || this == FENCE || this == LEAVES;
		}
	}

	private FixturePlans() {
	}

	public static ScenePlan plan(Fixture fixture) {
		Template template = fixture == Fixture.EMPTY
			? new Template("empty", 1, SIZE, SIZE, HEIGHT, List.of())
			: Template.platform("platform", 1, SIZE, SIZE, HEIGHT, FLOOR);
		List<Placement> delta = new ArrayList<>();
		Placement target = switch (fixture) {
			case EMPTY, PLATFORM -> null;
			case FAR -> FAR_TARGET;
			default -> TARGET;
		};
		if (target != null) {
			delta.add(target);
		}

		switch (fixture) {
			case PARTIAL, FULL -> {
				for (int x = 15; x <= 16; x++) {
					boolean occludes = fixture == Fixture.FULL || x == 15;
					for (int y = 1; y <= 2 && occludes; y++) {
						delta.add(new Placement(x, y, 19, "minecraft:stone"));
					}
				}
			}
			case SLAB -> row(delta, 2, x -> "minecraft:stone_slab[type=bottom,waterlogged=false]");
			case STAIRS -> row(delta, 1, x -> "minecraft:stone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
			case FENCE -> row(delta, 1, x -> "minecraft:oak_fence[east=" + (x < 17) + ",north=false,south=false,waterlogged=false,west=" + (x > 15) + "]");
			case LEAVES -> row(delta, 1, x -> "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
			default -> {
			}
		}

		return new ScenePlan(template, delta, target, 6000, Weather.CLEAR);
	}

	/** Each fixture's camera; edge and out-of-view turn the shared viewpoint by 45 and 180 degrees. */
	public static ViewpointPlan viewpoint(Fixture fixture) {
		return switch (fixture) {
			case NEAR -> ViewpointPlan.lookingAt(FEET_X, 1.0, 18.2, EYE, 16, 1, 16);
			case FAR -> ViewpointPlan.lookingAt(16.5, 1.0, 30.5, EYE, 16, 1, 1);
			case EDGE -> VIEWPOINT.turned(45.0);
			case OUT_OF_VIEW -> VIEWPOINT.turned(180.0);
			default -> VIEWPOINT;
		};
	}


	/**
	 * Lanes of the movement course, west to east: each obstacle is three blocks wide,
	 * centred on {@code centreX}, and starts at z = {@value #COURSE_OBSTACLE_Z}, so a player
	 * walking north from {@link #courseStart} meets it after about 3.2 blocks.
	 */
	public enum Lane {
		/** A one-block step onto a raised floor eight blocks deep: auto-jump climbs it, walking alone does not. */
		STEP(4),
		/** A wall two blocks high: too high for auto-jump. */
		WALL(10),
		/** A floor of bottom slabs: walked up without jumping. */
		SLAB(16),
		/** One straight stair onto the raised floor: walked up without jumping. */
		STAIRS(22),
		/** A fence row, 1.5 blocks high: too high for auto-jump. */
		FENCE(28);

		private final int centreX;

		Lane(int centreX) {
			this.centreX = centreX;
		}

		public int centreX() {
			return this.centreX;
		}
	}

	/** Arena-relative z of each lane obstacle's south face; raised floors run north from it to z = 10. */
	public static final int COURSE_OBSTACLE_Z = 18;

	/**
	 * The movement course on the platform arena: one lane per {@link Lane} and no target.
	 * Floor top is y = 1, so a player on the floor stands at arena-relative y = 1, on a slab
	 * at 1.5 and on the raised floor at 2.
	 */
	public static ScenePlan course() {
		List<Placement> delta = new ArrayList<>();
		for (Lane lane : Lane.values()) {
			for (int x = lane.centreX() - 1; x <= lane.centreX() + 1; x++) {
				int z = COURSE_OBSTACLE_Z;
				switch (lane) {
					case STEP -> raisedFloor(delta, x, z, "minecraft:stone");
					case WALL -> {
						delta.add(new Placement(x, 1, z, "minecraft:stone"));
						delta.add(new Placement(x, 2, z, "minecraft:stone"));
					}
					case SLAB -> {
						for (int row = 10; row <= z; row++) {
							delta.add(new Placement(x, 1, row, "minecraft:stone_slab[type=bottom,waterlogged=false]"));
						}
					}
					case STAIRS -> {
						delta.add(new Placement(x, 1, z, "minecraft:stone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
						raisedFloor(delta, x, z - 1, "minecraft:stone");
					}
					case FENCE -> delta.add(new Placement(x, 1, z, "minecraft:oak_fence[east=" + (x < lane.centreX() + 1) + ",north=false,south=false,waterlogged=false,west=" + (x > lane.centreX() - 1) + "]"));
				}
			}
		}

		return new ScenePlan(Template.platform("platform", 1, SIZE, SIZE, HEIGHT, FLOOR), delta, null, 6000, Weather.CLEAR);
	}

	/** Feet position facing north, four and a half blocks south of {@code lane}'s obstacle. */
	public static ViewpointPlan courseStart(Lane lane) {
		return new ViewpointPlan(lane.centreX() + 0.5, 1.0, COURSE_OBSTACLE_Z + 4.5, 180.0F, 0.0F);
	}

	private static void raisedFloor(List<Placement> delta, int x, int fromZ, String state) {
		for (int z = 10; z <= fromZ; z++) {
			delta.add(new Placement(x, 1, z, state));
		}
	}

	/** Three blocks of {@code state} centred in front of the target at z = 19. */
	private static void row(List<Placement> delta, int y, IntFunction<String> state) {
		for (int x = 15; x <= 17; x++) {
			delta.add(new Placement(x, y, 19, state.apply(x)));
		}
	}
}
