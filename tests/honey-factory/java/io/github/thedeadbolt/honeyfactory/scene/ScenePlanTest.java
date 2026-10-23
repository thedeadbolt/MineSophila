package io.github.thedeadbolt.honeyfactory.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenePlanTest {
	private static final ArenaLayout DEFAULT_ARENA = ArenaLayout.of(new ResolvedConfig.Arena(32, 32, 16, 4));

	@Test
	void marginSurroundsTheCoreAndRisesAboveItButNotBelowTheFloor() {
		assertEquals(new BlockBox(0, 64, 0, 31, 79, 31), DEFAULT_ARENA.core());
		assertEquals(new BlockBox(-4, 64, -4, 35, 83, 35), DEFAULT_ARENA.resetVolume());
		assertEquals(false, DEFAULT_ARENA.resetVolume().intersects(ArenaLayout.STAGING_PLATFORM));
		ArenaLayout.of(new ResolvedConfig.Arena(256, 256, 192, 64));
		assertThrows(IllegalArgumentException.class, () -> ArenaLayout.of(new ResolvedConfig.Arena(256, 256, 256, 64)));
	}

	@Test
	void deltasOverrideTheTemplateInOrderAndAirRemovesBlocks() {
		Template template = Template.platform(2, 2, 2, "minecraft:stone");
		ScenePlan plan = new ScenePlan(template, List.of(
			new Placement(0, 0, 0, "minecraft:dirt"),
			new Placement(1, 0, 1, Placement.AIR),
			new Placement(0, 0, 0, "minecraft:gold_block")
		), null, 6000, Weather.CLEAR);
		ArenaLayout arena = ArenaLayout.of(new ResolvedConfig.Arena(2, 2, 2, 0));
		assertEquals(List.of("minecraft:gold_block", "minecraft:stone", "minecraft:stone"), plan.layoutBlocks(arena).stream().map(block -> block.state()).sorted().toList());
	}

	@Test
	void fixtureLayoutsAreDistinctAndStable() {
		String platform = FixturePlans.plan(FixturePlans.Fixture.PLATFORM).layoutSha256(DEFAULT_ARENA);
		String open = FixturePlans.plan(FixturePlans.Fixture.OPEN).layoutSha256(DEFAULT_ARENA);
		String full = FixturePlans.plan(FixturePlans.Fixture.FULL).layoutSha256(DEFAULT_ARENA);
		assertNotEquals(platform, open);
		assertNotEquals(open, full);
		assertEquals(open, FixturePlans.plan(FixturePlans.Fixture.OPEN).layoutSha256(DEFAULT_ARENA));
		assertArrayEquals(new int[] {16, 65, 16}, FixturePlans.plan(FixturePlans.Fixture.OPEN).targetPosition(DEFAULT_ARENA));
		assertEquals(32 * 32, FixturePlans.plan(FixturePlans.Fixture.PLATFORM).layoutBlocks(DEFAULT_ARENA).size());
		assertEquals(0, FixturePlans.plan(FixturePlans.Fixture.EMPTY).layoutBlocks(DEFAULT_ARENA).size());
	}

	/** Golden checksums, cross-checked against layout-v1 text built independently from the contract. */
	@Test
	void fixtureLayoutChecksumsArePinned() {
		Map<FixturePlans.Fixture, String> golden = Map.of(
			FixturePlans.Fixture.EMPTY, "53636ac9ab7fc8ebdeddc7ec50981eab130b27806c3bc871d886e72c6e870929",
			FixturePlans.Fixture.PLATFORM, "d10055c089e0b9847ea2228008c3c005e0648cddd9cfae4ac0bc05fb40b1e160",
			FixturePlans.Fixture.OPEN, "dc70b1adcb5165ed43ea93c7c9a74c9fdbabd227e38031010924d5d54a367231",
			FixturePlans.Fixture.PARTIAL, "b058a8d06874a1ec58023c82a964e00c6993988ac73d9c12f9f4eb4c492862d1",
			FixturePlans.Fixture.FULL, "e573f0f00cfade22648037a921e15e2efa1ee608c5780094da1a9e9d1dcbb92b"
		);
		golden.forEach((fixture, sha) -> assertEquals(sha, FixturePlans.plan(fixture).layoutSha256(FixturePlans.ARENA), fixture.name()));
		assertEquals("896c69ba301b727c23d8cc83a07be3ab594f768b300f2dc6f041d622e40c49b6", FixturePlans.plan(FixturePlans.Fixture.OPEN).template().sha256());
	}

	@Test
	void fixtureViewpointMatchesTheM0Geometry() {
		ViewpointPlan view = FixturePlans.VIEWPOINT;
		assertEquals(-171.31364, view.yawDegrees(), 1e-4);
		assertEquals(10.454933, view.pitchDegrees(), 1e-4);
		assertArrayEquals(new double[] {16 - 5.0 / 12.0, 65.0, 22.5}, view.feet(), 1e-12);
	}

	@Test
	void plansRejectPlacementsOutsideTheCore() {
		Template template = Template.platform(2, 2, 2, "minecraft:stone");
		assertThrows(IllegalArgumentException.class, () -> new ScenePlan(template, List.of(new Placement(2, 0, 0, "minecraft:stone")), null, 0, Weather.CLEAR));
		Placement target = new Placement(1, 1, 1, "minecraft:honey_block");
		assertThrows(IllegalArgumentException.class, () -> new ScenePlan(template, List.of(), target, 0, Weather.CLEAR));
	}
}
