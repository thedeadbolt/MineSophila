package io.github.thedeadbolt.honeyfactory.environment;

/**
 * Accepted action ranges: a turn or look of at most the given degrees either way, and a
 * move or wait of 1 to the given ticks. Requests outside them are rejected before they
 * start.
 */
public record ActionLimits(float maxTurnDegrees, float maxLookDegrees, int maxMoveTicks, int maxWaitTicks) {
	/** A half turn, a full sweep from straight up to straight down, and 10 seconds of movement or waiting. */
	public static final ActionLimits DEFAULT = new ActionLimits(180.0F, 180.0F, 200, 200);

	public ActionLimits {
		if (!(maxTurnDegrees > 0.0F && maxTurnDegrees <= 360.0F) || !(maxLookDegrees > 0.0F && maxLookDegrees <= 180.0F) || maxMoveTicks < 1 || maxWaitTicks < 1) {
			throw new IllegalArgumentException("Need turn limits in (0, 360], look limits in (0, 180] and tick limits of at least 1");
		}
	}

	/**
	 * Checks {@code action} against these limits.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#UNSUPPORTED} for mining, or
	 *     {@link EnvironmentException.Reason#INVALID_REQUEST} for a value outside its range
	 */
	public void check(Action action) {
		switch (action) {
			case Action.Turn turn -> requireDegrees(action, turn.degrees(), this.maxTurnDegrees);
			case Action.Look look -> requireDegrees(action, look.degrees(), this.maxLookDegrees);
			case Action.MoveForward move -> requireTicks(action, move.ticks(), this.maxMoveTicks);
			case Action.Wait wait -> requireTicks(action, wait.ticks(), this.maxWaitTicks);
			case Action.Stop stop -> {
			}
			case Action.Mine mine -> throw new EnvironmentException(EnvironmentException.Reason.UNSUPPORTED, "MINE is not supported; mining is out of scope");
		}
	}

	private static void requireDegrees(Action action, float degrees, float limit) {
		if (!Float.isFinite(degrees) || Math.abs(degrees) > limit) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, action.type() + " needs degrees within [-" + limit + ", " + limit + "], not " + degrees);
		}
	}

	private static void requireTicks(Action action, int ticks, int limit) {
		if (ticks < 1 || ticks > limit) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, action.type() + " needs 1 to " + limit + " ticks, not " + ticks);
		}
	}
}
