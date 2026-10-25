package io.github.thedeadbolt.honeyfactory.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.environment.Action;
import java.util.List;
import org.junit.jupiter.api.Test;

class ActionMacrosTest {
	private static final MotorLimits LIMITS = MotorLimits.DEFAULT;

	/** Rates start and end at rest, stay within the maximum and change by at most the acceleration each tick. */
	private static void assertSmooth(List<Double> rates, double max, double accel) {
		double previous = 0.0;
		for (double rate : rates) {
			assertTrue(Math.abs(rate) <= max + 1e-9, "rate " + rate + " in " + rates);
			assertTrue(Math.abs(rate - previous) <= accel + 1e-9, "step from " + previous + " to " + rate + " in " + rates);
			previous = rate;
		}

		assertTrue(Math.abs(previous) <= accel + 1e-9, "the last rate " + previous + " cannot stop in one tick");
	}

	@Test
	void turnsRampUpCruiseAndLandExactly() {
		for (float degrees : new float[] {90.0F, -90.0F, 180.0F, 1.0F, 10.0F, -0.25F}) {
			List<Double> rates = ActionMacros.expand(new Action.Turn(degrees), LIMITS).stream().map(MotorCommand::turnRate).toList();
			assertEquals(degrees, rates.stream().mapToDouble(Double::doubleValue).sum(), 1e-6, "turn " + degrees);
			assertSmooth(rates, 9.0, 1.8);
			assertTrue(rates.stream().allMatch(rate -> Math.signum(rate) == Math.signum(degrees)), "turn " + degrees + " reverses: " + rates);
		}

		assertEquals(14, ActionMacros.expand(new Action.Turn(90.0F), LIMITS).size());
		assertEquals(List.of(1.0), ActionMacros.expand(new Action.Turn(1.0F), LIMITS).stream().map(MotorCommand::turnRate).toList());
	}

	@Test
	void looksUseThePitchLimits() {
		List<Double> rates = ActionMacros.expand(new Action.Look(60.0F), LIMITS).stream().map(MotorCommand::pitchRate).toList();
		assertEquals(60.0, rates.stream().mapToDouble(Double::doubleValue).sum(), 1e-6);
		assertSmooth(rates, 6.0, 1.2);
	}

	@Test
	void movesWaitsAndStops() {
		List<MotorCommand> move = ActionMacros.expand(new Action.MoveForward(5), LIMITS);
		assertEquals(5, move.size());
		assertTrue(move.stream().allMatch(command -> command.equals(new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0))));
		assertEquals(List.of(MotorCommand.IDLE, MotorCommand.IDLE, MotorCommand.IDLE), ActionMacros.expand(new Action.Wait(3), LIMITS));
		assertEquals(List.of(), ActionMacros.expand(new Action.Stop(), LIMITS));
		assertEquals(List.of(), ActionMacros.expand(new Action.Turn(0.0F), LIMITS));
		assertThrows(IllegalArgumentException.class, () -> ActionMacros.expand(new Action.Mine(5), LIMITS));
	}
}
