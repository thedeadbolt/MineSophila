package io.github.thedeadbolt.honeyfactory.contract;

/**
 * Who chose a tick's executed command: the teacher, the teacher with drift added, or a
 * clip's script (scan, pass-by, an occlusion clip's walk to its waypoint). The teacher's own
 * command is recorded on every tick either way; it is the policy's target.
 */
public enum Driver implements JsonEnum {
	TEACHER,
	TEACHER_DRIFT,
	SCRIPT;

	public static Driver fromJson(String name) {
		return JsonEnum.parse(Driver.class, name);
	}
}
