package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.JsonEnum;
import org.jspecify.annotations.Nullable;

/**
 * Which viewpoints a recipe keeps, judged by the {@link SightLines} estimate from the eye
 * to the aim cell before any capture. The saved mask, not this estimate, decides whether
 * a capture is accepted.
 */
public enum SightRule implements JsonEnum {
	/** Every placeable viewpoint. */
	ANY,
	/** Every sight line is clear: the whole target shows. */
	ALL_CLEAR,
	/** Some sight lines are clear and some blocked. */
	PARTLY_HIDDEN,
	/** At least one sight line is clear. */
	SOME_CLEAR,
	/** At least one is clear and at least one passes a block cell: behind a shape, beyond a gap or behind vegetation. */
	CLEAR_AND_OBSTRUCTED;

	public static SightRule fromJson(String name) {
		return JsonEnum.parse(SightRule.class, name);
	}

	/** Null when the view suits the rule, otherwise the rejection reason. */
	public @Nullable String check(SolidGrid grid, double[] eye, int[] aim) {
		if (this == ANY) {
			return null;
		}

		SightLines.Estimate estimate = SightLines.estimate(grid, eye, aim);
		return switch (this) {
			case ANY -> null;
			case ALL_CLEAR -> estimate.clear() == 1.0 ? null : "target_occluded";
			case PARTLY_HIDDEN -> estimate.clear() > 0.0 && estimate.clear() < 1.0 ? null : "target_not_partially_hidden";
			case SOME_CLEAR -> estimate.clear() > 0.0 ? null : "target_hidden";
			case CLEAR_AND_OBSTRUCTED -> estimate.clear() == 0.0 ? "target_hidden" : estimate.open() < 1.0 ? null : "target_unobstructed";
		};
	}
}
