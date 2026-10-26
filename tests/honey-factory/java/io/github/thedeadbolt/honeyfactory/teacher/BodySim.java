package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.control.MotorState;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;

/**
 * A kinematic body for closed-loop teacher tests: M11's turning limits, a fixed walking
 * speed, an automatic step up of one block, and blocks that stop it along one axis while it
 * slides along the other, as Minecraft's do. It falls straight onto whatever lies below.
 */
final class BodySim {
	/** Blocks per tick at full forward, about Minecraft's walking speed. */
	static final double WALK = 0.2158;
	/** Half the body's width, kept clear of blocks. */
	private static final double RADIUS = 0.3;

	private final SightGrid grid;
	private CameraPose pose;
	private MotorState motor = MotorState.ZERO;
	private double[] velocity = new double[3];
	private MotorCommand last = MotorCommand.IDLE;
	private boolean bumped;

	BodySim(SightGrid grid, CameraPose start) {
		this.grid = grid;
		this.pose = start;
	}

	CameraPose pose() {
		return this.pose;
	}

	BodySenses senses() {
		return BodySenses.of(this.pose, this.velocity, this.motor, true, this.bumped, false, this.last);
	}

	void step(MotorCommand command) {
		MotorLimits.DEFAULT.check(command);
		MotorState.Achieved achieved = this.motor.apply(command, MotorLimits.DEFAULT, this.pose.yawDegrees(), this.pose.pitchDegrees());
		this.motor = achieved.state();
		double yaw = Math.toRadians(achieved.yaw());
		double speed = WALK * command.forward();
		double targetX = this.pose.x() - Math.sin(yaw) * speed;
		double targetZ = this.pose.z() + Math.cos(yaw) * speed;
		double y = this.pose.y();
		this.bumped = false;
		int feet = (int) Math.floor(y);
		if (blocked(targetX, feet, targetZ) && !blocked(targetX, feet + 1, targetZ) && !blocked(targetX, feet + 2, targetZ)) {
			// Auto-jump up a one-block step.
			y = feet + 1;
			feet++;
		}

		// As Minecraft does, each axis moves on its own, so the body slides along a wall.
		double x = blocked(targetX, feet, this.pose.z()) ? this.pose.x() : targetX;
		double z = blocked(x, feet, targetZ) ? this.pose.z() : targetZ;
		this.bumped = x != targetX || z != targetZ;

		while (y > 1 && !this.grid.solid((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z))) {
			y -= 1;
		}

		this.velocity = new double[] {x - this.pose.x(), y - this.pose.y(), z - this.pose.z()};
		this.pose = new CameraPose(x, y, z, achieved.yaw(), achieved.pitch());
		this.last = command;
	}

	/** Whether the body's footprint at height {@code y} overlaps a block. */
	private boolean blocked(double x, int y, double z) {
		for (double dx : new double[] {-RADIUS, RADIUS}) {
			for (double dz : new double[] {-RADIUS, RADIUS}) {
				if (this.grid.solid((int) Math.floor(x + dx), y, (int) Math.floor(z + dz))) {
					return true;
				}
			}
		}

		return false;
	}
}
