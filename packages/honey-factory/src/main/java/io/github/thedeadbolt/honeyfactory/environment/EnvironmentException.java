package io.github.thedeadbolt.honeyfactory.environment;

/** A rejected or failed environment operation, with a stable machine-readable reason. */
public class EnvironmentException extends RuntimeException {
	public enum Reason {
		/** The world is not a verified dataset-owned world, or the write leaves owned bounds. */
		NOT_OWNED,
		/** Another operation is still running; requests are never queued. */
		BUSY,
		/** The operation is not valid in the coordinator's current state. */
		INVALID_STATE,
		/** The request itself is malformed or out of range. */
		INVALID_REQUEST,
		/** The operation or action is not implemented yet. */
		UNSUPPORTED,
		/** Readiness conditions were not met before the timeout. */
		TIMEOUT,
		/** The world does not match what the operation produced or expected. */
		WORLD_MISMATCH,
		/** Rendering, readback or capture output failed. */
		CAPTURE_FAILED,
		/** The operation needs a local client and integrated server. */
		NO_LOCAL_RUNTIME
	}

	private final Reason reason;

	public EnvironmentException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

	@Override
	public String toString() {
		return this.reason + ": " + getMessage();
	}
}
