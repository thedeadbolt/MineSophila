package io.github.thedeadbolt.honeyfactory.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerspectiveProjectionTest {
	private static final double EPSILON = 1e-9;
	/** Eye at (0.5, 65.62, 0.5) looking south (+Z), horizontal. */
	private static final CameraFrame SOUTH = CameraFrame.of(new CameraPose(0.5, 64.0, 0.5, 0.0F, 0.0F), 1.62);

	@Test
	void squareOutputCoversNinetyDegreesBothWays() {
		PerspectiveProjection projection = new PerspectiveProjection(1280, 1280);
		assertEquals(640.0, projection.focalLengthPx());
		assertEquals(90.0, projection.verticalFovDegrees(), EPSILON);
		assertEquals(List.of(new CaptureProjection.View(12.5F, -3.0F, projection.verticalFovDegrees(), 1280, 1280)), projection.views(12.5F, -3.0F));
		JsonObject metadata = projection.metadata();
		assertEquals(90.0, metadata.get("horizontal_fov_degrees").getAsDouble());
		assertEquals(640.0, metadata.getAsJsonArray("principal_point_px").get(1).getAsDouble());
	}

	@Test
	void nonSquareOutputKeepsNinetyDegreesHorizontally() {
		PerspectiveProjection projection = new PerspectiveProjection(320, 180);
		assertEquals(Math.toDegrees(2.0 * Math.atan(180.0 / 320.0)), projection.verticalFovDegrees(), EPSILON);
		// 45 degrees to the right on the horizon lands on the right image edge.
		double[] edge = projection.project(SOUTH, new double[] {0.5 - 10.0, 65.62, 0.5 + 10.0});
		assertEquals(320.0, edge[0], 1e-6);
		assertEquals(90.0, edge[1], 1e-6);
	}

	@Test
	void projectionAndDirectionsAgree() {
		PerspectiveProjection projection = new PerspectiveProjection(200, 100);
		assertArrayEquals(SOUTH.basis().forward(), projection.direction(SOUTH, 100.0, 50.0), EPSILON);
		double[] point = {3.0, 66.5, 9.0};
		double[] pixel = projection.project(SOUTH, point);
		double[] direction = projection.direction(SOUTH, pixel[0], pixel[1]);
		double distance = SOUTH.distanceTo(point);
		for (int axis = 0; axis < 3; axis++) {
			assertEquals(point[axis], SOUTH.eye()[axis] + direction[axis] * distance, 1e-6);
		}

		assertNull(projection.project(SOUTH, new double[] {0.5, 65.62, -5.0}), "points behind the camera do not project");
	}

	@Test
	void boxIntersectionIsExactAtTheFrustumEdges() {
		PerspectiveProjection projection = new PerspectiveProjection(64, 64);
		assertTrue(projection.intersects(SOUTH, new double[] {0, 65, 8}, new double[] {1, 66, 9}), "straight ahead");
		assertFalse(projection.intersects(SOUTH, new double[] {0, 65, -9}, new double[] {1, 66, -8}), "behind");
		// The left frustum plane is x = 0.5 + (z - 0.5); this box stays just outside it.
		assertFalse(projection.intersects(SOUTH, new double[] {9.6, 65, 8}, new double[] {10.6, 66, 9}), "beside the view");
		assertTrue(projection.intersects(SOUTH, new double[] {8.0, 65, 8}, new double[] {9.0, 66, 9}), "straddling the left edge");
		// All eight corners lie outside the frustum, yet the box spans it.
		assertTrue(projection.intersects(SOUTH, new double[] {-50, 0, 5}, new double[] {50, 130, 6}), "wall across the view");
		assertTrue(projection.intersects(SOUTH, new double[] {0, 65, 0}, new double[] {1, 66, 1}), "box around the eye");
	}

	@Test
	void rejectsUnsupportedSizesAndModes() {
		assertThrows(IllegalArgumentException.class, () -> new PerspectiveProjection(15, 64));
		assertThrows(IllegalArgumentException.class, () -> new PerspectiveProjection(64, 16_385));
		assertThrows(IllegalArgumentException.class, () -> CaptureProjection.of(io.github.thedeadbolt.honeyfactory.contract.CaptureMode.PANORAMA_310, 2480, 1280, null));
	}
}
