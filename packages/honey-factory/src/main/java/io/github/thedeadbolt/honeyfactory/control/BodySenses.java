package io.github.thedeadbolt.honeyfactory.control;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.CameraBasis;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * What the body feels in one step: its own motion relative to itself, where its head
 * points up or down, contact, and the command it sent last. Never where it is, which way
 * is north, or where the honey is.
 *
 * @param forwardSpeed speed along the body's horizontal facing, blocks per tick
 * @param strafeSpeed speed to the body's right, blocks per tick
 * @param verticalSpeed upward speed, blocks per tick
 * @param yawRate turning rate, degrees per tick, positive right
 * @param pitchRate looking rate, degrees per tick, positive down
 * @param pitch head pitch in degrees, positive down
 * @param down world down as a unit vector in camera coordinates (right, up, forward)
 * @param onGround whether the body stands on something
 * @param bumped whether the body ran into something this tick
 * @param inWater whether the body is in water
 * @param lastCommand the command executed last step, as sent
 */
public record BodySenses(
	double forwardSpeed,
	double strafeSpeed,
	double verticalSpeed,
	double yawRate,
	double pitchRate,
	float pitch,
	double[] down,
	boolean onGround,
	boolean bumped,
	boolean inWater,
	MotorCommand lastCommand
) {
	/** Senses from the body's pose and world velocity, in blocks per tick. */
	public static BodySenses of(CameraPose pose, double[] worldVelocity, MotorState achieved, boolean onGround, boolean bumped, boolean inWater, MotorCommand last) {
		CameraBasis level = CameraBasis.of(pose.yawDegrees(), 0.0);
		CameraBasis camera = CameraBasis.of(pose.yawDegrees(), pose.pitchDegrees());
		double[] down = {-camera.right()[1], -camera.up()[1], -camera.forward()[1]};
		return new BodySenses(
			dot(worldVelocity, level.forward()), dot(worldVelocity, level.right()), worldVelocity[1],
			achieved.yawRate(), achieved.pitchRate(), pose.pitchDegrees(), down, onGround, bumped, inWater, last
		);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("forward_speed", this.forwardSpeed);
		json.addProperty("strafe_speed", this.strafeSpeed);
		json.addProperty("vertical_speed", this.verticalSpeed);
		json.addProperty("yaw_rate", this.yawRate);
		json.addProperty("pitch_rate", this.pitchRate);
		json.addProperty("pitch", this.pitch);
		json.add("down", JsonWrite.array(this.down));
		json.addProperty("on_ground", this.onGround);
		json.addProperty("bumped", this.bumped);
		json.addProperty("in_water", this.inWater);
		json.add("last_command", this.lastCommand.toJson());
		return json;
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
