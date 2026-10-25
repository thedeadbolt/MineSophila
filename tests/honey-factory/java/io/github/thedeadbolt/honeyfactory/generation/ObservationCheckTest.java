package io.github.thedeadbolt.honeyfactory.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.TargetStatus;
import io.github.thedeadbolt.honeyfactory.contract.VisibilityRule;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.junit.jupiter.api.Test;

class ObservationCheckTest {
	private static final int SIZE = 32;
	private static final PerspectiveProjection PROJECTION = new PerspectiveProjection(SIZE, SIZE);
	/** Looking south (+Z) at the block 6 cells ahead. */
	private static final CameraFrame CAMERA = CameraFrame.of(new CameraPose(0.5, 64.0, 0.5, 0.0F, 0.0F), 1.62);
	private static final BlockBox TARGET = BlockBox.cell(new int[] {0, 65, 6});
	private static final ResolvedConfig.Visibility AT_LEAST_PARTLY = new ResolvedConfig.Visibility(VisibilityRule.AT_LEAST_PARTLY_VISIBLE, 0.1, null);

	/** Images whose visible and unoccluded masks are the same square of {@code side} pixels at (x, y). */
	private static CapturedImages square(int x, int y, int side) {
		byte[] mask = new byte[SIZE * SIZE];
		for (int row = y; row < y + side; row++) {
			for (int column = x; column < x + side; column++) {
				mask[row * SIZE + column] = (byte) 255;
			}
		}

		return new CapturedImages(SIZE, SIZE, new byte[SIZE * SIZE * 3], mask, mask.clone());
	}

	@Test
	void acceptsATargetWhollyInsideTheFrame() {
		ObservationCheck.Outcome outcome = ObservationCheck.check(AT_LEAST_PARTLY, PROJECTION, CAMERA, TARGET, square(12, 12, 6));
		assertEquals(TargetStatus.FULLY_VISIBLE, assertInstanceOf(ObservationCheck.Outcome.Accepted.class, outcome).target().targetStatus());
	}

	@Test
	void rejectsATargetReachingAnyEdgeOfTheFrame() {
		int[][] corners = {{0, 12}, {12, 0}, {SIZE - 6, 12}, {12, SIZE - 6}};
		for (int[] corner : corners) {
			ObservationCheck.Outcome outcome = ObservationCheck.check(AT_LEAST_PARTLY, PROJECTION, CAMERA, TARGET, square(corner[0], corner[1], 6));
			assertEquals(ObservationCheck.FRAME_CLIPPED, assertInstanceOf(ObservationCheck.Outcome.Rejected.class, outcome).reason());
		}
	}

	@Test
	void sceneWithoutATargetIsNeverClipped() {
		ObservationCheck.Outcome outcome = ObservationCheck.check(AT_LEAST_PARTLY, PROJECTION, CAMERA, null, CapturedImages.withoutTarget(SIZE, SIZE, new byte[SIZE * SIZE * 3]));
		assertEquals(TargetStatus.ABSENT, assertInstanceOf(ObservationCheck.Outcome.Accepted.class, outcome).target().targetStatus());
	}
}
