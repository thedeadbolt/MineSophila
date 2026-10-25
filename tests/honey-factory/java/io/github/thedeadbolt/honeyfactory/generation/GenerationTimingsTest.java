package io.github.thedeadbolt.honeyfactory.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationTimingsTest {
	@Test
	void summarizesEachPhaseWithNearestRankPercentiles() {
		GenerationTimings timings = new GenerationTimings();
		for (int ms = 1; ms <= 40; ms++) {
			timings.add(GenerationTimings.Phase.OBSERVE, ms * 1_000_000L);
		}

		timings.add(GenerationTimings.Phase.RESET, 7_000_000L);
		Map<GenerationTimings.Phase, GenerationTimings.Total> totals = timings.totals();
		GenerationTimings.Total observe = totals.get(GenerationTimings.Phase.OBSERVE);
		assertEquals(40, observe.count());
		assertEquals(0.820, observe.seconds(), 1e-9);
		assertEquals(0.0205, observe.meanSeconds(), 1e-9);
		assertEquals(0.020, observe.p50Seconds(), 1e-9);
		assertEquals(0.038, observe.p95Seconds(), 1e-9);
		assertEquals(0.040, observe.maxSeconds(), 1e-9);
		assertEquals(new GenerationTimings.Total(1, 0.007, 0.007, 0.007, 0.007), totals.get(GenerationTimings.Phase.RESET));
		assertEquals(new GenerationTimings.Total(0, 0.0, 0.0, 0.0, 0.0), GenerationTimings.Total.of(new long[0]));
	}
}
