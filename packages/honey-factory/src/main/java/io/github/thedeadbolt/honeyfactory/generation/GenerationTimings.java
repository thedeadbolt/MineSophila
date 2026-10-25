package io.github.thedeadbolt.honeyfactory.generation;

import java.util.Arrays;
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

	/** How often a phase ran, its total seconds and the median, 95th percentile and longest single run. */
	public record Total(int count, double seconds, double p50Seconds, double p95Seconds, double maxSeconds) {
		public double meanSeconds() {
			return this.count == 0 ? 0.0 : this.seconds / this.count;
		}

		/** The summary of {@code nanos}, one duration per run. */
		public static Total of(long[] nanos) {
			long[] sorted = nanos.clone();
			Arrays.sort(sorted);
			long sum = Arrays.stream(sorted).sum();
			return new Total(sorted.length, sum / 1e9, percentile(sorted, 0.50), percentile(sorted, 0.95), sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1e9);
		}

		/** Nearest-rank percentile of sorted durations, in seconds. */
		private static double percentile(long[] sorted, double fraction) {
			if (sorted.length == 0) {
				return 0.0;
			}

			int rank = (int) Math.ceil(fraction * sorted.length);
			return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)] / 1e9;
		}
	}

	private final Map<Phase, long[]> durations = new EnumMap<>(Phase.class);
	private final Map<Phase, Integer> counts = new EnumMap<>(Phase.class);

	synchronized void add(Phase phase, long nanos) {
		long[] values = this.durations.computeIfAbsent(phase, key -> new long[16]);
		int count = this.counts.getOrDefault(phase, 0);
		if (count == values.length) {
			values = Arrays.copyOf(values, count * 2);
			this.durations.put(phase, values);
		}

		values[count] = nanos;
		this.counts.put(phase, count + 1);
	}

	public synchronized Map<Phase, Total> totals() {
		Map<Phase, Total> result = new EnumMap<>(Phase.class);
		this.durations.forEach((phase, values) -> result.put(phase, Total.of(Arrays.copyOf(values, this.counts.get(phase)))));
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
