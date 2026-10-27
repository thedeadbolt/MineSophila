package io.github.thedeadbolt.honeyfactory.generation;

/**
 * Whether the body fell: one drop through the air more than {@value #MAX_FALL_BLOCKS} blocks
 * below where it last stood. Walking down slopes and steps, however far in total, is not a
 * fall; the teacher plans drops of up to 3 blocks.
 */
final class FallCheck {
	static final double MAX_FALL_BLOCKS = 3.0;

	private double standing;

	/** @param startY the body's height at the clip's start, standing */
	FallCheck(double startY) {
		this.standing = startY;
	}

	/** Whether the body, now at {@code y}, has fallen; a standing body sets the height later drops are measured from. */
	boolean fell(double y, boolean onGround) {
		if (y < this.standing - MAX_FALL_BLOCKS) {
			return true;
		}

		if (onGround) {
			this.standing = y;
		}

		return false;
	}
}
