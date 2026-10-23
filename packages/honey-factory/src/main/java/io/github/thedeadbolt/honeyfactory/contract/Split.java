package io.github.thedeadbolt.honeyfactory.contract;

/** Dataset split, assigned per scene and inherited by every sample of that scene. */
public enum Split {
	TRAIN("train"),
	VALIDATION("validation"),
	TEST("test");

	private final String jsonName;

	Split(String jsonName) {
		this.jsonName = jsonName;
	}

	public String jsonName() {
		return this.jsonName;
	}

	public static Split fromJson(String name) {
		for (Split split : values()) {
			if (split.jsonName.equals(name)) {
				return split;
			}
		}

		throw new IllegalArgumentException("unknown split '" + name + "'; expected train, validation or test");
	}
}
