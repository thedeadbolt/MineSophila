package io.github.thedeadbolt.honeyfactory.generation;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Wall time a dataset run spends in each phase, for finding where generation is slow. Phases
 * are measured from request to completion, so waiting on the game counts towards the
 * operation waited for. Not thread-safe; used on the generator's thread.
 */
public final class GenerationTimings {
	/** A measured phase. */
	public enum Phase {
		/** Preparing a source patch, from request to completion, often in the background. */
		PREPARE_PATCH,
		/** Waiting for a source patch before a scene can be built. */
		PATCH_WAIT,
		RESET,
		/** Capturing a batch of viewpoints, including moving to each. */
		OBSERVE,
		/** Encoding and writing one sample's images, on a worker thread. */
		WRITE,
		/** Waiting on the generator's thread for writes still in flight. */
		WRITE_WAIT,
		VALIDATE
	}

	/** How often a phase ran and its total seconds. */
	public record Total(int count, double seconds) {
		public double meanSeconds() {
			return this.count == 0 ? 0.0 : this.seconds / this.count;
		}
	}

	private final Map<Phase, long[]> totals = new EnumMap<>(Phase.class);

	synchronized void add(Phase phase, long nanos) {
		long[] total = this.totals.computeIfAbsent(phase, key -> new long[2]);
		total[0]++;
		total[1] += nanos;
	}

	public synchronized Map<Phase, Total> totals() {
		Map<Phase, Total> result = new EnumMap<>(Phase.class);
		this.totals.forEach((phase, total) -> result.put(phase, new Total((int) total[0], total[1] / 1e9)));
		return result;
	}

	@Override
	public synchronized String toString() {
		return totals().entrySet().stream()
			.map(entry -> entry.getKey().name().toLowerCase(java.util.Locale.ROOT) + " " + entry.getValue().count() + "x "
				+ String.format(java.util.Locale.ROOT, "%.2fs (mean %.3fs)", entry.getValue().seconds(), entry.getValue().meanSeconds()))
			.collect(Collectors.joining(", "));
	}
}
