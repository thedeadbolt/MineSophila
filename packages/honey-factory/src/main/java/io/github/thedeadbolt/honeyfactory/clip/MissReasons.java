package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.TrackBehaviour;
import io.github.thedeadbolt.honeyfactory.teacher.WalkGraph;
import java.util.List;

/**
 * Why a clip that sought the honey ended without reaching it (M12-2), judged by the scene's
 * true layout, which the generator knows and the teacher does not: no walkable way from the
 * start is {@code unreachable}; a way that got at least {@value #PROGRESS} cells shorter by
 * the end is a {@code timeout}; anything else is a {@code teacher_failure}, which must stay at 0.
 */
public final class MissReasons {
	/** Cells of true path the body must have gained for a miss to count as running out of time. */
	static final int PROGRESS = 2;

	private final WalkGraph truth;
	private final int[] origin;

	public MissReasons(SightGrid grid, BlockBox core) {
		this.truth = WalkGraph.of(grid, core, SeenMap.everything(core));
		this.origin = core.min();
	}

	/**
	 * @param honey the honey's absolute cell
	 */
	public ClipRecord.MissReason of(CameraPose start, CameraPose end, int[] honey) {
		int[] target = {honey[0] - this.origin[0], honey[1] - this.origin[1], honey[2] - this.origin[2]};
		List<int[]> before = TrackBehaviour.pathBeside(this.truth, cell(start), target);
		if (before == null) {
			return ClipRecord.MissReason.UNREACHABLE;
		}

		List<int[]> after = TrackBehaviour.pathBeside(this.truth, cell(end), target);
		return after != null && after.size() <= before.size() - PROGRESS ? ClipRecord.MissReason.TIMEOUT : ClipRecord.MissReason.TEACHER_FAILURE;
	}

	private int[] cell(CameraPose pose) {
		return new int[] {(int) Math.floor(pose.x()) - this.origin[0], (int) Math.floor(pose.y()) - this.origin[1], (int) Math.floor(pose.z()) - this.origin[2]};
	}
}
