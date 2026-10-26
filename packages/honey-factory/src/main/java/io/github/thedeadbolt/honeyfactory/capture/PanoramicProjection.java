package io.github.thedeadbolt.honeyfactory.capture;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code PANORAMA_310} ({@value #PROJECTION}): an equirectangular image of longitudes −155
 * to +155 degrees and elevations −70 to +90 degrees, reprojected from six square cube-face
 * renders ({@value #CUBE_FACE_ORIENTATION}, see {@link CubeFace}).
 *
 * <p>The panorama is level, as vanilla's panorama screenshot is: longitude is measured
 * around world up from the camera's yaw, positive to the camera's right, and elevation from
 * the horizontal plane, positive up. The camera's pitch does not tilt it. Output pixel
 * {@code (px, py)} looks along longitude {@code -155 + 310 px / width} and elevation
 * {@code 90 - 160 py / height}; pixel centres are at {@code + 0.5}. The 50-degree sector
 * behind the camera and everything below −70 degrees are not captured; the two side edges
 * of the image border the rear sector and do not wrap. Sampling is defined by
 * {@link PanoramaSampling}.
 */
public final class PanoramicProjection implements CaptureProjection {
	public static final String PROJECTION = "equirectangular-310x160-v1";
	public static final String CUBE_FACE_ORIENTATION = "cube-faces-v1";
	public static final String RGB_SAMPLING = "bilinear-face-clamped-v1";
	public static final String MASK_SAMPLING = "nearest-v1";
	public static final double LONGITUDE_MAX_DEGREES = 155.0;
	public static final double ELEVATION_MIN_DEGREES = -70.0;
	public static final double ELEVATION_MAX_DEGREES = 90.0;
	public static final double BLIND_SECTOR_DEGREES = 360.0 - 2 * LONGITUDE_MAX_DEGREES;
	public static final int MIN_SIZE = 16;
	public static final int MAX_SIZE = 16_384;
	public static final int MAX_FACE_SIZE = 4096;
	/** The largest output, in pixels; its sampling table takes 8 bytes per pixel. */
	public static final long MAX_PIXELS = 1L << 25;

	private static final double TAN_HALF_BLIND = Math.tan(Math.toRadians(BLIND_SECTOR_DEGREES / 2));
	private static final double SIN_ELEVATION_MIN = Math.sin(Math.toRadians(-ELEVATION_MIN_DEGREES));

	private final int width;
	private final int height;
	private final int faceSize;

	public PanoramicProjection(int width, int height, int faceSize) {
		if (width < MIN_SIZE || height < MIN_SIZE || width > MAX_SIZE || height > MAX_SIZE || (long) width * height > MAX_PIXELS) {
			throw new IllegalArgumentException("Panorama output must be " + MIN_SIZE + "-" + MAX_SIZE + " pixels on each side and at most " + MAX_PIXELS
				+ " pixels, got " + width + "x" + height);
		}

		if (faceSize < MIN_SIZE || faceSize > MAX_FACE_SIZE) {
			throw new IllegalArgumentException("Cube faces must be " + MIN_SIZE + "-" + MAX_FACE_SIZE + " pixels square, got " + faceSize);
		}

		this.width = width;
		this.height = height;
		this.faceSize = faceSize;
	}

	@Override
	public CaptureMode mode() {
		return CaptureMode.PANORAMA_310;
	}

	@Override
	public int width() {
		return this.width;
	}

	@Override
	public int height() {
		return this.height;
	}

	public int faceSize() {
		return this.faceSize;
	}

	/** Longitude, in degrees, at continuous output column {@code px}. */
	public double longitudeDegrees(double px) {
		return -LONGITUDE_MAX_DEGREES + 2 * LONGITUDE_MAX_DEGREES * px / this.width;
	}

	/** Elevation, in degrees, at continuous output row {@code py}. */
	public double elevationDegrees(double py) {
		return ELEVATION_MAX_DEGREES - (ELEVATION_MAX_DEGREES - ELEVATION_MIN_DEGREES) * py / this.height;
	}

	/** One square 90-degree view per {@link CubeFace}; the camera's pitch is not used. */
	@Override
	public List<View> views(float yawDegrees, float pitchDegrees) {
		List<View> views = new ArrayList<>(CubeFace.values().length);
		for (CubeFace face : CubeFace.values()) {
			views.add(new View(yawDegrees + face.yawOffsetDegrees(), face.pitchDegrees(), 90.0, this.faceSize, this.faceSize));
		}

		return List.copyOf(views);
	}

	@Override
	public CapturedImages assemble(List<CapturedImages> views) {
		return PanoramaSampling.of(this).reproject(views);
	}

	@Override
	public double[] direction(CameraFrame camera, double px, double py) {
		double longitude = Math.toRadians(longitudeDegrees(px));
		double elevation = Math.toRadians(elevationDegrees(py));
		double[] local = {Math.cos(elevation) * Math.sin(longitude), Math.sin(elevation), Math.cos(elevation) * Math.cos(longitude)};
		double[][] axes = levelAxes(camera);
		double[] direction = new double[3];
		for (int i = 0; i < 3; i++) {
			direction[i] = local[0] * axes[0][i] + local[1] * axes[1][i] + local[2] * axes[2][i];
		}

		return direction;
	}

	@Override
	public double @Nullable [] project(CameraFrame camera, double[] point) {
		double[] eye = camera.eye();
		double[] offset = {point[0] - eye[0], point[1] - eye[1], point[2] - eye[2]};
		double length = Math.sqrt(dot(offset, offset));
		if (length == 0.0) {
			return null;
		}

		double[][] axes = levelAxes(camera);
		double longitude = Math.toDegrees(Math.atan2(dot(offset, axes[0]), dot(offset, axes[2])));
		double elevation = Math.toDegrees(Math.asin(Math.clamp(dot(offset, axes[1]) / length, -1.0, 1.0)));
		if (Math.abs(longitude) > LONGITUDE_MAX_DEGREES || elevation < ELEVATION_MIN_DEGREES) {
			return null;
		}

		return new double[] {
			(longitude + LONGITUDE_MAX_DEGREES) / (2 * LONGITUDE_MAX_DEGREES) * this.width,
			(ELEVATION_MAX_DEGREES - elevation) / (ELEVATION_MAX_DEGREES - ELEVATION_MIN_DEGREES) * this.height,
		};
	}

	/** The panorama is level, so only {@code px} can be placed: yaw turns the direction to its longitude, pitch looks along it. */
	@Override
	public double @Nullable [] orientationPlacing(double[] direction, double px, double py) {
		double targetYaw = Math.toDegrees(Math.atan2(-direction[0], direction[2]));
		double pitch = -Math.toDegrees(Math.asin(Math.clamp(direction[1], -1.0, 1.0)));
		return new double[] {targetYaw - longitudeDegrees(px), pitch};
	}

	/**
	 * Also requires the corners to span less than half the image's width, so a box that
	 * crosses the rear blind sector, or stands over the camera, is never taken as inside.
	 */
	@Override
	public boolean contains(CameraFrame camera, double[] boxMin, double[] boxMax, double marginPx) {
		if (!CaptureProjection.super.contains(camera, boxMin, boxMax, marginPx)) {
			return false;
		}

		double left = Double.POSITIVE_INFINITY;
		double right = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			double[] pixel = project(camera, new double[] {
				(i & 1) == 0 ? boxMin[0] : boxMax[0],
				(i & 2) == 0 ? boxMin[1] : boxMax[1],
				(i & 4) == 0 ? boxMin[2] : boxMax[2],
			});
			left = Math.min(left, pixel[0]);
			right = Math.max(right, pixel[0]);
		}

		return right - left < this.width / 2.0;
	}

	/** World-space right, up and forward of the level frame: the camera's horizontal right and forward, and world up. */
	static double[][] levelAxes(CameraFrame camera) {
		double[] right = camera.basis().right();
		return new double[][] {right.clone(), {0.0, 1.0, 0.0}, {right[2], 0.0, -right[0]}};
	}

	/**
	 * Conservative test: a box is out of view only when all its corners lie in the rear blind
	 * sector or all lie below −70 degrees, both convex regions. A box spanning both regions
	 * without entering the panorama counts as in view; it then covers no pixel and is
	 * {@code subpixel}.
	 */
	@Override
	public boolean intersects(CameraFrame camera, double[] boxMin, double[] boxMax) {
		double[] eye = camera.eye();
		if (eye[0] >= boxMin[0] && eye[0] <= boxMax[0] && eye[1] >= boxMin[1] && eye[1] <= boxMax[1] && eye[2] >= boxMin[2] && eye[2] <= boxMax[2]) {
			return true;
		}

		double[][] axes = levelAxes(camera);
		boolean allRear = true;
		boolean allBelow = true;
		for (int i = 0; i < 8; i++) {
			double[] corner = {
				((i & 1) == 0 ? boxMin[0] : boxMax[0]) - eye[0],
				((i & 2) == 0 ? boxMin[1] : boxMax[1]) - eye[1],
				((i & 4) == 0 ? boxMin[2] : boxMax[2]) - eye[2],
			};
			double right = dot(corner, axes[0]);
			double up = dot(corner, axes[1]);
			double forward = dot(corner, axes[2]);
			allRear &= forward < 0 && Math.abs(right) < -forward * TAN_HALF_BLIND;
			allBelow &= -up > SIN_ELEVATION_MIN * Math.sqrt(right * right + up * up + forward * forward);
		}

		return !allRear && !allBelow;
	}

	@Override
	public JsonObject metadata() {
		JsonObject projection = new JsonObject();
		projection.addProperty("projection", PROJECTION);
		projection.addProperty("longitude_min_degrees", -LONGITUDE_MAX_DEGREES);
		projection.addProperty("longitude_max_degrees", LONGITUDE_MAX_DEGREES);
		projection.addProperty("elevation_min_degrees", ELEVATION_MIN_DEGREES);
		projection.addProperty("elevation_max_degrees", ELEVATION_MAX_DEGREES);
		projection.addProperty("blind_sector_degrees", BLIND_SECTOR_DEGREES);
		projection.add("pixels_per_degree", JsonWrite.array(this.width / (2 * LONGITUDE_MAX_DEGREES), this.height / (ELEVATION_MAX_DEGREES - ELEVATION_MIN_DEGREES)));
		projection.addProperty("cube_face_size", this.faceSize);
		projection.addProperty("cube_face_orientation", CUBE_FACE_ORIENTATION);
		projection.addProperty("rgb_sampling", RGB_SAMPLING);
		projection.addProperty("mask_sampling", MASK_SAMPLING);
		return projection;
	}

	@Override
	public boolean equals(@Nullable Object other) {
		return other instanceof PanoramicProjection that && this.width == that.width && this.height == that.height && this.faceSize == that.faceSize;
	}

	@Override
	public int hashCode() {
		return (this.width * 31 + this.height) * 31 + this.faceSize;
	}

	@Override
	public String toString() {
		return "PanoramicProjection[width=" + this.width + ", height=" + this.height + ", faceSize=" + this.faceSize + "]";
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
