package io.github.thedeadbolt.honeyfactory.replay;

/** Why a recorded scene cannot be replayed exactly, with a stable machine-readable reason. */
public class ReplayException extends RuntimeException {
	public enum Reason {
		/** The dataset, scene or sample does not exist. */
		NOT_FOUND,
		/** The dataset's files cannot be read as the contract defines them. */
		INVALID_DATASET,
		/** This build lacks a template, generator or algorithm version the scene was made with. */
		UNAVAILABLE,
		/** Regeneration gave something other than what the dataset records. */
		MISMATCH
	}

	private final Reason reason;

	public ReplayException(Reason reason, String message) {
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
