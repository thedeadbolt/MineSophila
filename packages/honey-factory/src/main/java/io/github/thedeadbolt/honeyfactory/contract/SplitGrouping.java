package io.github.thedeadbolt.honeyfactory.contract;

/** Key that keeps related scenes in one split: the scene itself, or a level-5 source world. */
public enum SplitGrouping implements JsonEnum {
	SCENE,
	SOURCE_WORLD;

	public static SplitGrouping fromJson(String name) {
		return JsonEnum.parse(SplitGrouping.class, name);
	}
}
