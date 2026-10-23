package io.github.thedeadbolt.honeyfactory.capture;

import io.github.thedeadbolt.honeyfactory.contract.CameraBasis;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * Where a capture looks from: the absolute eye position and the world-space camera basis.
 * The eye is the player's feet position plus the eye height, as Minecraft places it.
 */
public record CameraFrame(double[] eye, CameraBasis basis) {
	public static CameraFrame of(CameraPose pose, double eyeHeightBlocks) {
		return new CameraFrame(new double[] {pose.x(), pose.y() + eyeHeightBlocks, pose.z()}, CameraBasis.of(pose.yawDegrees(), pose.pitchDegrees()));
	}

	/** {@code point} in camera coordinates: distances along right, up and forward. */
	public double[] toCamera(double[] point) {
		double dx = point[0] - this.eye[0];
		double dy = point[1] - this.eye[1];
		double dz = point[2] - this.eye[2];
		return new double[] {dot(dx, dy, dz, this.basis.right()), dot(dx, dy, dz, this.basis.up()), dot(dx, dy, dz, this.basis.forward())};
	}

	/** Euclidean distance from the eye to {@code point}, in blocks. */
	public double distanceTo(double[] point) {
		return Math.sqrt(Math.pow(point[0] - this.eye[0], 2) + Math.pow(point[1] - this.eye[1], 2) + Math.pow(point[2] - this.eye[2], 2));
	}

	private static double dot(double x, double y, double z, double[] axis) {
		return x * axis[0] + y * axis[1] + z * axis[2];
	}
}
