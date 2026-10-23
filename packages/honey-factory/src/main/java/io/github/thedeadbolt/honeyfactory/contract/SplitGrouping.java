package io.github.thedeadbolt.honeyfactory.contract;

import java.util.Arrays;

/** Key that keeps related scenes in one split: the scene itself, or a level-5 source world. */
public enum SplitGrouping {
	SCENE("scene"),
	SOURCE_WORLD("source_world");

	private final String jsonName;

	SplitGrouping(String jsonName) {
		this.jsonName = jsonName;
	}

	public String jsonName() {
		return this.jsonName;
	}

	public static SplitGrouping fromJson(String name) {
		for (SplitGrouping value : values()) {
			if (value.jsonName.equals(name)) {
				return value;
			}
		}

		throw new IllegalArgumentException("unknown value '" + name + "'; expected one of " + Arrays.stream(values()).map(SplitGrouping::jsonName).toList());
	}
}
