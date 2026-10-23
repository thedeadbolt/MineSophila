package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlockBoxTest {
	@Test
	void boundsAreInclusive() {
		BlockBox box = new BlockBox(-4, 64, -4, 35, 83, 35);
		assertTrue(box.contains(-4, 64, -4));
		assertTrue(box.contains(35, 83, 35));
		assertFalse(box.contains(36, 70, 0));
		assertEquals(40L * 20 * 40, box.volume());
		assertArrayEquals(new int[] {-1, -1, 2, 2}, box.chunkRange());
		assertTrue(box.contains(new BlockBox(0, 64, 0, 31, 79, 31)));
		assertFalse(box.contains(new BlockBox(0, 63, 0, 31, 79, 31)));
		assertFalse(box.intersects(new BlockBox(36, 64, 0, 40, 70, 4)));
		assertEquals(new BlockBox(-3, 64, -4, 36, 83, 35), box.offset(1, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> new BlockBox(1, 0, 0, 0, 0, 0));
	}
}
