package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * The teacher's last check before it walks: the ground just ahead of the body's centre must
 * be floor it has seen within a safe drop. Minecraft holds a body while any of its width
 * stands on a block, so only the centre leaving the floor would drop it; the body may walk a
 * one-block strip along a ledge. At a ledge it stops; where the
 * floor ahead is not seen yet it walks to within {@value #PEEK} of it, stops and looks down
 * at the highest cell of it not seen, and walks on once it has seen the floor. Only what the teacher has seen counts, so the brake is as realizable as the rest.
 */
final class EdgeBrake {
	/** Distances ahead of the body's centre it checks, in blocks: past a stride and the slide after stopping. */
	static final double[] AHEAD = {0.45, 0.9};
	/**
	 * How near floor not seen yet the body may walk before it stops to look, in blocks: to
	 * the edge of the floor it stands on, from where a wall top's foot can be seen.
	 */
	static final double PEEK = 0.3;
	/** Checked under the body's centre only, see above. */
	private static final double CENTRE = 0.0;

	private EdgeBrake() {
	}

	/** {@code command}, stopped (and looking down where the floor is unknown) when walking on would risk a fall. */
	static MotorCommand apply(MotorCommand command, TeacherInput input, TeacherContext context) {
		if (command.forward() <= 0.0) {
			return command;
		}

		CameraPose pose = input.pose();
		int[] origin = context.walkable().min();
		int feet = (int) Math.floor(pose.y()) - origin[1];
		WalkGraph graph = WalkGraph.of(context.grid(), context.walkable(), input.seen());
		double heading = pose.yawDegrees() + command.turnRate();
		WalkGraph.Ahead ahead = graph.firstUnsafe(pose.x(), pose.z(), feet, origin, heading, AHEAD, CENTRE);
		if (ahead == null || ahead.footing() == WalkGraph.Footing.UNKNOWN && graph.firstUnsafe(pose.x(), pose.z(), feet, origin, heading, new double[] {PEEK}, CENTRE) == null) {
			return command;
		}

		double pitchRate = command.pitchRate();
		if (ahead.footing() == WalkGraph.Footing.UNKNOWN) {
			// The highest unseen cell in that column, below a wall top perhaps far below the view.
			int unseen = feet - 1;
			while (unseen > 0 && input.seen().seen(ahead.x(), unseen, ahead.z())) {
				unseen--;
			}

			double[] floor = {ahead.x() + origin[0] + 0.5, unseen + 1 + origin[1], ahead.z() + origin[2] + 0.5};
			pitchRate = Steering.toward(pose, input.senses(), floor, context.eyeHeight(), context.limits(), false, true).pitchRate();
		}

		return new MotorCommand(0.0, 0.0, command.turnRate(), pitchRate, false, 0);
	}
}
