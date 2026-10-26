package io.github.thedeadbolt.honeyfactory.teacher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Searching covers the reachable space systematically. Through the clips' panorama camera it
 * sees at least 90% of it within 200 ticks; through a 90-degree camera, where the difference
 * shows most, it covers at least {@value #MIN_ADVANTAGE} times as much as wandering by tick
 * 200 (measured: 1.31 to 1.54 among clutter, everything on an open floor).
 */
class SearchBehaviourTest {
	static final double MIN_ADVANTAGE = 1.25;
	private static final CaptureProjection NARROW = CaptureProjection.of(CaptureMode.PERSPECTIVE_90, 64, 64, null);
	private static final CaptureProjection PANORAMA = CaptureProjection.of(CaptureMode.PANORAMA_310, 248, 128, 64);

	private static Grids cluttered(long seed) {
		Grids grid = new Grids(33, 6, 33);
		SplittableRandom random = new SplittableRandom(seed);
		for (int i = 0; i < 40; i++) {
			grid.wall(random.nextInt(1, 32), random.nextInt(1, 32), 3);
		}

		for (int z = 8; z <= 24; z++) {
			grid.wall(20, z, 3);
		}

		return grid.clear(16, 16);
	}

	/** Coverage of reachable cells after {@code ticks}, driving the body with {@code driver}. */
	private static double coverage(Grids grid, CaptureProjection camera, int ticks, Function<TeacherInput, MotorCommand> driver) {
		TeacherContext context = new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, 1.62, camera, 1.5);
		BodySim body = new BodySim(grid, new CameraPose(16.5, 1.0, 16.5, 0.0f, 0.0f));
		Set<Long> reachable = SeenMap.reachable(grid, grid.core, new int[] {16, 1, 16});
		SeenMap seen = new SeenMap(grid.core);
		HoneyMemory memory = new HoneyMemory();
		for (int tick = 1; tick <= ticks; tick++) {
			body.step(driver.apply(Perception.perceive(tick, body.pose(), body.senses(), null, 0, context, seen, memory)));
		}

		seen.observe(body.pose(), 1.62, camera, grid);
		return seen.coverage(reachable);
	}

	private static Function<TeacherInput, MotorCommand> searching(Grids grid, CaptureProjection camera) {
		RealizableTeacher teacher = new RealizableTeacher(60);
		teacher.reset(new TeacherContext(grid, grid.core, MotorLimits.DEFAULT, 50, 1.62, camera, 1.5));
		return input -> teacher.decide(input).command();
	}

	/** A random walk: full speed, turning by a rate drawn afresh every 10 ticks. */
	private static Function<TeacherInput, MotorCommand> wandering(long seed) {
		SplittableRandom random = new SplittableRandom(seed);
		double[] turn = {0.0};
		return input -> {
			if (input.tick() % 10 == 1) {
				turn[0] = random.nextDouble(-9.0, 9.0);
			}

			return new MotorCommand(1.0, 0.0, turn[0], -Math.clamp(input.pose().pitchDegrees(), -6.0, 6.0), false, 0);
		};
	}

	@Test
	void panoramaCoversMostOfTheArena() {
		Grids open = new Grids(33, 6, 33);
		double search = coverage(open, PANORAMA, 200, searching(open, PANORAMA));
		assertTrue(search >= 0.9, "open floor, after 200 ticks: " + search);
		for (long seed = 1; seed <= 3; seed++) {
			Grids grid = cluttered(seed);
			double cluttered = coverage(grid, PANORAMA, 200, searching(grid, PANORAMA));
			assertTrue(cluttered >= 0.9, "clutter seed " + seed + ", after 200 ticks: " + cluttered);
		}
	}

	@Test
	void searchBeatsWandering() {
		for (long seed = 0; seed <= 3; seed++) {
			long layout = seed;
			Function<Long, Grids> scene = s -> s == 0 ? new Grids(33, 6, 33) : cluttered(s);
			Grids grid = scene.apply(layout);
			double search = coverage(grid, NARROW, 200, searching(grid, NARROW));
			double wander = 0.0;
			for (long walk = 1; walk <= 5; walk++) {
				wander += coverage(scene.apply(layout), NARROW, 200, wandering(walk)) / 5;
			}

			// Coverage cannot pass 100%, so a search that has seen nearly everything also passes.
			assertTrue(search >= MIN_ADVANTAGE * wander || search >= 0.95, "layout " + seed + ": search " + search + " against wandering " + wander + " at tick 200");
		}
	}
}
