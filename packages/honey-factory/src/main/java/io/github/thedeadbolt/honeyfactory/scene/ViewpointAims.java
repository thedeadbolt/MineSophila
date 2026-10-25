package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ViewpointAim;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * The camera orientation of a placed viewpoint, by the configured {@link ViewpointAim}. Both
 * scene generators draw position and jitter first, then call this with the same stream.
 */
final class ViewpointAims {
	/**
	 * How far inside the image every corner of the aim block must project under
	 * {@code image_uniform}. One pixel keeps the mask off the border; the second covers the
	 * panorama's curved block edges between corners.
	 */
	static final double FRAME_MARGIN_PX = 2.0;
	static final String TARGET_OFF_FRAME = "target_off_frame";

	private ViewpointAims() {
	}

	/**
	 * The viewpoint at feet {@code (x, feetY, z)} looking towards aim block {@code aim}.
	 * {@code target_jitter} adds the drawn jitter to the aim at the block centre;
	 * {@code image_uniform} draws the centre's output pixel from {@code random} and rejects a
	 * draw that no camera can reach or that leaves part of the block outside the image.
	 */
	static ViewpointCandidate orient(
		ResolvedConfig.Viewpoints settings,
		CaptureProjection projection,
		SeededRandom random,
		double x,
		double feetY,
		double z,
		int[] aim,
		double yawJitter,
		double pitchJitter
	) {
		ViewpointPlan base = ViewpointPlan.lookingAt(x, feetY, z, settings.eyeHeightBlocks(), aim[0], aim[1], aim[2]);
		if (settings.aim() == ViewpointAim.TARGET_JITTER) {
			float yaw = (float) ViewpointPlan.wrapDegrees(base.yawDegrees() + yawJitter);
			float pitch = (float) Math.clamp(base.pitchDegrees() + pitchJitter, -90.0, 90.0);
			return new ViewpointCandidate.Placeable(new ViewpointPlan(x, feetY, z, yaw, pitch));
		}

		double px = random.uniform(0.0, projection.width());
		double py = random.uniform(0.0, projection.height());
		double[] eye = {x, feetY + settings.eyeHeightBlocks(), z};
		double[] centre = {aim[0] + 0.5 - eye[0], aim[1] + 0.5 - eye[1], aim[2] + 0.5 - eye[2]};
		double length = Math.sqrt(centre[0] * centre[0] + centre[1] * centre[1] + centre[2] * centre[2]);
		double[] orientation = projection.orientationPlacing(new double[] {centre[0] / length, centre[1] / length, centre[2] / length}, px, py);
		if (orientation == null) {
			return new ViewpointCandidate.Rejected(TARGET_OFF_FRAME);
		}

		// Checked at the float angles Minecraft will use.
		float yaw = (float) ViewpointPlan.wrapDegrees(orientation[0]);
		float pitch = (float) Math.clamp(orientation[1], -90.0, 90.0);
		CameraFrame camera = CameraFrame.of(new CameraPose(x, feetY, z, yaw, pitch), settings.eyeHeightBlocks());
		double[] blockMin = {aim[0], aim[1], aim[2]};
		double[] blockMax = {aim[0] + 1.0, aim[1] + 1.0, aim[2] + 1.0};
		if (!projection.contains(camera, blockMin, blockMax, FRAME_MARGIN_PX)) {
			return new ViewpointCandidate.Rejected(TARGET_OFF_FRAME);
		}

		return new ViewpointCandidate.Placeable(new ViewpointPlan(x, feetY, z, yaw, pitch));
	}
}
