package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Track: the honey is visible, so turn to it and approach it. When the straight way is
 * clear of blocks the teacher has seen, it steers at the honey itself; otherwise it follows
 * a path to the nearest walkable cell within reach of the honey, steering two cells ahead,
 * and once in that cell walks to its centre.
 * Within reach it stops and keeps the honey centred. With no known way to it (across a
 * gap, below a ledge) it turns to the honey and watches it instead of walking at it.
 */
public final class TrackBehaviour {
	/** Path cells ahead of the body the teacher steers at. */
	static final int LOOK_AHEAD = 2;
	/** Half the body's width, which must clear known blocks. */
	static final double BODY_RADIUS = 0.3;
	private static final double LINE_STEP = 0.25;

	private TrackBehaviour() {
	}

	/** Whether the body's eye is within reach of the honey's centre, horizontally. */
	public static boolean reached(CameraPose pose, int[] honey, TeacherContext context) {
		return Math.hypot(honey[0] + 0.5 - pose.x(), honey[2] + 0.5 - pose.z()) <= context.reachDistanceBlocks();
	}

	/** Tracks the visible honey; {@code input.honeySeen()} must be set. */
	public static Teacher.Decision decide(TeacherInput input, TeacherContext context) {
		int[] honey = input.honeySeen();
		if (honey == null) {
			throw new IllegalArgumentException("tracking needs visible honey");
		}

		return new Teacher.Decision(approach(input, honey, context), TeacherMode.TRACK, new FrameRecord.Belief(FrameRecord.Belief.Source.SEEN, honey.clone(), input.tick() - 1));
	}

	/** The command that approaches the honey at absolute cell {@code honey}, by what the teacher knows. */
	static MotorCommand approach(TeacherInput input, int[] honey, TeacherContext context) {
		CameraPose pose = input.pose();
		double[] centre = {honey[0] + 0.5, honey[1] + 0.5, honey[2] + 0.5};
		if (reached(pose, honey, context)) {
			return Steering.toward(pose, input.senses(), centre, context.eyeHeight(), context.limits(), false, true);
		}

		WalkGraph graph = WalkGraph.of(context.grid(), context.walkable(), input.seen());
		int[] origin = context.walkable().min();
		int[] feet = {(int) Math.floor(pose.x()) - origin[0], (int) Math.floor(pose.y()) - origin[1], (int) Math.floor(pose.z()) - origin[2]};
		int[] target = {honey[0] - origin[0], honey[1] - origin[1], honey[2] - origin[2]};
		double[] aim = centre;
		boolean walk = true;
		if (!clear(graph, pose, origin, feet[1], centre)) {
			List<int[]> path = pathBeside(graph, feet, target, context.reachDistanceBlocks());
			if (path == null) {
				walk = false;
			} else if (path.size() > 1) {
				aim = ahead(graph, pose, origin, feet[1], path);
			} else {
				int[] cell = path.getFirst();
				aim = new double[] {cell[0] + 0.5 + origin[0], cell[1] + origin[1], cell[2] + 0.5 + origin[2]};
			}
		}

		MotorCommand steer = Steering.toward(pose, input.senses(), aim, context.eyeHeight(), context.limits(), walk, false);
		MotorCommand look = Steering.toward(pose, input.senses(), centre, context.eyeHeight(), context.limits(), false, true);
		return new MotorCommand(steer.forward(), steer.strafe(), steer.turnRate(), look.pitchRate(), false, 0);
	}

	/**
	 * The point to steer at along {@code path}: the cell {@value #LOOK_AHEAD} ahead when the
	 * body's width fits along the straight line there, otherwise the next cell, so the body
	 * never cuts a corner into a block.
	 */
	static double[] ahead(WalkGraph graph, CameraPose pose, int[] origin, int feetY, List<int[]> path) {
		double[] body = {pose.x() - origin[0], pose.y() - origin[1], pose.z() - origin[2]};
		for (int index = Math.min(LOOK_AHEAD, path.size() - 1); index >= 1; index--) {
			int[] cell = path.get(index);
			double[] centre = {cell[0] + 0.5, cell[1], cell[2] + 0.5};
			if (index == 1 || graph.straight(body, centre, Math.min(feetY, cell[1]), BODY_RADIUS)) {
				return new double[] {centre[0] + origin[0], centre[1] + origin[1], centre[2] + origin[2]};
			}
		}

		throw new IllegalStateException("a path of " + path.size() + " cells has no next cell");
	}

	/**
	 * The shortest known path to a walkable cell from whose centre the honey at {@code target}
	 * is within {@code reach} horizontally, or null. Such a cell may stand level with the
	 * honey, one block below it or one block up on a block beside it.
	 */
	public static @Nullable List<int[]> pathBeside(WalkGraph graph, int[] feet, int[] target, double reach) {
		List<int[]> goals = new ArrayList<>();
		int span = (int) Math.ceil(reach);
		for (int dx = -span; dx <= span; dx++) {
			for (int dz = -span; dz <= span; dz++) {
				if ((dx != 0 || dz != 0) && Math.hypot(dx, dz) <= reach) {
					for (int dy = -1; dy <= 1; dy++) {
						if (graph.walkable(target[0] + dx, target[1] + dy, target[2] + dz)) {
							goals.add(new int[] {target[0] + dx, target[1] + dy, target[2] + dz});
						}
					}
				}
			}
		}

		return PathPlanner.path(graph, feet, goals);
	}

	/**
	 * Whether the body could walk straight towards {@code centre}: under its whole width, every
	 * column on the way has seen floor at its feet's height.
	 */
	static boolean clear(WalkGraph graph, CameraPose pose, int[] origin, int feetY, double[] centre) {
		double dx = centre[0] - pose.x();
		double dz = centre[2] - pose.z();
		double length = Math.hypot(dx, dz);
		int honeyX = (int) Math.floor(centre[0]) - origin[0];
		int honeyZ = (int) Math.floor(centre[2]) - origin[2];
		for (double t = LINE_STEP; t < length; t += LINE_STEP) {
			double px = pose.x() + dx * t / length;
			double pz = pose.z() + dz * t / length;
			if ((int) Math.floor(px) - origin[0] == honeyX && (int) Math.floor(pz) - origin[2] == honeyZ) {
				return true;
			}

			for (double ox : new double[] {-BODY_RADIUS, BODY_RADIUS}) {
				for (double oz : new double[] {-BODY_RADIUS, BODY_RADIUS}) {
					int x = (int) Math.floor(px + ox) - origin[0];
					int z = (int) Math.floor(pz + oz) - origin[2];
					if ((x != honeyX || z != honeyZ) && !graph.standable(x, feetY, z)) {
						return false;
					}
				}
			}
		}

		return true;
	}
}
