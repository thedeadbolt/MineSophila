package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.contract.Driver;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;
import org.jspecify.annotations.Nullable;

/**
 * What a clip's body executes each tick. Scripted clip types follow their own rule; the
 * teacher's command is computed and recorded beside it either way. A driver may know the
 * scene, since it is not the teacher; its commands stay within the motor limits.
 */
public interface ClipDriver {
	MotorCommand next(int tick, TeacherInput input, Teacher.Decision teacher);

	/** Whether the last command drifted from what the driver meant to do. */
	default boolean drifting() {
		return false;
	}

	/** Who chose the last command. */
	Driver driver();

	/**
	 * The driver of a clip that starts at {@code start}: scan and pass-by follow their scripts;
	 * approach, detour, search and no-honey execute the teacher's command with {@code drift}; occlusion
	 * walks to its waypoint with drift, then follows the teacher.
	 *
	 * @param honey the honey's absolute cell, or null in a scene without honey
	 * @param walk how far a scripted walk goes in the clip, in blocks
	 */
	static ClipDriver of(ClipStarts.Start start, long seed, int @Nullable [] honey, MotorLimits limits, double eyeHeight, Drift drift, EdgeGuard guard, double walk) {
		return switch (start.type()) {
			case SCAN -> new ScanDriver(seed, limits);
			case PASS_BY -> new PassByDriver(seed, start.pose(), honey, limits, guard, walk);
			case APPROACH, DETOUR, SEARCH, NO_HONEY -> new TeacherDriver(drift);
			case OCCLUSION -> new OcclusionDriver(start.waypoint(), limits, eyeHeight, drift);
		};
	}

	/** What a clip of {@code type} shows and who drives it, in a few words, for people watching a run. */
	static String describe(ClipType type) {
		return switch (type) {
			case APPROACH -> "approach: the teacher goes to the honey";
			case DETOUR -> "detour: the teacher goes around to the honey";
			case SEARCH -> "search: the teacher looks for the honey, then goes to it";
			case OCCLUSION -> "occlusion: a script walks behind cover, then the teacher finds the honey";
			case PASS_BY -> "pass-by: a script walks past the honey";
			case SCAN -> "scan: a script turns in place";
			case NO_HONEY -> "no-honey: the teacher searches an arena without honey";
		};
	}

	/** Degrees in [-180, 180). */
	static double wrap(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}
}
