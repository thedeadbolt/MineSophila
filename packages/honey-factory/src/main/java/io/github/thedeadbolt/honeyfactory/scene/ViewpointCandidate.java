package io.github.thedeadbolt.honeyfactory.scene;

import org.jspecify.annotations.Nullable;

/** One viewpoint draw: a placeable plan, or the reason the draw cannot be used. */
public record ViewpointCandidate(@Nullable ViewpointPlan plan, @Nullable String rejection) {
	public static ViewpointCandidate of(ViewpointPlan plan) {
		return new ViewpointCandidate(plan, null);
	}

	public static ViewpointCandidate rejected(String reason) {
		return new ViewpointCandidate(null, reason);
	}
}
