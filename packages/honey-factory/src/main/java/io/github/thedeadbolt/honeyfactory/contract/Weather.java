package io.github.thedeadbolt.honeyfactory.contract;

/** Scene weather. */
public enum Weather implements JsonEnum {
	CLEAR,
	RAIN,
	THUNDER;

	public static Weather fromJson(String name) {
		return JsonEnum.parse(Weather.class, name);
	}
}
