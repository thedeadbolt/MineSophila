package io.github.thedeadbolt.honeyfactory.clip;

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

	/**
	 * The driver of a clip of {@code type}.
	 *
	 * @param honey the honey's absolute cell, or null in a scene without honey
	 * @throws IllegalArgumentException for a type with no driver yet
	 */
	static ClipDriver of(ClipType type, long seed, CameraPose start, int @Nullable [] honey, MotorLimits limits) {
		return switch (type) {
			case SCAN -> new ScanDriver(seed, limits);
			case PASS_BY -> new PassByDriver(seed, start, honey, limits);
			default -> throw new IllegalArgumentException("clip type " + type.jsonName() + " has no driver yet");
		};
	}

	/** Degrees in [-180, 180). */
	static double wrap(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}
}
