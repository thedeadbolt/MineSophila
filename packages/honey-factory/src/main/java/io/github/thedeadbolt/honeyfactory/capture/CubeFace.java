package io.github.thedeadbolt.honeyfactory.capture;

/**
 * The six cube faces of {@code cube-faces-v1}, in render order. Each is a square 90-degree
 * render at the camera's yaw plus {@link #yawOffsetDegrees} and at the absolute pitch
 * {@link #pitchDegrees}, as vanilla's panorama screenshot renders them.
 *
 * <p>Axes are in the level frame {@code (right, up, forward)}: horizontal right and
 * forward from the camera's yaw, and world up. They are the camera basis Minecraft uses
 * for each face's yaw and pitch.
 */
public enum CubeFace {
	FRONT(0, 0, new double[] {0, 0, 1}, new double[] {1, 0, 0}, new double[] {0, 1, 0}),
	RIGHT(90, 0, new double[] {1, 0, 0}, new double[] {0, 0, -1}, new double[] {0, 1, 0}),
	BACK(180, 0, new double[] {0, 0, -1}, new double[] {-1, 0, 0}, new double[] {0, 1, 0}),
	LEFT(270, 0, new double[] {-1, 0, 0}, new double[] {0, 0, 1}, new double[] {0, 1, 0}),
	UP(0, -90, new double[] {0, 1, 0}, new double[] {1, 0, 0}, new double[] {0, 0, -1}),
	DOWN(0, 90, new double[] {0, -1, 0}, new double[] {1, 0, 0}, new double[] {0, 0, 1});

	private final float yawOffsetDegrees;
	private final float pitchDegrees;
	private final double[] forward;
	private final double[] right;
	private final double[] up;

	CubeFace(float yawOffsetDegrees, float pitchDegrees, double[] forward, double[] right, double[] up) {
		this.yawOffsetDegrees = yawOffsetDegrees;
		this.pitchDegrees = pitchDegrees;
		this.forward = forward;
		this.right = right;
		this.up = up;
	}

	public float yawOffsetDegrees() {
		return this.yawOffsetDegrees;
	}

	public float pitchDegrees() {
		return this.pitchDegrees;
	}

	public double[] forward() {
		return this.forward.clone();
	}

	public double[] right() {
		return this.right.clone();
	}

	public double[] up() {
		return this.up.clone();
	}

	double dotForward(double[] direction) {
		return dot(this.forward, direction);
	}

	double dotRight(double[] direction) {
		return dot(this.right, direction);
	}

	double dotUp(double[] direction) {
		return dot(this.up, direction);
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
