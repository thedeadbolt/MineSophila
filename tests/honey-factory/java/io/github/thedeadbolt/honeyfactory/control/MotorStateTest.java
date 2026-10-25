package io.github.thedeadbolt.honeyfactory.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MotorStateTest {
	private static final MotorLimits LIMITS = MotorLimits.DEFAULT;

	private static MotorCommand turning(double rate) {
		return new MotorCommand(0.0, 0.0, rate, 0.0, false, 0);
	}

	@Test
	void turnRateRampsUpByTheAccelerationLimit() {
		MotorState state = MotorState.ZERO;
		float yaw = 0.0F;
		double[] expected = {1.8, 3.6, 5.4, 7.2, 9.0, 9.0};
		double turned = 0.0;
		for (double rate : expected) {
			MotorState.Achieved achieved = state.apply(turning(9.0), LIMITS, yaw, 0.0F);
			assertEquals(rate, achieved.state().yawRate(), 1e-9);
			turned += rate;
			assertEquals(turned, achieved.yaw(), 1e-4);
			state = achieved.state();
			yaw = achieved.yaw();
		}
	}

	@Test
	void reversingAFullTurnTakesTenTicks() {
		MotorState state = new MotorState(9.0, 0.0);
		int ticks = 0;
		while (state.yawRate() > -9.0 + 1e-9) {
			state = state.apply(turning(-9.0), LIMITS, 0.0F, 0.0F).state();
			ticks++;
		}

		assertEquals(10, ticks);
	}

	@Test
	void yawWrapsAndPitchClampsStoppingItsRate() {
		MotorState.Achieved wrapped = new MotorState(2.0, 0.0).apply(turning(2.0), LIMITS, 179.0F, 0.0F);
		assertEquals(-179.0F, wrapped.yaw(), 1e-4);
		MotorState.Achieved clamped = new MotorState(0.0, 6.0).apply(new MotorCommand(0.0, 0.0, 0.0, 6.0, false, 0), LIMITS, 0.0F, 89.5F);
		assertEquals(90.0F, clamped.pitch());
		assertEquals(0.0, clamped.state().pitchRate());
	}

	@Test
	void checkRejectsValuesOutsideTheLimitsNamingTheField() {
		record Case(MotorCommand command, String field) {
		}

		for (Case c : new Case[] {
			new Case(new MotorCommand(1.5, 0.0, 0.0, 0.0, false, 0), "forward"),
			new Case(new MotorCommand(0.0, Double.NaN, 0.0, 0.0, false, 0), "strafe"),
			new Case(new MotorCommand(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0, false, 0), "turn_rate"),
			new Case(new MotorCommand(0.0, 0.0, 9.01, 0.0, false, 0), "turn_rate"),
			new Case(new MotorCommand(0.0, 0.0, 0.0, -6.5, false, 0), "pitch_rate"),
			new Case(new MotorCommand(0.0, 0.0, 0.0, 0.0, false, 1), "delay_ticks"),
		}) {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> LIMITS.check(c.command()), c.toString());
			assertTrue(error.getMessage().contains(c.field()), error.getMessage());
		}

		LIMITS.check(new MotorCommand(-1.0, 1.0, -9.0, 6.0, true, 0));
	}

	@Test
	void sameInputsGiveBitIdenticalOutputs() {
		MotorCommand command = new MotorCommand(0.3, -0.2, 7.3, -4.1, false, 0);
		MotorState state = new MotorState(1.234, -0.5);
		assertEquals(state.apply(command, LIMITS, 12.5F, -3.25F), state.apply(command, LIMITS, 12.5F, -3.25F));
	}
}
