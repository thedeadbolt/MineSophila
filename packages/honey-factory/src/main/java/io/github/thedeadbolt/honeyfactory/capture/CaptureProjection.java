package io.github.thedeadbolt.honeyfactory.capture;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How one capture mode turns a camera frame into output pixels: the renders it needs,
 * which geometry it can see and the metadata a manifest records for it. The capture
 * service and scene generators never branch on the mode; they ask the projection.
 */
public sealed interface CaptureProjection permits PerspectiveProjection {
	/** Minecraft's camera near plane, in blocks. */
	double NEAR_PLANE_BLOCKS = 0.05;

	/**
	 * One square-pixel perspective render, rotated from the camera by the given offsets.
	 * {@code verticalFovDegrees} is the full vertical field of view.
	 */
	record View(double yawOffsetDegrees, double pitchOffsetDegrees, double verticalFovDegrees, int width, int height) {
	}

	CaptureMode mode();

	int width();

	int height();

	/** The renders one capture needs, in order. */
	List<View> views();

	/** World-space unit direction through output pixel coordinates {@code (px, py)}; pixel centres are at {@code + 0.5}. */
	double[] direction(CameraFrame camera, double px, double py);

	/** Whether any part of the axis-aligned box lies inside the captured field of view. */
	boolean intersects(CameraFrame camera, double[] boxMin, double[] boxMax);

	/** The manifest's {@code capture.projection} members for this projection. */
	JsonObject metadata();

	/** The projection for a resolved capture mode; panoramic capture arrives in M5. */
	static CaptureProjection of(CaptureMode mode, int width, int height, @Nullable Integer cubeFaceSize) {
		return switch (mode) {
			case PERSPECTIVE_90 -> new PerspectiveProjection(width, height);
			case PANORAMA_310 -> throw new UnsupportedOperationException("PANORAMA_310 capture is implemented in M5");
		};
	}
}
