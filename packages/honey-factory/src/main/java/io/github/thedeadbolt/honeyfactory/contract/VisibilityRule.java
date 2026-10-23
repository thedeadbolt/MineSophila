package io.github.thedeadbolt.honeyfactory.contract;

import java.util.Arrays;

/** Target visibility a level requires of target-present viewpoints. */
public enum VisibilityRule {
	FULLY_VISIBLE("fully_visible"),
	PARTIALLY_VISIBLE("partially_visible"),
	AT_LEAST_PARTLY_VISIBLE("at_least_partly_visible");

	private final String jsonName;

	VisibilityRule(String jsonName) {
		this.jsonName = jsonName;
	}

	public String jsonName() {
		return this.jsonName;
	}

	public static VisibilityRule fromJson(String name) {
		for (VisibilityRule value : values()) {
			if (value.jsonName.equals(name)) {
				return value;
			}
		}

		throw new IllegalArgumentException("unknown value '" + name + "'; expected one of " + Arrays.stream(values()).map(VisibilityRule::jsonName).toList());
	}
}
