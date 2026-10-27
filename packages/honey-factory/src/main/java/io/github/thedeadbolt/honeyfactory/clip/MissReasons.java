package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.TrackBehaviour;
import io.github.thedeadbolt.honeyfactory.teacher.WalkGraph;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Why a clip that sought the honey ended without reaching it (M12-2), judged by the scene's
 * true layout, which the generator knows and the teacher does not: no walkable way from the
 * start is {@code unreachable}; a way that got at least {@value #PROGRESS} cells shorter from
 * where the teacher took over to the end is a {@code timeout}, as is a clip whose script never
 * handed over; anything else is a {@code teacher_failure}, which must stay at 0.
 */
public final class MissReasons {
	/** Cells of true path the body must have gained for a miss to count as running out of time. */
	static final int PROGRESS = 2;

	private final WalkGraph truth;
	private final int[] origin;
	private final double reach;

	/** @param reach how near the teacher must come to the honey, in blocks */
	public MissReasons(SightGrid grid, BlockBox core, double reach) {
		this.truth = WalkGraph.of(grid, core, SeenMap.everything(core));
		this.origin = core.min();
		this.reach = reach;
	}

	/** As {@link #of(CameraPose, CameraPose, CameraPose, int[])} for a clip the teacher drove from its start. */
	public ClipRecord.MissReason of(CameraPose start, CameraPose end, int[] honey) {
		return of(start, start, end, honey);
	}

	/**
	 * @param takeover where the teacher first drove, or null when a script drove every tick
	 * @param honey the honey's absolute cell
	 */
	public ClipRecord.MissReason of(CameraPose start, @Nullable CameraPose takeover, CameraPose end, int[] honey) {
		int[] target = {honey[0] - this.origin[0], honey[1] - this.origin[1], honey[2] - this.origin[2]};
		if (TrackBehaviour.pathBeside(this.truth, cell(start), target, this.reach) == null) {
			return ClipRecord.MissReason.UNREACHABLE;
		}

		if (takeover == null) {
			return ClipRecord.MissReason.TIMEOUT;
		}

		List<int[]> before = TrackBehaviour.pathBeside(this.truth, cell(takeover), target, this.reach);
		if (before == null) {
			return ClipRecord.MissReason.TEACHER_FAILURE;
		}

		List<int[]> after = TrackBehaviour.pathBeside(this.truth, cell(end), target, this.reach);
		return after != null && after.size() <= before.size() - PROGRESS ? ClipRecord.MissReason.TIMEOUT : ClipRecord.MissReason.TEACHER_FAILURE;
	}

	private int[] cell(CameraPose pose) {
		return new int[] {(int) Math.floor(pose.x()) - this.origin[0], (int) Math.floor(pose.y()) - this.origin[1], (int) Math.floor(pose.z()) - this.origin[2]};
	}
}
