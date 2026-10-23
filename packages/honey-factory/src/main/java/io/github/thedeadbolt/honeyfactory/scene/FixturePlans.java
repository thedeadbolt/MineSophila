package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed scenes for debugging and reproducibility tests on the default 32x32x16 arena.
 * The occlusion fixtures reproduce M0's geometry: a honey block on a white concrete floor,
 * seen from one viewpoint, behind no stone, a stone column covering its left half, or a
 * stone wall covering all of it.
 */
public final class FixturePlans {
	public static final int SIZE = 32;
	public static final int HEIGHT = 16;
	public static final String FLOOR = "minecraft:white_concrete";
	public static final Placement TARGET = new Placement(16, 1, 16, "minecraft:honey_block");
	public static final ArenaLayout ARENA = ArenaLayout.of(new ResolvedConfig.Arena(SIZE, SIZE, HEIGHT, 4));

	/** Viewpoint shared by the occlusion fixtures; the partial occluder's edge projects through the target centre. */
	public static final ViewpointPlan VIEWPOINT = ViewpointPlan.lookingAt(16 - 5.0 / 12.0, 1.0, 22.5, ConfigResolver.EYE_HEIGHT_BLOCKS, 16, 1, 16);

	public enum Fixture {
		EMPTY,
		PLATFORM,
		OPEN,
		PARTIAL,
		FULL;

		public String commandName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private FixturePlans() {
	}

	public static ScenePlan plan(Fixture fixture) {
		Template template = fixture == Fixture.EMPTY
			? new Template("empty", 1, SIZE, SIZE, HEIGHT, List.of())
			: Template.platform(SIZE, SIZE, HEIGHT, FLOOR);
		List<Placement> delta = new ArrayList<>();
		Placement target = null;
		if (fixture == Fixture.OPEN || fixture == Fixture.PARTIAL || fixture == Fixture.FULL) {
			target = TARGET;
			delta.add(TARGET);
			for (int x = 15; x <= 16; x++) {
				boolean occludes = fixture == Fixture.FULL || fixture == Fixture.PARTIAL && x == 15;
				for (int y = 1; y <= 2 && occludes; y++) {
					delta.add(new Placement(x, y, 19, "minecraft:stone"));
				}
			}
		}

		return new ScenePlan(template, delta, target, 6000, Weather.CLEAR);
	}
}
