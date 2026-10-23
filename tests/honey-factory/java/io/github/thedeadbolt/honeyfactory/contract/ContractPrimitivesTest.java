package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.ContractExamples;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractPrimitivesTest {
	@Test
	void targetStatusFollowsTheSpecifiedOrder() {
		assertEquals(TargetStatus.ABSENT, TargetStatus.derive(false, false, 0, 0));
		assertEquals(TargetStatus.OUT_OF_VIEW, TargetStatus.derive(true, false, 0, 0));
		assertEquals(TargetStatus.SUBPIXEL, TargetStatus.derive(true, true, 0, 0));
		assertEquals(TargetStatus.FULLY_OCCLUDED, TargetStatus.derive(true, true, 0, 5));
		assertEquals(TargetStatus.FULLY_VISIBLE, TargetStatus.derive(true, true, 5, 5));
		assertEquals(TargetStatus.PARTIALLY_VISIBLE, TargetStatus.derive(true, true, 4, 5));
		assertThrows(IllegalArgumentException.class, () -> TargetStatus.derive(true, true, 6, 5));
		assertThrows(IllegalArgumentException.class, () -> TargetStatus.derive(true, true, -1, 5));
		assertThrows(IllegalArgumentException.class, () -> TargetStatus.derive(false, true, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> TargetStatus.derive(true, false, 0, 3));
		assertNull(TargetStatus.visibilityRatio(0, 0));
		assertEquals(0.8, TargetStatus.visibilityRatio(4, 5));
	}

	@Test
	void maskStatisticsUseHalfOpenBoxesAndPixelCentres() {
		MaskStatistics stats = MaskStatistics.of(ContractExamples.square(2, 3, 4, 2), 64, 64);
		assertEquals(8, stats.positivePixels());
		assertArrayEquals(new int[] {2, 3, 4, 2}, stats.bboxXywh());
		assertArrayEquals(new double[] {4.0, 4.0}, stats.centroidXy());
		MaskStatistics empty = MaskStatistics.of(new byte[64 * 64], 64, 64);
		assertNull(empty.bboxXywh());
		assertNull(empty.centroidXy());
		byte[] grey = new byte[64 * 64];
		grey[10] = 7;
		assertThrows(IllegalArgumentException.class, () -> MaskStatistics.of(grey, 64, 64));
	}

	@Test
	void cameraBasisFollowsMinecraftConventions() {
		CameraBasis south = CameraBasis.of(0.0, 0.0);
		assertArrayEquals(new double[] {0.0, 0.0, 1.0}, south.forward(), 1e-12);
		assertArrayEquals(new double[] {-1.0, 0.0, 0.0}, south.right(), 1e-12);
		assertArrayEquals(new double[] {0.0, 1.0, 0.0}, south.up(), 1e-12);
		CameraBasis west = CameraBasis.of(90.0, 0.0);
		assertArrayEquals(new double[] {-1.0, 0.0, 0.0}, west.forward(), 1e-12);
		assertArrayEquals(new double[] {0.0, -1.0, 0.0}, CameraBasis.of(0.0, 90.0).forward(), 1e-12);
	}

	@Test
	void layoutSerializationIsCanonical() {
		int[] min = {0, 0, 0};
		int[] max = {2, 2, 2};
		List<BlockLayout.Block> blocks = List.of(
			new BlockLayout.Block(1, 1, 0, "minecraft:stone"),
			new BlockLayout.Block(0, 0, 1, BlockLayout.canonicalState("minecraft:oak_stairs", Map.of("half", "bottom", "facing", "north")))
		);
		assertEquals("""
			honey-factory/layout-v1
			bounds 0 0 0 2 2 2
			0 0 1 minecraft:oak_stairs[facing=north,half=bottom]
			1 1 0 minecraft:stone
			""", BlockLayout.serialize(min, max, blocks));
		assertEquals(BlockLayout.sha256(min, max, blocks), BlockLayout.sha256(min, max, blocks.reversed()));
		assertThrows(IllegalArgumentException.class, () -> BlockLayout.serialize(min, max, List.of(new BlockLayout.Block(3, 0, 0, "minecraft:stone"))));
		assertThrows(IllegalArgumentException.class, () -> BlockLayout.serialize(min, max, List.of(new BlockLayout.Block(0, 0, 0, "minecraft:air"))));
		assertThrows(IllegalArgumentException.class, () -> BlockLayout.serialize(min, max, List.of(new BlockLayout.Block(0, 0, 0, "minecraft:oak_stairs[half=bottom,facing=north]"))));
	}

	@Test
	void jsonLinesAreStrict() {
		JsonObject record = new JsonObject();
		record.addProperty("a", 1);
		byte[] bytes = Jsonl.write(List.of(record, record));
		assertEquals("{\"a\":1}\n{\"a\":1}\n", new String(bytes, StandardCharsets.UTF_8));
		assertEquals(2, Jsonl.read(bytes, "x.jsonl").size());
		assertThrows(ContractException.class, () -> Jsonl.read("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "x.jsonl"));
		assertThrows(ContractException.class, () -> Jsonl.read("﻿{\"a\":1}\n".getBytes(StandardCharsets.UTF_8), "x.jsonl"));
		assertThrows(ContractException.class, () -> Jsonl.read("{\"a\":1}\n\n".getBytes(StandardCharsets.UTF_8), "x.jsonl"));
		assertThrows(ContractException.class, () -> Jsonl.read("{\"a\":NaN}\n".getBytes(StandardCharsets.UTF_8), "x.jsonl"));
	}
}
