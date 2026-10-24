package io.github.thedeadbolt.honeyfactory.contract;

/**
 * Key that keeps related scenes in one split: the scene itself, the copied region of a
 * source patch, or a whole source world (reserved for datasets drawn from several saves).
 */
public enum SplitGrouping implements JsonEnum {
	SCENE,
	SOURCE_REGION,
	SOURCE_WORLD;

	public static SplitGrouping fromJson(String name) {
		return JsonEnum.parse(SplitGrouping.class, name);
	}
}
