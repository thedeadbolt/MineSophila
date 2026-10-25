package io.github.thedeadbolt.honeyfactory.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DatasetValidatorThreadsTest {
	private static final long PANORAMA = 2480L * 1280;

	@Test
	void threadsFollowProcessorsAndHeap() {
		assertEquals(DatasetValidator.MAX_THREADS, DatasetValidator.threads(PANORAMA, 12L << 30, 32), "a production heap allows the cap");
		assertEquals(7, DatasetValidator.threads(PANORAMA, 12L << 30, 8), "one processor is left free");
		assertEquals(4, DatasetValidator.threads(PANORAMA, 512L << 20, 32), "a small heap holds fewer images at once");
		assertEquals(1, DatasetValidator.threads(PANORAMA, 64L << 20, 32), "never fewer than one");
		assertEquals(1, DatasetValidator.threads(64 * 64, 12L << 30, 1));
	}
}
