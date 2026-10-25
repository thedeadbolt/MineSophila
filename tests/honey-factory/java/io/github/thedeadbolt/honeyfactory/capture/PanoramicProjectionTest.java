package io.github.thedeadbolt.honeyfactory.capture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CameraBasis;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PanoramicProjectionTest {
	private static final double EPSILON = 1e-9;
	private static final PanoramicProjection DEFAULT = new PanoramicProjection(2480, 1280, 1024);

	private static CameraFrame camera(float yaw, float pitch) {
		return CameraFrame.of(new CameraPose(0.5, 64.0, 0.5, yaw, pitch), 1.62);
	}

	/** The face axes are the basis Minecraft's camera has at each face's yaw and pitch. */
	@ParameterizedTest
	@ValueSource(floats = {0.0F, 37.5F, -120.0F, 179.0F})
	void faceAxesMatchMinecraftsCameraAtEachFace(float yaw) {
		CameraFrame camera = camera(yaw, 0.0F);
		double[][] level = PanoramicProjection.levelAxes(camera);
		List<CaptureProjection.View> views = DEFAULT.views(yaw, 25.0F);
		for (CubeFace face : CubeFace.values()) {
			CaptureProjection.View view = views.get(face.ordinal());
			assertEquals(1024, view.width());
			assertEquals(90.0, view.verticalFovDegrees());
			CameraBasis basis = CameraBasis.of(view.yawDegrees(), view.pitchDegrees());
			assertArrayEquals(basis.forward(), world(level, face.forward()), 1e-6, face + " forward");
			assertArrayEquals(basis.right(), world(level, face.right()), 1e-6, face + " right");
			assertArrayEquals(basis.up(), world(level, face.up()), 1e-6, face + " up");
		}
	}

	@Test
	void pixelsMapToLongitudeAndElevation() {
		CameraFrame camera = camera(30.0F, -20.0F);
		double[][] level = PanoramicProjection.levelAxes(camera);
		double horizonRow = 1280 * 90.0 / 160.0;
		assertArrayEquals(level[2], DEFAULT.direction(camera, 1240.0, horizonRow), EPSILON, "the centre looks forward, ignoring pitch");
		assertArrayEquals(level[0], DEFAULT.direction(camera, 1240.0 + 90 * 8, horizonRow), EPSILON, "90 degrees right");
		assertArrayEquals(new double[] {0, 1, 0}, DEFAULT.direction(camera, 600.0, 0.0), EPSILON, "the top edge is the zenith");
		double[] lowest = DEFAULT.direction(camera, 1240.0, 1280.0);
		assertEquals(-Math.sin(Math.toRadians(70.0)), lowest[1], EPSILON, "the bottom edge is 70 degrees down");
		double[] leftEdge = DEFAULT.direction(camera, 0.0, horizonRow);
		assertEquals(Math.cos(Math.toRadians(155.0)), dot(leftEdge, level[2]), EPSILON);
		assertEquals(-Math.sin(Math.toRadians(155.0)), dot(leftEdge, level[0]), EPSILON);
	}

	/** Every table entry samples the face point whose direction is the pixel's direction. */
	@Test
	void theSamplingTableFollowsEachPixelsDirection() {
		PanoramicProjection projection = new PanoramicProjection(620, 320, 256);
		PanoramaSampling table = PanoramaSampling.of(projection);
		CameraFrame camera = camera(0.0F, 0.0F);
		double[][] level = PanoramicProjection.levelAxes(camera);
		int[] used = new int[CubeFace.values().length];
		for (int y = 0; y < projection.height(); y++) {
			for (int x = 0; x < projection.width(); x++) {
				int[] sample = table.sample(x, y);
				CubeFace face = CubeFace.values()[sample[0]];
				used[sample[0]]++;
				double a = sample[1] / 256.0 / 128.0 - 1.0;
				double b = 1.0 - sample[2] / 256.0 / 128.0;
				double[] local = new double[3];
				for (int i = 0; i < 3; i++) {
					local[i] = face.forward()[i] + a * face.right()[i] + b * face.up()[i];
				}

				double[] expected = projection.direction(camera, x + 0.5, y + 0.5);
				double[] actual = normalize(world(level, local));
				double angle = Math.toDegrees(Math.acos(Math.min(1.0, dot(actual, expected))));
				assertTrue(angle < 0.01, "pixel " + x + "," + y + " samples " + angle + " degrees away");
			}
		}

		for (CubeFace face : CubeFace.values()) {
			assertTrue(used[face.ordinal()] > 0, face + " is sampled");
		}
	}

	@Test
	void reprojectionKeepsMasksCategoricalAndFacesInPlace() {
		PanoramicProjection projection = new PanoramicProjection(310, 160, 64);
		List<CapturedImages> faces = new ArrayList<>();
		for (CubeFace face : CubeFace.values()) {
			int size = 64;
			byte[] rgb = new byte[size * size * 3];
			byte[] visible = new byte[size * size];
			byte[] unoccluded = new byte[size * size];
			for (int p = 0; p < size * size; p++) {
				rgb[p * 3] = (byte) (40 * face.ordinal());
				rgb[p * 3 + 1] = (byte) 200;
				unoccluded[p] = (byte) (p % size >= size / 2 ? 255 : 0);
				visible[p] = (byte) (p % size >= size / 2 && p / size >= size / 2 ? 255 : 0);
			}

			faces.add(new CapturedImages(size, size, rgb, visible, unoccluded));
		}

		CapturedImages panorama = projection.assemble(faces);
		assertNull(panorama.maskDefect(), "masks stay 0 or 255 and visible stays inside unoccluded");
		PanoramaSampling table = PanoramaSampling.of(projection);
		for (int y = 0; y < 160; y++) {
			for (int x = 0; x < 310; x++) {
				int[] sample = table.sample(x, y);
				int p = y * 310 + x;
				int red = panorama.rgb()[p * 3] & 0xFF;
				assertEquals(40 * sample[0], red, "a solid face reprojects unchanged at " + x + "," + y);
				boolean rightHalf = (sample[1] >> 8) >= 32;
				assertEquals(rightHalf, panorama.unoccludedMask()[p] != 0, "nearest sampling at " + x + "," + y);
			}
		}

		assertArrayEquals(panorama.rgb(), projection.assemble(faces).rgb(), "reprojection is deterministic");
		assertThrows(IllegalArgumentException.class, () -> projection.assemble(faces.subList(0, 5)));
	}

	@Test
	void onlyTheBlindSectorAndTheGroundBelowSeventyDegreesAreOutOfView() {
		CameraFrame south = camera(0.0F, 0.0F);
		assertTrue(seen(south, 0, 0, 8), "ahead");
		assertTrue(seen(south, -8, 0, 0), "to the side");
		assertTrue(seen(south, 0, 8, -0.1), "overhead, slightly behind");
		assertFalse(seen(south, 0, 0, -8), "straight behind");
		// 150 degrees from forward is inside the panorama; 170 is in the blind sector.
		assertTrue(seen(south, -8 * Math.sin(Math.toRadians(150)), 0, 8 * Math.cos(Math.toRadians(150))));
		assertFalse(seen(south, -8 * Math.sin(Math.toRadians(170)), 0, 8 * Math.cos(Math.toRadians(170))));
		assertFalse(seen(south, 0, -8, 0.5), "almost straight down");
		assertTrue(seen(south, 0, -8, 5), "down at about 58 degrees");
		assertTrue(DEFAULT.intersects(south, new double[] {0, 65, 0}, new double[] {1, 66, 1}), "box around the eye");
	}

	@Test
	void describesItselfAndRejectsUnsupportedSizes() {
		JsonObject metadata = DEFAULT.metadata();
		assertEquals(PanoramicProjection.PROJECTION, metadata.get("projection").getAsString());
		assertEquals(50.0, metadata.get("blind_sector_degrees").getAsDouble());
		assertEquals(8.0, metadata.getAsJsonArray("pixels_per_degree").get(0).getAsDouble());
		assertEquals(8.0, metadata.getAsJsonArray("pixels_per_degree").get(1).getAsDouble());
		assertEquals(1024, metadata.get("cube_face_size").getAsInt());
		assertEquals(CaptureMode.PANORAMA_310, CaptureProjection.of(CaptureMode.PANORAMA_310, 2480, 1280, 1024).mode());
		assertEquals(DEFAULT, CaptureProjection.of(CaptureMode.PANORAMA_310, 2480, 1280, 1024));
		assertThrows(IllegalArgumentException.class, () -> new PanoramicProjection(2480, 1280, 8));
		assertThrows(IllegalArgumentException.class, () -> new PanoramicProjection(2480, 1280, 8192));
		assertThrows(IllegalArgumentException.class, () -> new PanoramicProjection(16_384, 16_384, 1024));
	}

	/** Whether a unit cube centred {@code (dx, dy, dz)} from the eye is in view. */
	private static boolean seen(CameraFrame camera, double dx, double dy, double dz) {
		double[] eye = camera.eye();
		double[] min = {eye[0] + dx - 0.5, eye[1] + dy - 0.5, eye[2] + dz - 0.5};
		return DEFAULT.intersects(camera, min, new double[] {min[0] + 1, min[1] + 1, min[2] + 1});
	}

	private static double[] world(double[][] level, double[] local) {
		double[] result = new double[3];
		for (int i = 0; i < 3; i++) {
			result[i] = local[0] * level[0][i] + local[1] * level[1][i] + local[2] * level[2][i];
		}

		return result;
	}

	private static double[] normalize(double[] v) {
		double length = Math.sqrt(dot(v, v));
		return new double[] {v[0] / length, v[1] / length, v[2] / length};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	@Test
	void projectInvertsDirection() {
		CameraFrame camera = camera(-40.0F, 20.0F);
		for (double[] pixel : new double[][] {{1240.0, 640.0}, {10.5, 3.5}, {2470.0, 1270.0}, {600.25, 900.75}}) {
			double[] direction = DEFAULT.direction(camera, pixel[0], pixel[1]);
			double[] eye = camera.eye();
			assertArrayEquals(pixel, DEFAULT.project(camera, new double[] {eye[0] + 7 * direction[0], eye[1] + 7 * direction[1], eye[2] + 7 * direction[2]}), 1e-6);
		}

		double[] eye = camera.eye();
		double[] behind = camera.basis().forward();
		assertNull(DEFAULT.project(camera, new double[] {eye[0] - 5 * behind[0], eye[1], eye[2] - 5 * behind[2]}), "the rear blind sector");
		assertNull(DEFAULT.project(camera, new double[] {eye[0], eye[1] - 5, eye[2] + 0.5}), "below -70 degrees");
	}

	/** Only the column is placed: the yaw puts the direction at its longitude, at whatever row its elevation gives. */
	@Test
	void orientationPlacingSetsTheColumn() {
		double[] direction = {-0.6, 0.28, 0.75};
		double length = Math.sqrt(dot(direction, direction));
		direction = new double[] {direction[0] / length, direction[1] / length, direction[2] / length};
		for (double px : new double[] {0.5, 400.0, 1240.0, 2479.5}) {
			double[] orientation = DEFAULT.orientationPlacing(direction, px, 0.0);
			CameraFrame camera = new CameraFrame(new double[] {0.0, 0.0, 0.0}, CameraBasis.of(orientation[0], orientation[1]));
			double[] pixel = DEFAULT.project(camera, direction);
			assertEquals(px, pixel[0], 1e-6);
			assertEquals((90.0 - Math.toDegrees(Math.asin(direction[1]))) / 160.0 * 1280, pixel[1], 1e-6);
		}
	}

	@Test
	void containsRejectsBoxesAcrossTheRearOrOverhead() {
		CameraFrame camera = camera(0.0F, 0.0F);
		assertTrue(DEFAULT.contains(camera, new double[] {0, 65, 8}, new double[] {1, 66, 9}, 2.0), "ahead");
		assertFalse(DEFAULT.contains(camera, new double[] {0, 65, -9}, new double[] {1, 66, -8}, 2.0), "behind");
		assertFalse(DEFAULT.contains(camera, new double[] {0, 60, 0}, new double[] {1, 61, 1}, 2.0), "underfoot");
		assertFalse(DEFAULT.contains(camera, new double[] {0, 70, 0}, new double[] {1, 71, 1}, 2.0), "overhead, around every longitude");
	}
}
