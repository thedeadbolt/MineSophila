package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Drift: smooth, temporally correlated departures on about 10% of ticks, within the motor limits. */
class DriftTest {
	private static final ClipSettings.Drift SETTINGS = new ClipSettings.Drift(0.10, 6.0, 6.0, 0.5);
	private static final MotorCommand WALK = new MotorCommand(1.0, 0.0, 8.0, 0.0, false, 0);

	@Test
	void activeShareNearRate() {
		Drift drift = new Drift(11L, SETTINGS, MotorLimits.DEFAULT);
		int ticks = 100_000;
		int active = 0;
		int episodes = 0;
		boolean before = false;
		for (int tick = 1; tick <= ticks; tick++) {
			drift.apply(WALK);
			if (drift.active()) {
				active++;
				episodes += before ? 0 : 1;
			}

			before = drift.active();
		}

		assertEquals(0.10, active / (double) ticks, 0.01);
		assertEquals(6.0, active / (double) episodes, 0.5);
	}

	@Test
	void driftStaysWithinLimits() {
		Drift drift = new Drift(3L, new ClipSettings.Drift(0.5, 6.0, 9.0, 1.0), MotorLimits.DEFAULT);
		for (int tick = 0; tick < 10_000; tick++) {
			MotorCommand drifted = drift.apply(WALK);
			assertDoesNotThrow(() -> MotorLimits.DEFAULT.check(drifted));
		}
	}

	@Test
	void driftIsSmoothAndDeterministicPerSeed() {
		List<MotorCommand> first = new ArrayList<>();
		List<MotorCommand> second = new ArrayList<>();
		Drift a = new Drift(5L, SETTINGS, MotorLimits.DEFAULT);
		Drift b = new Drift(5L, SETTINGS, MotorLimits.DEFAULT);
		MotorCommand still = MotorCommand.IDLE;
		double largestJump = 0.0;
		double previous = 0.0;
		for (int tick = 0; tick < 2000; tick++) {
			first.add(a.apply(still));
			second.add(b.apply(still));
			largestJump = Math.max(largestJump, Math.abs(first.getLast().turnRate() - previous));
			previous = first.getLast().turnRate();
		}

		assertEquals(first, second);
		assertTrue(largestJump < SETTINGS.maxTurnRate(), "a drift episode rises and falls rather than jumping: " + largestJump);
		assertTrue(first.stream().anyMatch(command -> command.turnRate() != 0.0), "some ticks drift");
	}

	@Test
	void zeroRateNeverDrifts() {
		Drift drift = new Drift(5L, new ClipSettings.Drift(0.0, 6.0, 6.0, 0.5), MotorLimits.DEFAULT);
		for (int tick = 0; tick < 1000; tick++) {
			assertEquals(WALK, drift.apply(WALK));
		}
	}

	@Test
	void hazardEpisodesPushTowardsTheHazard() {
		ClipSettings.Drift hazardous = new ClipSettings.Drift(0.3, 6.0, 6.0, 0.5, 1.0);
		for (double bearing : new double[] {70.0, -70.0}) {
			Drift drift = new Drift(5L, hazardous, MotorLimits.DEFAULT, pose -> bearing);
			int drifting = 0;
			for (int tick = 0; tick < 2000; tick++) {
				MotorCommand drifted = drift.apply(MotorCommand.IDLE, POSE);
				if (drift.active()) {
					drifting++;
					assertTrue(Math.signum(drifted.turnRate()) == Math.signum(bearing) || drifted.turnRate() == 0.0, "turns towards the hazard at " + bearing + ": " + drifted);
					assertTrue(drifted.forward() >= 0.0, "walks towards the hazard: " + drifted);
					assertTrue(drift.towardsHazard());
				}
			}

			assertTrue(drifting > 100);
		}
	}

	@Test
	void withoutAHazardNearbyBumpsAreAsBefore() {
		ClipSettings.Drift hazardous = new ClipSettings.Drift(0.10, 6.0, 6.0, 0.5, 1.0);
		Drift plain = new Drift(5L, SETTINGS, MotorLimits.DEFAULT);
		Drift none = new Drift(5L, new ClipSettings.Drift(0.10, 6.0, 6.0, 0.5, 0.0), MotorLimits.DEFAULT, pose -> 45.0);
		Drift far = new Drift(5L, hazardous, MotorLimits.DEFAULT, pose -> Double.NaN);
		for (int tick = 0; tick < 2000; tick++) {
			MotorCommand expected = plain.apply(WALK);
			assertEquals(expected, none.apply(WALK, POSE), "a hazard fraction of 0 draws as before");
			MotorCommand drifted = far.apply(WALK, POSE);
			assertEquals(plain.active(), far.active());
			assertTrue(!far.towardsHazard(), "no hazard within reach, no hazard episode");
		}
	}

	private static final io.github.thedeadbolt.honeyfactory.environment.CameraPose POSE = new io.github.thedeadbolt.honeyfactory.environment.CameraPose(15.5, 5.0, 15.5, 0.0f, 0.0f);
}
