package io.github.thedeadbolt.honeyfactory.contract;

import org.jspecify.annotations.Nullable;

/** Target visibility a level requires of target-present viewpoints. */
public enum VisibilityRule implements JsonEnum {
	FULLY_VISIBLE,
	PARTIALLY_VISIBLE,
	AT_LEAST_PARTLY_VISIBLE;

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
		return JsonEnum.parse(VisibilityRule.class, name);
	}
}
