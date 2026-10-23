package io.github.thedeadbolt.honeyfactory.capture;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How one capture mode turns a camera frame into output pixels: the renders it needs,
 * which geometry it can see and the metadata a manifest records for it. The capture
 * service and scene generators never branch on the mode; they ask the projection.
 */
public sealed interface CaptureProjection permits PerspectiveProjection, PanoramicProjection {
	/** Minecraft's camera near plane, in blocks. */
	double NEAR_PLANE_BLOCKS = 0.05;

	/**
	 * One square-pixel perspective render with the camera at Minecraft yaw and pitch
	 * {@code yawDegrees} and {@code pitchDegrees}. {@code verticalFovDegrees} is the full
	 * vertical field of view.
	 */
	record View(float yawDegrees, float pitchDegrees, double verticalFovDegrees, int width, int height) {
	}

	CaptureMode mode();

	int width();

	int height();

	/** The renders one capture needs from a camera at the given yaw and pitch, in order. */
	List<View> views(float yawDegrees, float pitchDegrees);

	/**
	 * The output images from one capture per view, given in {@link #views} order. All views
	 * come from one frozen world state and camera position.
	 */
	CapturedImages assemble(List<CapturedImages> views);

	/** World-space unit direction through output pixel coordinates {@code (px, py)}; pixel centres are at {@code + 0.5}. */
	double[] direction(CameraFrame camera, double px, double py);

	/** Whether any part of the axis-aligned box lies inside the captured field of view. */
	boolean intersects(CameraFrame camera, double[] boxMin, double[] boxMax);

	/** Whether any part of the blocks in {@code box} lies inside the captured field of view. */
	default boolean intersects(CameraFrame camera, BlockBox box) {
		return intersects(camera, box.lowerCorner(), box.upperCorner());
	}

	/** The manifest's {@code capture.projection} members for this projection. */
	JsonObject metadata();

	/**
	 * The projection for a resolved capture mode.
	 *
	 * @param cubeFaceSize the cube-face size, required for {@link CaptureMode#PANORAMA_310} only
	 */
	static CaptureProjection of(CaptureMode mode, int width, int height, @Nullable Integer cubeFaceSize) {
		return switch (mode) {
			case PERSPECTIVE_90 -> new PerspectiveProjection(width, height);
			case PANORAMA_310 -> {
				if (cubeFaceSize == null) {
					throw new IllegalArgumentException("PANORAMA_310 needs a cube-face size");
				}

				yield new PanoramicProjection(width, height, cubeFaceSize);
			}
		};
	}
}
