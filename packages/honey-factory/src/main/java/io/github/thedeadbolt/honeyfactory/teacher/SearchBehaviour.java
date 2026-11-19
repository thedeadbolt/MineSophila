package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Search: systematic frontier coverage. The teacher walks to the frontier, the edge between
 * seen and unseen space, that best trades path length against the unseen cells near it. On
 * arrival it scans in place, turning towards the most unseen space nearby, then picks the
 * next. Without frontiers it scans in place.
 * Search may be held to the area around a point, as re-acquiring does.
 */
public final class SearchBehaviour {
	/** Cells around a frontier whose unseen share makes it worth visiting. */
	static final int GAIN_RADIUS = 4;
	/** Path cells one unseen cell near a frontier is worth. */
	static final double GAIN_WEIGHT = 1.0;
	/** Nearest-scored frontiers whose paths are planned when choosing. */
	static final int CANDIDATES = 8;
	/** Ticks bumping into something before a goal is given up. */
	static final int STUCK_TICKS = 10;

	/** Cells around the body whose unseen part a scan on arrival turns towards. */
	static final int SCAN_RADIUS = 8;
	/** Facing error at which a scan has turned far enough. */
	static final double SCAN_DONE_DEGREES = 15.0;

	private int @Nullable [] goal;
	private int scanLeft;
	/** The world yaw a scan turns to, or NaN to turn at full rate. */
	private double scanYaw = Double.NaN;
	private int stuck;
	private final Set<Long> abandoned = new HashSet<>();

	/** Forgets the goal and any abandoned frontiers, for a new clip. */
	public void reset() {
		this.goal = null;
		this.scanLeft = 0;
		this.scanYaw = Double.NaN;
		this.stuck = 0;
		this.abandoned.clear();
	}

	/**
	 * The search command for this tick.
	 *
	 * @param focus an arena-relative cell to search around, or null for the whole arena
	 * @param radius how far from {@code focus} frontiers count, in blocks
	 */
	public MotorCommand decide(TeacherInput input, TeacherContext context, int @Nullable [] focus, double radius) {
		if (this.scanLeft > 0) {
			this.scanLeft--;
			if (Double.isNaN(this.scanYaw) || Math.abs(Steering.wrap(this.scanYaw - input.pose().yawDegrees())) > SCAN_DONE_DEGREES) {
				return scan(input, context);
			}

			this.scanLeft = 0;
		}

		CameraPose pose = input.pose();
		int[] origin = context.walkable().min();
		int[] feet = {(int) Math.floor(pose.x()) - origin[0], (int) Math.floor(pose.y()) - origin[1], (int) Math.floor(pose.z()) - origin[2]};
		WalkGraph graph = WalkGraph.of(context.grid(), context.walkable(), input.seen());
		if (this.goal != null && arrived(feet, this.goal)) {
			this.goal = null;
			return startScan(input, context);
		}

		this.stuck = input.senses().bumped() ? this.stuck + 1 : 0;
		if (this.goal != null && this.stuck > STUCK_TICKS) {
			this.abandoned.add(SeenMap.key(this.goal[0], this.goal[1], this.goal[2]));
			this.goal = null;
			this.stuck = 0;
		}

		List<int[]> path = this.goal == null ? null : PathPlanner.path(graph, feet, this.goal);
		if (path == null) {
			if (this.goal != null) {
				this.abandoned.add(SeenMap.key(this.goal[0], this.goal[1], this.goal[2]));
			}

			path = choose(input, context, graph, feet, focus, radius);
			if (path == null && focus != null) {
				path = choose(input, context, graph, feet, null, 0.0);
			}

			if (path == null) {
				return startScan(input, context);
			}

			this.goal = path.getLast();
		}

		if (path.size() < 2) {
			this.goal = null;
			return startScan(input, context);
		}

		double[] aim = TrackBehaviour.ahead(graph, pose, origin, feet[1], path);
		return Steering.toward(pose, input.senses(), aim, context.eyeHeight(), context.limits(), true, false);
	}

