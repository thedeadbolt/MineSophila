package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.junit.jupiter.api.Test;

/** Frame labels come from the frame's own pose and masks; honey at the frame edge is labelled, not rejected. */
class ClipLabelsTest {
	private static final double EYE = 1.62;
	private static final CaptureProjection PERSPECTIVE = CaptureProjection.of(CaptureMode.PERSPECTIVE_90, 64, 64, null);
	/** At the origin cell's centre, facing +z (Minecraft yaw 0). */
	private static final CameraPose POSE = new CameraPose(0.5, 0.0, 0.5, 0.0f, 0.0f);

	private static FrameRecord label(CameraPose pose, int[] cell, CapturedImages images) {
		return ClipLabels.frame("s000000-c00", 1, 1, 1, 3, new ClipEnvironment.Frame(pose, 1.0 / 3.0, images), cell, PERSPECTIVE, EYE, null);
	}

	private static CapturedImages empty() {
		return CapturedImages.withoutTarget(64, 64, new byte[64 * 64 * 3]);
	}

	@Test
	void honeyToTheRightHasAzimuthNinety() {
		FrameRecord.Bearing bearing = ClipLabels.bearing(POSE, EYE, new int[] {-5, 1, 0});
		assertEquals(90.0, bearing.azimuthDegrees(), 1e-9);
		assertEquals(Math.toDegrees(Math.atan2(1.5 - EYE, 5.0)), bearing.elevationDegrees(), 1e-9);
		assertEquals(Math.hypot(5.0, 1.5 - EYE), bearing.distanceBlocks(), 1e-9);
	}

	@Test
	void honeyAheadHasAzimuthZeroAndBehindOneEighty() {
		assertEquals(0.0, ClipLabels.bearing(POSE, EYE, new int[] {0, 1, 5}).azimuthDegrees(), 1e-9);
		assertEquals(180.0, Math.abs(ClipLabels.bearing(POSE, EYE, new int[] {0, 1, -5}).azimuthDegrees()), 1e-9);
	}

	@Test
	void honeyOutsideTheProjectionIsOutOfViewWithABearing() {
		FrameRecord frame = label(POSE, new int[] {0, 1, -5}, empty());
		assertEquals(TargetStatus.OUT_OF_VIEW, frame.target().targetStatus());
		assertNotNull(frame.bearing());
		assertFalse(frame.touchesEdge());
	}

	@Test
	void absentHoneyHasNoBearing() {
		FrameRecord frame = label(POSE, null, empty());
		assertEquals(TargetStatus.ABSENT, frame.target().targetStatus());
		assertNull(frame.bearing());
	}

	@Test
	void maskAtTheFrameEdgeIsLabelledNotRejected() {
		byte[] mask = new byte[64 * 64];
		mask[10 * 64] = (byte) 255;
		mask[10 * 64 + 1] = (byte) 255;
		CapturedImages images = new CapturedImages(64, 64, new byte[64 * 64 * 3], mask, mask.clone());
		FrameRecord frame = label(POSE, new int[] {0, 1, 5}, images);
		assertTrue(frame.touchesEdge());
		assertEquals(TargetStatus.FULLY_VISIBLE, frame.target().targetStatus());
		assertEquals(2, frame.target().visiblePixels());
		assertEquals(1.0 / 3.0, frame.partialTick(), 1e-12);
	}
}
