package io.github.thedeadbolt.honeyfactory.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ActionTest {
	private static EnvironmentException.Reason rejection(ActionLimits limits, Action action) {
		return assertThrows(EnvironmentException.class, () -> limits.check(action)).reason();
	}

	@Test
	void turnsIncreaseYawWithinAHalfTurnEitherWay() {
		assertEquals(-90.0F, new Action.Turn(90.0F).resultingYaw(180.0F));
		assertEquals(90.0F, new Action.Turn(-90.0F).resultingYaw(180.0F));
		assertEquals(-180.0F, new Action.Turn(180.0F).resultingYaw(0.0F));
		assertEquals(10.0F, new Action.Turn(20.0F).resultingYaw(710.0F));
		assertEquals(-170.0F, new Action.Turn(20.0F).resultingYaw(170.0F));
	}

	@Test
	void looksArePositiveDownAndClampedToMinecraftsLimits() {
		assertEquals(30.0F, new Action.Look(30.0F).resultingPitch(0.0F));
		assertEquals(90.0F, new Action.Look(80.0F).resultingPitch(30.0F));
		assertEquals(-90.0F, new Action.Look(-180.0F).resultingPitch(45.0F));
	}

	@Test
	void parsesSpecificationNamesAndValues() {
		assertEquals(new Action.Turn(-45.0F), Action.parse("turn", -45.0));
		assertEquals(new Action.Look(12.5F), Action.parse("LOOK", 12.5));
		assertEquals(new Action.MoveForward(20), Action.parse("move_forward", 20.0));
		assertEquals(new Action.Wait(5), Action.parse("wait", 5.0));
		assertEquals(new Action.Stop(), Action.parse("stop", 0.0));
		assertEquals(new Action.Mine(10), Action.parse("mine", 10.0));
		assertEquals("MOVE_FORWARD", new Action.MoveForward(1).type());
		assertEquals("degrees", new Action.Turn(1.0F).unit());
		assertEquals("ticks", new Action.Stop().unit());
		assertEquals(20, new Action.MoveForward(20).ticks());
		assertEquals(0, new Action.Look(10.0F).ticks());

		for (String bad : new String[] {"jump", "move_backward"}) {
			assertEquals(EnvironmentException.Reason.INVALID_REQUEST, assertThrows(EnvironmentException.class, () -> Action.parse(bad, 1.0)).reason());
		}

		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, assertThrows(EnvironmentException.class, () -> Action.parse("wait", 2.5)).reason());
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, assertThrows(EnvironmentException.class, () -> Action.parse("move_forward", -1.0)).reason());
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, assertThrows(EnvironmentException.class, () -> Action.parse("turn", Double.NaN)).reason());
	}

	@Test
	void limitsRejectOutOfRangeRequestsAndMining() {
		ActionLimits limits = ActionLimits.DEFAULT;
		limits.check(new Action.Turn(-180.0F));
		limits.check(new Action.Look(180.0F));
		limits.check(new Action.MoveForward(200));
		limits.check(new Action.Wait(1));
		limits.check(new Action.Stop());
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(limits, new Action.Turn(180.5F)));
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(limits, new Action.Look(Float.POSITIVE_INFINITY)));
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(limits, new Action.MoveForward(0)));
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(limits, new Action.Wait(201)));
		assertEquals(EnvironmentException.Reason.UNSUPPORTED, rejection(limits, new Action.Mine(1)));

		ActionLimits tight = new ActionLimits(15.0F, 10.0F, 5, 3);
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(tight, new Action.Turn(16.0F)));
		assertEquals(EnvironmentException.Reason.INVALID_REQUEST, rejection(tight, new Action.Wait(4)));
		assertThrows(IllegalArgumentException.class, () -> new ActionLimits(0.0F, 10.0F, 5, 3));
		assertThrows(IllegalArgumentException.class, () -> new ActionLimits(15.0F, 190.0F, 5, 3));
	}

	@Test
	void settingsDefaultToAutoJumpWithDefaultLimits() {
		assertEquals(true, EnvironmentSettings.DEFAULT.autoJump());
		assertEquals(ActionLimits.DEFAULT, EnvironmentSettings.DEFAULT.limits());
		assertEquals(false, EnvironmentSettings.DEFAULT.withAutoJump(false).autoJump());
	}
}
