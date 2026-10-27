package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.scene.SightLines;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.TrackBehaviour;
import io.github.thedeadbolt.honeyfactory.teacher.WalkGraph;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * Where a clip starts, by type. Starts are drawn as still viewpoints are; types that need the
 * layout check it with the true scene, which the generator may know and the teacher never
 * does:
 *
 * <ul>
 *   <li><b>Detour</b>: the honey fully hidden from the start, and the shortest walk to it at
 *       least {@value #MIN_DETOUR_RATIO} times the straight distance;
 *   <li><b>Occlusion</b>: the honey visible from the start, and a straight walk to a waypoint
 *       that hides it for at least {@value #MIN_HIDDEN_TICKS} ticks;
 *   <li><b>Scan</b> and <b>no honey</b>: facing a random way;
 *   <li>otherwise the first placeable draw.
 * </ul>
 *
 * A detour or occlusion with no qualifying start in its draws falls back to approach.
 */
public final class ClipStarts {
	public static final double MIN_DETOUR_RATIO = 1.3;
	public static final int MIN_HIDDEN_TICKS = 6;
	/** Waypoints tried per occlusion start. */
	static final int WAYPOINTS = 40;
	static final double MIN_WAYPOINT_DISTANCE = 3.0;
	static final double MAX_WAYPOINT_DISTANCE = 10.0;
	/** Walking speed the occlusion check steps by, blocks per tick. */
	static final double WALK = 0.2158;
	static final double BODY_RADIUS = 0.3;

	/**
	 * A clip's start.
	 *
	 * @param draw the viewpoint draw it came from
	 * @param waypoint where an occlusion clip walks to, absolute, or null
	 * @param pathRatio a detour's walk against its straight distance, otherwise 0
	 * @param hiddenTicks how long an occlusion's walk hides the honey, otherwise 0
	 */
	public record Start(ClipType type, CameraPose pose, int draw, double @Nullable [] waypoint, double pathRatio, int hiddenTicks) {
	}

	private ClipStarts() {
	}

	/** Whether {@code type} starts with the honey fully hidden, so its draws must not require a view of it. */
	public static boolean hidesHoney(ClipType type) {
		return type == ClipType.DETOUR;
	}

	/**
	 * Chooses a start for a clip of {@code requested} type.
	 *
	 * @param draws the placeable start of each draw index, or null when that draw is not placeable
	 * @param honey the honey's absolute cell, or null without honey
	 * @return the start, or null when no draw is placeable
	 */
	public static @Nullable Start choose(ClipType requested, IntFunction<@Nullable CameraPose> draws, int firstDraw, int maxDraws, SightGrid grid, BlockBox core, int @Nullable [] honey,
		long startSeed, double eyeHeight) {
		int first = -1;
		CameraPose firstPose = null;
		for (int draw = firstDraw; draw < firstDraw + maxDraws; draw++) {
			CameraPose pose = draws.apply(draw);
			if (pose == null) {
				continue;
			}

			if (firstPose == null) {
				first = draw;
				firstPose = pose;
			}

			Start start = switch (requested) {
				case DETOUR -> honey == null ? null : detour(pose, draw, grid, core, honey, eyeHeight);
				case OCCLUSION -> honey == null ? null : occlusion(pose, draw, grid, core, honey, startSeed, eyeHeight);
				case SCAN, NO_HONEY -> new Start(requested, turned(pose, startSeed), draw, null, 0.0, 0);
				case APPROACH, PASS_BY -> new Start(requested, pose, draw, null, 0.0, 0);
			};
			if (start != null) {
				return start;
			}
		}

		return firstPose == null ? null : new Start(ClipType.APPROACH, firstPose, first, null, 0.0, 0);
	}

	private static CameraPose turned(CameraPose pose, long seed) {
		float yaw = (float) (new SplittableRandom(seed).nextDouble() * 360.0 - 180.0);
		return new CameraPose(pose.x(), pose.y(), pose.z(), yaw, pose.pitchDegrees());
	}

	private static @Nullable Start detour(CameraPose pose, int draw, SightGrid grid, BlockBox core, int[] honey, double eyeHeight) {
		int[] origin = core.min();
		double[] eye = {pose.x() - origin[0], pose.y() + eyeHeight - origin[1], pose.z() - origin[2]};
		int[] target = {honey[0] - origin[0], honey[1] - origin[1], honey[2] - origin[2]};
		if (SightLines.clearFraction(grid, eye, target) > 0.0) {
			return null;
		}

		WalkGraph graph = WalkGraph.of(grid, core, SeenMap.everything(core));
		int[] feet = {(int) Math.floor(pose.x()) - origin[0], (int) Math.floor(pose.y()) - origin[1], (int) Math.floor(pose.z()) - origin[2]};
		List<int[]> path = TrackBehaviour.pathBeside(graph, feet, target);
		double straight = Math.hypot(target[0] + 0.5 - eye[0], target[2] + 0.5 - eye[2]);
		if (path == null || straight <= 0.0) {
			return null;
		}

		double ratio = length(path) / straight;
		return ratio >= MIN_DETOUR_RATIO ? new Start(ClipType.DETOUR, pose, draw, null, ratio, 0) : null;
	}

	private static @Nullable Start occlusion(CameraPose pose, int draw, SightGrid grid, BlockBox core, int[] honey, long seed, double eyeHeight) {
		int[] origin = core.min();
		int[] target = {honey[0] - origin[0], honey[1] - origin[1], honey[2] - origin[2]};
		double[] from = {pose.x() - origin[0], pose.y() - origin[1], pose.z() - origin[2]};
		if (SightLines.clearFraction(grid, new double[] {from[0], from[1] + eyeHeight, from[2]}, target) <= 0.0) {
			return null;
		}

		WalkGraph graph = WalkGraph.of(grid, core, SeenMap.everything(core));
		int feetY = (int) Math.floor(from[1]);
		SplittableRandom random = new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * (draw + 1)));
		double[] best = null;
		int bestHidden = 0;
		for (int i = 0; i < WAYPOINTS; i++) {
			double angle = random.nextDouble() * 2.0 * Math.PI;
			double distance = random.nextDouble(MIN_WAYPOINT_DISTANCE, MAX_WAYPOINT_DISTANCE);
			double[] to = {from[0] + Math.cos(angle) * distance, from[1], from[2] + Math.sin(angle) * distance};
			if (!graph.walkable((int) Math.floor(to[0]), feetY, (int) Math.floor(to[2])) || !graph.straight(from, to, feetY, BODY_RADIUS)) {
				continue;
			}

			int hidden = hiddenTicks(grid, from, to, target, eyeHeight);
			if (hidden > bestHidden) {
				bestHidden = hidden;
				best = to;
			}
		}

		if (best == null || bestHidden < MIN_HIDDEN_TICKS) {
			return null;
		}

		return new Start(ClipType.OCCLUSION, pose, draw, new double[] {best[0] + origin[0], best[1] + origin[1], best[2] + origin[2]}, 0.0, bestHidden);
	}

	/** Ticks of a straight walk from {@code from} to {@code to} during which the honey is fully hidden. */
	static int hiddenTicks(SightGrid grid, double[] from, double[] to, int[] target, double eyeHeight) {
		double length = Math.hypot(to[0] - from[0], to[2] - from[2]);
		int ticks = (int) Math.floor(length / WALK);
		int hidden = 0;
		for (int tick = 1; tick <= ticks; tick++) {
			double t = tick * WALK / length;
			double[] eye = {from[0] + (to[0] - from[0]) * t, from[1] + eyeHeight, from[2] + (to[2] - from[2]) * t};
			if (SightLines.clearFraction(grid, eye, target) <= 0.0) {
				hidden++;
			}
		}

		return hidden;
	}

	/** A path's length in blocks along its steps. */
	private static double length(List<int[]> path) {
		double total = 0.0;
		for (int i = 1; i < path.size(); i++) {
			total += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[2] - path.get(i - 1)[2]);
		}

		return total;
	}
}
