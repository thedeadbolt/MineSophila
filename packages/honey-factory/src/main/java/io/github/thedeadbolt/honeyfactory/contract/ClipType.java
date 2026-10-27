package io.github.thedeadbolt.honeyfactory.contract;

/** How a motion clip is driven (M12-2); the teacher's command is logged in every type. */
public enum ClipType implements JsonEnum {
	APPROACH,
	PASS_BY,
	SCAN,
	OCCLUSION,
	DETOUR,
	SEARCH,
	NO_HONEY;

	public static ClipType fromJson(String name) {
		return JsonEnum.parse(ClipType.class, name);
	}
}
