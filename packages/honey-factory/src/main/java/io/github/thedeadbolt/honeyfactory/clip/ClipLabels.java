package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SchemaVersion;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import org.jspecify.annotations.Nullable;

/**
 * A clip frame's labels, from that frame's own pose and masks only. Honey cut by the frame
 * edge or barely visible is labelled as it is; nothing is rejected here.
 */
public final class ClipLabels {
	private ClipLabels() {
	}

	/**
	 * The frame record of {@code captured}.
	 *
	 * @param target the honey's absolute cell, or null in a scene without honey
	 * @param labels the tick's labels on frame 0 and on each tick's last frame, otherwise null
	 */
	public static FrameRecord frame(String clipId, int frame, int tick, int subFrame, int framesPerTick, ClipEnvironment.Frame captured, int @Nullable [] target,
		CaptureProjection projection, double eyeHeight, FrameRecord.@Nullable TickLabels labels) {
		CapturedImages images = captured.images();
		CameraPose pose = wrapped(captured.pose());
		BlockBox box = target == null ? null : BlockBox.cell(target);
		CameraFrame camera = CameraFrame.of(pose, eyeHeight);
		SampleRecord.Target labelled = SampleRecord.Target.of(box != null, box != null && projection.intersects(camera, box),
			MaskStatistics.of(images.visibleMask(), images.width(), images.height()), images.unoccludedPixels(), box == null ? null : camera.distanceTo(box.center()));
		return new FrameRecord(SchemaVersion.CURRENT, clipId, frame, tick, subFrame, framesPerTick, captured.partialTick(), pose, labelled,
			box != null && images.unoccludedTouchesEdge(), target == null ? null : bearing(pose, eyeHeight, target), labels);
	}

	/**
	 * {@code pose} with its yaw in [-180, 180). A turn across ±180 degrees interpolates to
	 * yaws such as 181.5 between ticks; the camera looks the same way either way.
	 */
	static CameraPose wrapped(CameraPose pose) {
		float yaw = pose.yawDegrees();
		return yaw >= -180.0f && yaw < 180.0f ? pose : new CameraPose(pose.x(), pose.y(), pose.z(), (float) ClipDriver.wrap(yaw), pose.pitchDegrees());
	}

	/**
	 * Where the centre of {@code cell} lies from the body at {@code pose}: degrees right of its
	 * facing in (-180, 180], degrees above the horizon, and blocks from the eye.
	 */
	public static FrameRecord.Bearing bearing(CameraPose pose, double eyeHeight, int[] cell) {
		double dx = cell[0] + 0.5 - pose.x();
		double dy = cell[1] + 0.5 - (pose.y() + eyeHeight);
		double dz = cell[2] + 0.5 - pose.z();
		double yaw = Math.toRadians(pose.yawDegrees());
		// Minecraft's facing at yaw 0 is +z, and its right is -x.
		double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
		double right = -Math.cos(yaw) * dx - Math.sin(yaw) * dz;
		double horizontal = Math.hypot(dx, dz);
		return new FrameRecord.Bearing(Math.toDegrees(Math.atan2(right, forward)), Math.toDegrees(Math.atan2(dy, horizontal)), Math.sqrt(horizontal * horizontal + dy * dy));
	}
}
