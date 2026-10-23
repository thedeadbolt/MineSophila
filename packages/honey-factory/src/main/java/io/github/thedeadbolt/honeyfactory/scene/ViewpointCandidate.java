package io.github.thedeadbolt.honeyfactory.scene;

/** One viewpoint draw: a placeable plan, or the reason the draw cannot be used. */
public sealed interface ViewpointCandidate {
	record Placeable(ViewpointPlan plan) implements ViewpointCandidate {
	}

	record Rejected(String reason) implements ViewpointCandidate {
	}
}
