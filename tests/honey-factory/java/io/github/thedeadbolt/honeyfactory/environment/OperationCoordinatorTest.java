package io.github.thedeadbolt.honeyfactory.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OperationCoordinatorTest {
	private static void run(OperationCoordinator coordinator, OperationKind kind) {
		Operation operation = coordinator.begin(kind, 0);
		for (OperationState next : kind.path().subList(1, kind.path().size())) {
			coordinator.advance(operation, next);
		}

		coordinator.complete(operation);
	}

	private static EnvironmentException.Reason rejection(OperationCoordinator coordinator, OperationKind kind) {
		return assertThrows(EnvironmentException.class, () -> coordinator.begin(kind, 0)).reason();
	}

	@Test
	void operationsWalkTheirPathsToReady() {
		OperationCoordinator coordinator = new OperationCoordinator();
		for (OperationKind kind : OperationKind.values()) {
			if (kind != OperationKind.RESET) {
				assertEquals(EnvironmentException.Reason.INVALID_STATE, rejection(coordinator, kind), kind + " before a reset");
			}
		}

		run(coordinator, OperationKind.RESET);
		run(coordinator, OperationKind.SET_VIEWPOINT);
		run(coordinator, OperationKind.OBSERVE);
		run(coordinator, OperationKind.STEP);
		run(coordinator, OperationKind.RESET);
		assertEquals(OperationState.READY, coordinator.state());
		assertEquals(5, coordinator.status().operationsStarted());
	}

	@Test
	void oneOperationAtATime() {
		OperationCoordinator coordinator = new OperationCoordinator();
		Operation reset = coordinator.begin(OperationKind.RESET, 0);
		assertEquals(EnvironmentException.Reason.BUSY, rejection(coordinator, OperationKind.RESET));
		assertEquals(EnvironmentException.Reason.BUSY, rejection(coordinator, OperationKind.OBSERVE));
		assertEquals(reset, coordinator.status().current());
	}

	@Test
	void failureRequiresAReset() {
		OperationCoordinator coordinator = new OperationCoordinator();
		run(coordinator, OperationKind.RESET);
		Operation view = coordinator.begin(OperationKind.SET_VIEWPOINT, 3);
		coordinator.fail(view, "timed out");
		OperationCoordinator.Status status = coordinator.status();
		assertEquals(OperationState.FAILED, status.state());
		assertNull(status.current());
		assertTrue(status.lastError().contains("SET_VIEWPOINT: timed out"));
		assertEquals(EnvironmentException.Reason.INVALID_STATE, rejection(coordinator, OperationKind.OBSERVE));
		run(coordinator, OperationKind.RESET);
		assertNull(coordinator.status().lastError());
	}

	@Test
	void stepsMustFollowTheKindsPath() {
		OperationCoordinator coordinator = new OperationCoordinator();
		run(coordinator, OperationKind.RESET);
		Operation view = coordinator.begin(OperationKind.SET_VIEWPOINT, 0);
		assertThrows(IllegalStateException.class, () -> coordinator.advance(view, OperationState.CAPTURING));
		assertThrows(IllegalStateException.class, () -> coordinator.complete(view));
		Operation stale = new Operation("op-999999", OperationKind.SET_VIEWPOINT, 0);
		assertThrows(IllegalStateException.class, () -> coordinator.advance(stale, OperationState.SETTLING));
	}

	@Test
	void kindPathsFollowTheSpecifiedStateDiagram() {
		for (OperationKind kind : OperationKind.values()) {
			for (OperationState start : OperationState.values()) {
				if (kind.canStartFrom(start)) {
					assertTrue(start.successors().contains(kind.path().getFirst()), kind + " from " + start);
				}
			}

			for (int i = 0; i + 1 < kind.path().size(); i++) {
				assertTrue(kind.path().get(i).successors().contains(kind.path().get(i + 1)), kind + " step " + i);
			}

			assertTrue(kind.path().getLast().successors().contains(OperationState.READY), kind + " end");
		}
	}

	@Test
	void readinessNeedsConditionsAndSettlingAndTimesOut() {
		ReadinessGate gate = new ReadinessGate(5, 20, 100);
		assertEquals(ReadinessGate.Result.WAITING, gate.poll(101, true));
		assertEquals(ReadinessGate.Result.READY, gate.poll(105, true));
		assertEquals(ReadinessGate.Result.WAITING, gate.poll(119, false));
		assertEquals(ReadinessGate.Result.TIMED_OUT, gate.poll(120, false));
		assertThrows(IllegalArgumentException.class, () -> new ReadinessGate(10, 5, 0));
	}
}
