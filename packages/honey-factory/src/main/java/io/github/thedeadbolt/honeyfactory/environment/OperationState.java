package io.github.thedeadbolt.honeyfactory.environment;

import java.util.EnumSet;
import java.util.Set;

/**
 * Coordinator states from the specification:
 *
 * <pre>
 * IDLE -> PREPARING_SCENE -> READY -> ACTING -> SETTLING -> CAPTURING -> READY
 *                               \________________________________________-> FAILED
 * </pre>
 *
 * <p>Any active state may fail. A reset starts from IDLE, READY or FAILED.
 */
public enum OperationState {
	IDLE,
	PREPARING_SCENE,
	READY,
	ACTING,
	SETTLING,
	CAPTURING,
	FAILED;

	/** States reachable in one transition, excluding the implicit move to FAILED. */
	public Set<OperationState> successors() {
		return switch (this) {
			case IDLE, FAILED -> EnumSet.of(PREPARING_SCENE);
			case PREPARING_SCENE -> EnumSet.of(READY);
			case READY -> EnumSet.of(PREPARING_SCENE, ACTING, CAPTURING);
			case ACTING -> EnumSet.of(SETTLING);
			case SETTLING -> EnumSet.of(CAPTURING, READY);
			case CAPTURING -> EnumSet.of(READY);
		};
	}

	/** True while an operation owns the coordinator. */
	public boolean isActive() {
		return this != IDLE && this != READY && this != FAILED;
	}
}
