package io.github.thedeadbolt.honeyfactory.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CameraBasis;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.List;
import java.util.Random;
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

	/** A camera solved for a pixel sees the direction at that pixel, across the image and at steep elevations. */
	@Test
	void orientationPlacingPutsTheDirectionOnThePixel() {
		PerspectiveProjection projection = new PerspectiveProjection(1280, 960);
		Random random = new Random(26);
		int solved = 0;
		for (int i = 0; i < 2000; i++) {
			double elevation = Math.toRadians(random.nextDouble(-80.0, 80.0));
			double heading = random.nextDouble(0.0, 2 * Math.PI);
			double[] direction = {Math.cos(elevation) * Math.sin(heading), Math.sin(elevation), Math.cos(elevation) * Math.cos(heading)};
			double px = random.nextDouble(0.0, 1280.0);
			double py = random.nextDouble(0.0, 960.0);
			double[] orientation = projection.orientationPlacing(direction, px, py);
			if (orientation == null) {
				continue;
			}

			solved++;
			assertTrue(orientation[1] >= -90.0 && orientation[1] <= 90.0);
			CameraFrame camera = new CameraFrame(new double[] {0.0, 0.0, 0.0}, CameraBasis.of(orientation[0], orientation[1]));
			double[] pixel = projection.project(camera, direction);
			assertEquals(px, pixel[0], 1e-6);
			assertEquals(py, pixel[1], 1e-6);
		}

		// About 23% of these need a pitch past vertical, such as a target 70 degrees up at the
		// image's bottom edge; the closed form's other branch never adds a solution.
		assertTrue(solved > 1400, solved + " of 2000 solved");
		// Straight up cannot land at the bottom edge without pitching past vertical.
		assertNull(projection.orientationPlacing(new double[] {0.0, 1.0, 0.0}, 640.0, 960.0));
	}

	@Test
	void containsNeedsEveryCornerInsideTheMargin() {
		PerspectiveProjection projection = new PerspectiveProjection(64, 64);
		assertTrue(projection.contains(SOUTH, new double[] {0, 65, 8}, new double[] {1, 66, 9}, 2.0), "straight ahead");
		assertFalse(projection.contains(SOUTH, new double[] {8, 65, 8}, new double[] {9, 66, 9}, 2.0), "across the left edge");
		assertFalse(projection.contains(SOUTH, new double[] {0, 65, 0}, new double[] {1, 66, 1}, 2.0), "around the eye");
	}
}
