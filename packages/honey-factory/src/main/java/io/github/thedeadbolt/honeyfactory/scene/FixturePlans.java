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


	/** Three blocks of {@code state} centred in front of the target at z = 19. */
	private static void row(List<Placement> delta, int y, IntFunction<String> state) {
		for (int x = 15; x <= 17; x++) {
			delta.add(new Placement(x, y, 19, state.apply(x)));
		}
	}
}
