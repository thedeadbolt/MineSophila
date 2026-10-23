package io.github.thedeadbolt.honeyfactory.environment;

/**
 * Tick-based readiness: an operation is ready once its conditions hold and at least
 * {@code minSettleTicks} have passed, and times out after {@code timeoutTicks}. Fixed
 * sleeps are never the only condition.
 */
public final class ReadinessGate {
	public enum Result {
		WAITING,
		READY,
		TIMED_OUT
	}

	private final int minSettleTicks;
	private final int timeoutTicks;
	private final long startTick;

	public ReadinessGate(int minSettleTicks, int timeoutTicks, long startTick) {
		if (minSettleTicks < 0 || timeoutTicks < minSettleTicks) {
			throw new IllegalArgumentException("Need 0 <= minSettleTicks <= timeoutTicks");
		}

		this.minSettleTicks = minSettleTicks;
		this.timeoutTicks = timeoutTicks;
		this.startTick = startTick;
	}

	public Result poll(long tick, boolean conditionsMet) {
		long waited = waited(tick);
		if (conditionsMet && waited >= this.minSettleTicks) {
			return Result.READY;
		}

		return waited >= this.timeoutTicks ? Result.TIMED_OUT : Result.WAITING;
	}

	public long waited(long tick) {
		return tick - this.startTick;
	}
}
