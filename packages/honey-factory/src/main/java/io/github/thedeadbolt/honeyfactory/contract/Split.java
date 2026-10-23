package io.github.thedeadbolt.honeyfactory.contract;

/** Dataset split, assigned per scene and inherited by every sample of that scene. */
public enum Split implements JsonEnum {
	TRAIN,
	VALIDATION,
	TEST;

	public static Split fromJson(String name) {
		return JsonEnum.parse(Split.class, name);
	}
}
