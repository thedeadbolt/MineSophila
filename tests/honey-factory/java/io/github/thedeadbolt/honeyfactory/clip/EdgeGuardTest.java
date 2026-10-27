package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The guard on the executed command uses the scene's true layout: drift and scripts may take
 * the body close to a ledge, never over it. The teacher's command is never changed.
 */
class EdgeGuardTest {
	/** A 16 × 16 platform (top y = 4, feet 5) in the middle of a 32 × 32 arena with nothing around it. */
	static final BlockBox CORE = new BlockBox(0, 0, 0, 31, 9, 31);
	static final SightGrid PLATFORM = new SightGrid() {
		@Override
		public boolean solid(int x, int y, int z) {
			return y <= 4 && x >= 8 && x <= 23 && z >= 8 && z <= 23;
		}

		@Override
		public boolean blocks(int x, int y, int z, double[] from, double[] to) {
			return solid(x, y, z);
		}
	};
	private static final MotorCommand WALK = new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0);

	@Test
	void stopsAtThePlatformEdge() {
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		MotorCommand guarded = guard.apply(WALK, new CameraPose(15.5, 5.0, 23.4, 0.0f, 0.0f));
		assertEquals(0.0, guarded.forward());
		assertEquals(WALK.turnRate(), guarded.turnRate());
	}

	@Test
	void leavesSafeCommandsAlone() {
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		assertSame(WALK, guard.apply(WALK, new CameraPose(15.5, 5.0, 15.5, 0.0f, 0.0f)));
		MotorCommand back = new MotorCommand(-1.0, 0.0, 0.0, 0.0, false, 0);
		assertSame(back, guard.apply(back, new CameraPose(15.5, 5.0, 23.4, 0.0f, 0.0f)), "walking back from the edge is safe");
	}

	@Test
	void walksAlongTheVeryEdge() {
		// The body's centre 0.2 inside the -z edge, walking along it (+x) and slightly towards it:
		// part of its width hangs over the drop, but Minecraft holds a body while any of it stands on a block.
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		assertSame(WALK, guard.apply(WALK, new CameraPose(15.5, 5.0, 8.2, -90.0f, 0.0f)));
		assertSame(WALK, guard.apply(WALK, new CameraPose(15.5, 5.0, 8.6, -80.0f, 0.0f)));
	}

	@Test
	void driftedWalksNeverLeaveThePlatform() {
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		SplittableRandom random = new SplittableRandom(9);
		Drift drift = new Drift(4L, new ClipSettings.Drift(0.5, 6.0, 9.0, 1.0), MotorLimits.DEFAULT);
		double x = 15.5;
		double z = 15.5;
		double yaw = 0.0;
		double closest = Double.MAX_VALUE;
		for (int tick = 0; tick < 5000; tick++) {
			MotorCommand meant = new MotorCommand(1.0, 0.0, random.nextDouble(-3.0, 3.0), 0.0, false, 0);
			MotorCommand executed = guard.apply(drift.apply(meant), new CameraPose(x, 5.0, z, (float) yaw, 0.0f));
			yaw += executed.turnRate();
			x -= Math.sin(Math.toRadians(yaw)) * 0.2158 * executed.forward();
			z += Math.cos(Math.toRadians(yaw)) * 0.2158 * executed.forward();
			assertTrue(x > 8.0 && x < 24.0 && z > 8.0 && z < 24.0, "tick " + tick + ": left the platform at " + x + ", " + z);
			closest = Math.min(closest, Math.min(Math.min(x - 8.0, 24.0 - x), Math.min(z - 8.0, 24.0 - z)));
		}

		assertTrue(closest < 1.0, "the walk came close to an edge: " + closest);
	}

	@Test
	void hazardBearingPointsAtTheNearestEdge() {
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		// 2 blocks from the +z edge: facing +z the edge is ahead; facing -x (yaw 90) it is to the left (turning negative reaches +z).
		assertEquals(0.0, guard.hazardBearing(new CameraPose(15.5, 5.0, 21.5, 0.0f, 0.0f), 4.0), 12.0);
		assertEquals(-90.0, guard.hazardBearing(new CameraPose(15.5, 5.0, 21.5, 90.0f, 0.0f), 4.0), 12.0);
		assertTrue(Double.isNaN(guard.hazardBearing(new CameraPose(15.5, 5.0, 15.5, 0.0f, 0.0f), 4.0)), "nothing within 4 blocks of the middle");
	}

	@Test
	void clearRunMeasuresTheWayToTheEdge() {
		EdgeGuard guard = new EdgeGuard(PLATFORM, CORE);
		assertEquals(7.0, guard.clearRun(new CameraPose(15.5, 5.0, 16.0, 0.0f, 0.0f), 0.0, 20.0), 0.5);
		assertEquals(3.0, guard.clearRun(new CameraPose(15.5, 5.0, 16.0, 0.0f, 0.0f), 0.0, 3.0), 1e-9);
	}
}
