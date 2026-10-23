package io.github.thedeadbolt.honeyfactory.dataset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SplitGrouping;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneSlotsTest {
	private static ResolvedConfig config(long seed, int scenes) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "slots", "seed": %d, "difficulty": 2}, "quotas": {"accepted_scenes": %d}, "output": {"root": "/srv"}}
			""".formatted(seed, scenes));
	}

	@Test
	void quotasAndSplitsAreExactPerClass() {
		List<SceneSlots.Slot> slots = SceneSlots.plan(config(11, 1000));
		assertEquals(800, slots.stream().filter(SceneSlots.Slot::targetPresent).count());
		for (boolean present : new boolean[] {true, false}) {
			int[] counts = new int[3];
			slots.stream().filter(slot -> slot.targetPresent() == present).forEach(slot -> counts[slot.split().ordinal()]++);
			assertArrayEquals(present ? new int[] {640, 80, 80} : new int[] {160, 20, 20}, counts);
		}
	}

	@Test
	void planIsDeterministicAndSeedDependent() {
		assertEquals(SceneSlots.plan(config(3, 50)), SceneSlots.plan(config(3, 50)));
		assertNotEquals(SceneSlots.plan(config(3, 50)), SceneSlots.plan(config(4, 50)));
	}

	@Test
	void largestRemainderBreaksTiesInSplitOrder() {
		double[] halves = {0.5, 0.25, 0.25};
		assertArrayEquals(new int[] {1, 0, 0}, SceneSlots.splitCounts(1, halves));
		assertArrayEquals(new int[] {1, 1, 1}, SceneSlots.splitCounts(3, halves));
		assertArrayEquals(new int[] {3, 1, 1}, SceneSlots.splitCounts(5, halves));
		assertArrayEquals(new int[] {0, 0, 0}, SceneSlots.splitCounts(0, halves));
	}

	@Test
	void rejectedAttemptsChangeOnlyTheirOwnSeeds() {
		ResolvedConfig config = config(5, 10);
		SceneSlots.Slot slot = SceneSlots.plan(config).get(3);
		SceneDescriptor first = SceneDescriptor.of(config, slot, 0);
		SceneDescriptor retry = SceneDescriptor.of(config, slot, 1);
		assertNotEquals(first.seeds(), retry.seeds());
		assertEquals(SceneDescriptor.of(config, SceneSlots.plan(config).get(4), 0), SceneDescriptor.of(config, SceneSlots.plan(config).get(4), 0));
		assertEquals(slot.split(), retry.split());
		assertEquals(Split.TRAIN, SceneSlots.plan(config(5, 1)).getFirst().split());
	}
}
