package io.github.thedeadbolt.honeyfactory.generation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A fall is one drop through the air deeper than the limit, not walking downhill. */
class FallCheckTest {
	@Test
	void walkingDownStepsIsNotAFall() {
		FallCheck check = new FallCheck(148.0);
		for (int step = 1; step <= 6; step++) {
			assertFalse(check.fell(148.0 - step, true), "one block down, standing, at step " + step);
		}
	}

	@Test
	void oneDropDeeperThanTheLimitIsAFall() {
		FallCheck check = new FallCheck(100.0);
		assertFalse(check.fell(99.0, false));
		assertFalse(check.fell(97.5, false));
		assertTrue(check.fell(96.5, false), "3.5 blocks below the last standing height");
	}

	@Test
	void theDropIsMeasuredFromTheLastStandingHeight() {
		FallCheck check = new FallCheck(100.0);
		assertFalse(check.fell(98.0, true));
		assertFalse(check.fell(95.5, false), "2.5 below where it last stood");
		assertTrue(check.fell(94.5, false));
	}

	@Test
	void landingAfterADropWithinTheLimitResetsIt() {
		FallCheck check = new FallCheck(100.0);
		assertFalse(check.fell(97.0, true));
		assertFalse(check.fell(94.0, true));
		assertFalse(check.fell(91.0, true));
	}
}
