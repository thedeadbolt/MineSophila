package io.github.thedeadbolt.honeyfactory.environment;

import org.jspecify.annotations.Nullable;

/**
 * The environment's operation state machine. It accepts one state-changing operation at
 * a time, never queues, and walks each operation through its kind's states.
 *
 * <p>Not thread-safe: the owning runtime calls it from a single thread.
 */
public final class OperationCoordinator {
	private OperationState state = OperationState.IDLE;
	private @Nullable Operation current;
	private @Nullable String lastError;
	private long nextId = 1;

	public record Status(OperationState state, @Nullable Operation current, @Nullable String lastError, long operationsStarted) {
	}

	/**
	 * Accepts a new operation and enters its first state.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#BUSY} while another operation runs,
	 *     or {@link EnvironmentException.Reason#INVALID_STATE} when the kind cannot start now
	 */
	public Operation begin(OperationKind kind, long tick) {
		if (this.current != null) {
			throw new EnvironmentException(EnvironmentException.Reason.BUSY, "operation " + this.current.id() + " (" + this.current.kind() + ") is still running");
		}

		if (!kind.canStartFrom(this.state)) {
			String hint = this.state == OperationState.FAILED || this.state == OperationState.IDLE ? "; reset a scene first" : "";
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_STATE, kind + " cannot start while " + this.state + hint);
		}

		Operation operation = new Operation(String.format("op-%06d", this.nextId++), kind, tick);
		this.current = operation;
		this.state = kind.path().getFirst();
		return operation;
	}

	/** Moves the running operation to the next state on its kind's path. */
	public void advance(Operation operation, OperationState next) {
		requireCurrent(operation);
		int index = operation.kind().path().indexOf(this.state);
		if (index < 0 || index + 1 >= operation.kind().path().size() || operation.kind().path().get(index + 1) != next) {
			throw new IllegalStateException(operation.kind() + " cannot move from " + this.state + " to " + next);
		}

		this.state = next;
	}

	/** Finishes the running operation successfully; the coordinator becomes READY. */
	public void complete(Operation operation) {
		requireCurrent(operation);
		if (operation.kind().path().getLast() != this.state) {
			throw new IllegalStateException(operation.kind() + " cannot complete from " + this.state);
		}

		this.state = OperationState.READY;
		this.current = null;
		this.lastError = null;
	}

	/** Fails the running operation; only a reset can leave FAILED. */
	public void fail(Operation operation, String reason) {
		requireCurrent(operation);
		this.state = OperationState.FAILED;
		this.current = null;
		this.lastError = operation.id() + " " + operation.kind() + ": " + reason;
	}

	public Status status() {
		return new Status(this.state, this.current, this.lastError, this.nextId - 1);
	}

	public OperationState state() {
		return this.state;
	}

	private void requireCurrent(Operation operation) {
		if (this.current == null || !this.current.equals(operation)) {
			throw new IllegalStateException(operation.id() + " is not the running operation");
		}
	}
}