	/** The path to the best frontier, or null when there is none worth going to. */
	private @Nullable List<int[]> choose(TeacherInput input, TeacherContext context, WalkGraph graph, int[] feet, int @Nullable [] focus, double radius) {
		List<int[]> frontiers = new ArrayList<>();
		for (int[] frontier : input.seen().frontiers(graph)) {
			if (arrived(feet, frontier) || this.abandoned.contains(SeenMap.key(frontier[0], frontier[1], frontier[2]))) {
				continue;
			}

			if (focus == null || Math.hypot(frontier[0] - focus[0], frontier[2] - focus[2]) <= radius) {
				frontiers.add(frontier);
			}
		}

		if (frontiers.isEmpty()) {
			return null;
		}

		Comparator<int[]> estimate = Comparator.comparingDouble(frontier -> PathPlanner.octile(feet, frontier) - GAIN_WEIGHT * gain(input.seen(), context, frontier));
		frontiers.sort(estimate);
		List<int[]> best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (int[] frontier : frontiers.subList(0, Math.min(CANDIDATES, frontiers.size()))) {
			List<int[]> path = PathPlanner.path(graph, feet, frontier);
			if (path == null) {
				this.abandoned.add(SeenMap.key(frontier[0], frontier[1], frontier[2]));
				continue;
			}

			double score = path.size() - GAIN_WEIGHT * gain(input.seen(), context, frontier);
			if (score < bestScore) {
				bestScore = score;
				best = path;
			}
		}

		return best;
	}

	/** Unseen cells at the body's height within {@value #GAIN_RADIUS} blocks of {@code cell}. */
	private static int gain(SeenMap seen, TeacherContext context, int[] cell) {
		int count = 0;
		for (int dz = -GAIN_RADIUS; dz <= GAIN_RADIUS; dz++) {
			for (int dx = -GAIN_RADIUS; dx <= GAIN_RADIUS; dx++) {
				int x = cell[0] + dx;
				int z = cell[2] + dz;
				if (inside(context, x, z) && !seen.seen(x, cell[1], z)) {
					count++;
				}
			}
		}

		return count;
	}

	private static boolean inside(TeacherContext context, int x, int z) {
		return x >= 0 && z >= 0 && x <= context.walkable().maxX() - context.walkable().minX() && z <= context.walkable().maxZ() - context.walkable().minZ();
	}

	private static boolean arrived(int[] feet, int[] cell) {
		return Math.abs(feet[0] - cell[0]) <= 1 && Math.abs(feet[2] - cell[2]) <= 1;
	}

	/**
	 * Starts a scan towards the most unseen space within {@value #SCAN_RADIUS} cells; with
	 * none nearby, a full-rate turn for {@link #scanTicks}.
	 */
	private MotorCommand startScan(TeacherInput input, TeacherContext context) {
		this.scanLeft = scanTicks(context) - 1;
		this.scanYaw = unseenYaw(input, context);
		return scan(input, context);
	}

	/** The world yaw towards the centre of the unseen cells at the body's height nearby, or NaN when there are none. */
	private double unseenYaw(TeacherInput input, TeacherContext context) {
		CameraPose pose = input.pose();
		int[] origin = context.walkable().min();
		int feetX = (int) Math.floor(pose.x()) - origin[0];
		int feetY = (int) Math.floor(pose.y()) - origin[1];
		int feetZ = (int) Math.floor(pose.z()) - origin[2];
		double sumX = 0.0;
		double sumZ = 0.0;
		int count = 0;
		for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
			for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
				int x = feetX + dx;
				int z = feetZ + dz;
				if ((dx != 0 || dz != 0) && inside(context, x, z) && !input.seen().seen(x, feetY, z)) {
					double length = Math.hypot(dx, dz);
					sumX += dx / length;
					sumZ += dz / length;
					count++;
				}
			}
		}

		return count == 0 || Math.hypot(sumX, sumZ) < 1e-9 ? Double.NaN : Math.toDegrees(Math.atan2(-sumX, sumZ));
	}

	/** Ticks a scan lasts: enough to turn the part of a full circle the camera does not already see. */
	static int scanTicks(TeacherContext context) {
		double degrees = context.projection().mode() == CaptureMode.PANORAMA_310 ? 90.0 : 360.0;
		double ramp = context.limits().maxTurnRate() / context.limits().maxTurnAccel();
		return (int) Math.ceil(degrees / context.limits().maxTurnRate() + ramp / 2.0);
	}

	/** Turning in place, towards the scan's yaw or at full rate, looking level. */
	private MotorCommand scan(TeacherInput input, TeacherContext context) {
		double pitch = Steering.rate(-input.pose().pitchDegrees(), context.limits().maxPitchAccel(), context.limits().maxPitchRate());
		double turn = Double.isNaN(this.scanYaw) ? context.limits().maxTurnRate()
			: Steering.rate(Steering.wrap(this.scanYaw - input.pose().yawDegrees()), context.limits().maxTurnAccel(), context.limits().maxTurnRate());
		return new MotorCommand(0.0, 0.0, turn, pitch, false, 0);
	}
}
