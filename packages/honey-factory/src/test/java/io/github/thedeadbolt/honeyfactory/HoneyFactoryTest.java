package io.github.thedeadbolt.honeyfactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HoneyFactoryTest {
	@Test
	void idUsesModNamespace() {
		assertEquals(HoneyFactory.MOD_ID, HoneyFactory.id("honey").getNamespace());
	}
}
