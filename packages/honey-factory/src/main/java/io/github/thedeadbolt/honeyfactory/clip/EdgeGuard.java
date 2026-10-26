package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.WalkGraph;

/**
 * Keeps the executed command on the platform (M12-2). Drift and scripts may take the body
 * close to a ledge, so the data holds near-edge states and the teacher's recoveries from
 * them, but never over it: when the ground just ahead, by the scene's true layout, is a
 * ledge or the arena's edge, the executed command stops walking and keeps turning. The
 * teacher's command, the label, is never changed. Scripts may know the scene; the teacher
 * may not, and has its own brake.
 */
public final class EdgeGuard {
	/** Distances along the walking direction it checks, in blocks: past a stride and the slide after stopping. */
	static final double[] AHEAD = {0.45, 0.9};
	/** Half the body's width. */
	static final double RADIUS = 0.3;
	private static final double RUN_STEP = 0.25;
	/** How far drift looks for a hazard to push towards, in blocks. */
	public static final double HAZARD_REACH = 4.0;
	/** Headings searched for the nearest hazard. */
	private static final int HAZARD_HEADINGS = 16;

	private final WalkGraph truth;
	private final int[] origin;

	public EdgeGuard(SightGrid grid, BlockBox core) {
		this.truth = WalkGraph.of(grid, core, SeenMap.everything(core));
		this.origin = core.min();
	}

	/** {@code command}, or the same command without walking when walking on would leave the floor. */
	public MotorCommand apply(MotorCommand command, CameraPose pose) {
		if (command.forward() == 0.0) {
			return command;
		}

		double sign = Math.signum(command.forward());
		double[] distances = {sign * AHEAD[0], sign * AHEAD[1]};
		int feet = (int) Math.floor(pose.y()) - this.origin[1];
		if (this.truth.firstUnsafe(pose.x(), pose.z(), feet, this.origin, pose.yawDegrees() + command.turnRate(), distances, RADIUS) == null) {
			return command;
		}

		return new MotorCommand(0.0, command.strafe(), command.turnRate(), command.pitchRate(), command.jump(), command.delayTicks());
	}

	/**
	 * Degrees to turn right (negative: left) to face the nearest ledge, wall or arena edge
	 * within {@code reach} blocks of {@code pose} at its feet's height, by the true layout; NaN
	 * when none is that close.
	 */
	public double hazardBearing(CameraPose pose, double reach) {
		int feet = (int) Math.floor(pose.y()) - this.origin[1];
		double nearest = Double.MAX_VALUE;
		double bearing = Double.NaN;
		for (int index = 0; index < HAZARD_HEADINGS; index++) {
			double heading = 360.0 * index / HAZARD_HEADINGS;
			double yaw = Math.toRadians(heading);
			for (double distance = RUN_STEP; distance <= reach + 1e-9 && distance < nearest; distance += RUN_STEP) {
				int x = (int) Math.floor(pose.x() - Math.sin(yaw) * distance) - this.origin[0];
				int z = (int) Math.floor(pose.z() + Math.cos(yaw) * distance) - this.origin[2];
				if (this.truth.footing(x, feet, z) != WalkGraph.Footing.SAFE) {
					nearest = distance;
					bearing = ClipDriver.wrap(heading - pose.yawDegrees());
					break;
				}
			}
		}

		return bearing;
	}

	/** How far, up to {@code max} blocks, the body could walk from {@code from} along {@code headingDegrees} with floor under its whole width. */
	public double clearRun(CameraPose from, double headingDegrees, double max) {
		int feet = (int) Math.floor(from.y()) - this.origin[1];
		double yaw = Math.toRadians(headingDegrees);
		double run = 0.0;
		for (double distance = RUN_STEP; distance <= max + 1e-9; distance += RUN_STEP) {
			double x = from.x() - Math.sin(yaw) * distance;
			double z = from.z() + Math.cos(yaw) * distance;
			if (this.truth.firstUnsafe(x, z, feet, this.origin, headingDegrees, new double[] {0.0, RADIUS}, RADIUS) != null) {
				return run;
			}

			run = distance;
		}

		return Math.min(run, max);
	}
}
