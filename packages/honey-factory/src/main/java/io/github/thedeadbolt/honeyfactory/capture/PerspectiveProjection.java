package io.github.thedeadbolt.honeyfactory.capture;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * {@code PERSPECTIVE_90}: one rectilinear render with a 90-degree horizontal field of view
 * centred on the camera's forward direction, square pixels and the principal point at the
 * image centre.
 *
 * <p>With focal length {@code f = width / 2} pixels, a camera-space point
 * {@code (right, up, forward)} lands at pixel coordinates
 * {@code (cx + f * right / forward, cy - f * up / forward)}; pixel {@code (x, y)} is the
 * unit square whose centre is {@code (x + 0.5, y + 0.5)}.
 */
public record PerspectiveProjection(int width, int height) implements CaptureProjection {
	public static final double HORIZONTAL_FOV_DEGREES = 90.0;
	public static final int MIN_SIZE = 16;
	public static final int MAX_SIZE = 16_384;

	public PerspectiveProjection {
		if (width < MIN_SIZE || height < MIN_SIZE || width > MAX_SIZE || height > MAX_SIZE) {
			throw new IllegalArgumentException("Perspective output must be " + MIN_SIZE + "-" + MAX_SIZE + " pixels on each side, got " + width + "x" + height);
		}
	}

	@Override
	public CaptureMode mode() {
		return CaptureMode.PERSPECTIVE_90;
	}

	public double focalLengthPx() {
		return this.width / 2.0;
	}

	/** {@code 2 atan(tan(45 degrees) * height / width)}. */
	public double verticalFovDegrees() {
		return Math.toDegrees(2.0 * Math.atan(this.height / (double) this.width));
	}

	@Override
	public List<View> views(float yawDegrees, float pitchDegrees) {
		return List.of(new View(yawDegrees, pitchDegrees, verticalFovDegrees(), this.width, this.height));
	}

	/** The single render is the output. */
	@Override
	public CapturedImages assemble(List<CapturedImages> views) {
		if (views.size() != 1 || views.getFirst().width() != this.width || views.getFirst().height() != this.height) {
			throw new IllegalArgumentException("A perspective capture is one " + this.width + "x" + this.height + " render");
		}

		return views.getFirst();
	}

	/** Continuous pixel coordinates of a world point, or null when it is not in front of the near plane. */
	public double @Nullable [] project(CameraFrame camera, double[] point) {
		double[] local = camera.toCamera(point);
		if (local[2] < NEAR_PLANE_BLOCKS) {
			return null;
		}

		double focal = focalLengthPx();
		return new double[] {this.width / 2.0 + focal * local[0] / local[2], this.height / 2.0 - focal * local[1] / local[2]};
	}

	@Override
	public double[] direction(CameraFrame camera, double px, double py) {
		double focal = focalLengthPx();
		double right = (px - this.width / 2.0) / focal;
		double up = (this.height / 2.0 - py) / focal;
		double[] r = camera.basis().right();
		double[] u = camera.basis().up();
		double[] f = camera.basis().forward();
		double[] direction = {f[0] + right * r[0] + up * u[0], f[1] + right * r[1] + up * u[1], f[2] + right * r[2] + up * u[2]};
		double length = Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2]);
		return new double[] {direction[0] / length, direction[1] / length, direction[2] / length};
	}

	/**
	 * Exact convex test: clips each face of the box against the near plane and the four side
	 * planes of the view frustum, and reports whether any face keeps a non-empty part.
	 */
	@Override
	public boolean intersects(CameraFrame camera, double[] boxMin, double[] boxMax) {
		double[][] corners = new double[8][];
		for (int i = 0; i < 8; i++) {
			corners[i] = camera.toCamera(new double[] {
				(i & 1) == 0 ? boxMin[0] : boxMax[0],
				(i & 2) == 0 ? boxMin[1] : boxMax[1],
				(i & 4) == 0 ? boxMin[2] : boxMax[2],
			});
		}

		// Faces as corner indices around each quad: -x, +x, -y, +y, -z, +z.
		int[][] faces = {{0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};
		double tanHalfHorizontal = 1.0;
		double tanHalfVertical = this.height / (double) this.width;
		for (int[] face : faces) {
			List<double[]> polygon = new ArrayList<>(4);
			for (int index : face) {
				polygon.add(corners[index]);
			}

			polygon = clip(polygon, p -> p[2] - NEAR_PLANE_BLOCKS);
			polygon = clip(polygon, p -> p[2] * tanHalfHorizontal - p[0]);
			polygon = clip(polygon, p -> p[2] * tanHalfHorizontal + p[0]);
			polygon = clip(polygon, p -> p[2] * tanHalfVertical - p[1]);
			polygon = clip(polygon, p -> p[2] * tanHalfVertical + p[1]);
			if (!polygon.isEmpty()) {
				return true;
			}
		}

		return false;
	}

	/** Sutherland-Hodgman clipping of a convex polygon to the half-space {@code inside(p) >= 0}. */
	private static List<double[]> clip(List<double[]> polygon, ToDoubleFunction<double[]> inside) {
		List<double[]> result = new ArrayList<>(polygon.size() + 1);
		for (int i = 0; i < polygon.size(); i++) {
			double[] current = polygon.get(i);
			double[] next = polygon.get((i + 1) % polygon.size());
			double a = inside.applyAsDouble(current);
			double b = inside.applyAsDouble(next);
			if (a >= 0) {
				result.add(current);
			}

			if (a >= 0 != b >= 0) {
				double t = a / (a - b);
				result.add(new double[] {
					current[0] + t * (next[0] - current[0]),
					current[1] + t * (next[1] - current[1]),
					current[2] + t * (next[2] - current[2]),
				});
			}
		}

		return result;
	}

	@Override
	public JsonObject metadata() {
		JsonObject projection = new JsonObject();
		projection.addProperty("horizontal_fov_degrees", HORIZONTAL_FOV_DEGREES);
		projection.addProperty("vertical_fov_degrees", verticalFovDegrees());
		projection.add("principal_point_px", JsonWrite.array(this.width / 2.0, this.height / 2.0));
		projection.add("focal_length_px", JsonWrite.array(focalLengthPx(), focalLengthPx()));
		return projection;
	}
}
