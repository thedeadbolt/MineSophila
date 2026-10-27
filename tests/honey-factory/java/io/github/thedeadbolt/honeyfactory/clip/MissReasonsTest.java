package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.junit.jupiter.api.Test;

/** Why a clip that sought the honey did not reach it, judged by the scene's true layout. */
class MissReasonsTest {
	private static final MissReasons REASONS = new MissReasons(EdgeGuardTest.PLATFORM, EdgeGuardTest.CORE);
	private static final CameraPose START = new CameraPose(15.5, 5.0, 10.5, 0.0f, 0.0f);

	@Test
	void progressThatRanOutOfTimeIsATimeout() {
		assertEquals(ClipRecord.MissReason.TIMEOUT, REASONS.of(START, new CameraPose(15.5, 5.0, 16.5, 0.0f, 0.0f), new int[] {15, 5, 21}));
	}

	@Test
	void noProgressIsATeacherFailure() {
		assertEquals(ClipRecord.MissReason.TEACHER_FAILURE, REASONS.of(START, new CameraPose(15.5, 5.0, 11.0, 0.0f, 0.0f), new int[] {15, 5, 21}));
	}

	@Test
	void honeyWithNoWayToItIsUnreachable() {
		// On the low floor beside the platform: a 4-block drop away.
		assertEquals(ClipRecord.MissReason.UNREACHABLE, REASONS.of(START, START, new int[] {3, 1, 15}));
	}

	@Test
	void aScriptThatNeverHandedOverRanOutOfTime() {
		// An occlusion walk to cover that filled the clip: the teacher never drove, so it cannot have failed.
		assertEquals(ClipRecord.MissReason.TIMEOUT, REASONS.of(START, null, new CameraPose(15.5, 5.0, 8.5, 0.0f, 0.0f), new int[] {15, 5, 21}));
	}

	@Test
	void progressCountsFromWhereTheTeacherTookOver() {
		CameraPose cover = new CameraPose(15.5, 5.0, 8.5, 0.0f, 0.0f);
		assertEquals(ClipRecord.MissReason.TIMEOUT, REASONS.of(START, cover, new CameraPose(15.5, 5.0, 12.5, 0.0f, 0.0f), new int[] {15, 5, 21}),
			"4 cells back from cover, though only 2 past the start");
		assertEquals(ClipRecord.MissReason.TEACHER_FAILURE, REASONS.of(START, cover, cover, new int[] {15, 5, 21}));
	}
}
