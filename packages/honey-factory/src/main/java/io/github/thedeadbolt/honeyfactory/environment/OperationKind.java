package io.github.thedeadbolt.honeyfactory.environment;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Environment operations, where each may start and the states it passes through to READY. */
public enum OperationKind {
	RESET(EnumSet.of(OperationState.IDLE, OperationState.READY, OperationState.FAILED), List.of(OperationState.PREPARING_SCENE)),
	SET_VIEWPOINT(EnumSet.of(OperationState.READY), List.of(OperationState.ACTING, OperationState.SETTLING)),
	OBSERVE(EnumSet.of(OperationState.READY), List.of(OperationState.CAPTURING)),
	STEP(EnumSet.of(OperationState.READY), List.of(OperationState.ACTING, OperationState.SETTLING, OperationState.CAPTURING));

	private final Set<OperationState> startStates;
	private final List<OperationState> path;

	OperationKind(Set<OperationState> startStates, List<OperationState> path) {
		this.startStates = startStates;
		this.path = path;
	}

	public boolean canStartFrom(OperationState state) {
		return this.startStates.contains(state);
	}

	/** Active states in order; READY follows the last one. */
	public List<OperationState> path() {
		return this.path;
	}
}
