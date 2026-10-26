package io.github.thedeadbolt.honeyfactory.contract;

/** What the teacher is doing in a tick: tracking visible honey, re-acquiring it where last seen, or searching. */
public enum TeacherMode implements JsonEnum {
	TRACK,
	REACQUIRE,
	SEARCH;

	public static TeacherMode fromJson(String name) {
		return JsonEnum.parse(TeacherMode.class, name);
	}
}
