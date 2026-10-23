package io.github.thedeadbolt.honeyfactory.contract;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

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

	/**
	 * Whether a target-present observation satisfies this rule. Ratio bounds are inclusive
	 * and ignored when null; they never apply to {@link #FULLY_VISIBLE}.
	 */
	public boolean accepts(TargetStatus status, @Nullable Double ratio, @Nullable Double minRatio, @Nullable Double maxRatio) {
		boolean statusAllowed = switch (this) {
			case FULLY_VISIBLE -> status == TargetStatus.FULLY_VISIBLE;
			case PARTIALLY_VISIBLE -> status == TargetStatus.PARTIALLY_VISIBLE;
			case AT_LEAST_PARTLY_VISIBLE -> status == TargetStatus.FULLY_VISIBLE || status == TargetStatus.PARTIALLY_VISIBLE;
		};
		if (!statusAllowed || this == FULLY_VISIBLE) {
			return statusAllowed;
		}

		return (minRatio == null || ratio >= minRatio) && (maxRatio == null || ratio <= maxRatio);
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
