package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.Baseline;
import io.github.thedeadbolt.honeyfactory.world.WorldGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * Roadmap M2 exit gate: ownership refusal, invalid transitions, reproducible fixture
 * rebuilds, reset bounds proven by sentinels, observe through the capture service and
 * restoration of the world and player baseline.
 */
public class M2EnvironmentTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m2";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		List<String> failures = new ArrayList<>();
		refusesOrdinarySave(context, failures);
		datasetWorld(context, failures);
		if (!failures.isEmpty()) {
			throw new AssertionError("M2 failures:\n" + String.join("\n", failures));
		}
	}

	private static void refusesOrdinarySave(ClientGameTestContext context, List<String> failures) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			expect(failures, "claim in an ordinary save", EnvironmentException.Reason.NOT_OWNED, world.getServer().computeOnServer(server -> claimReason(server, server.getWorldData().getLevelName())));
			expect(failures, "reset in an ordinary save", EnvironmentException.Reason.NOT_OWNED, rejection(context, runtime -> resetFixture(runtime, FixturePlans.Fixture.PLATFORM)));
		}
	}

	private static void datasetWorld(ClientGameTestContext context, List<String> failures) {
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
			Map<String, String> rulesBefore = world.getServer().computeOnServer(M2EnvironmentTest::controlledRules);
			GameType modeBefore = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().gameMode());

			expect(failures, "reset before claiming", EnvironmentException.Reason.NOT_OWNED, rejection(context, runtime -> resetFixture(runtime, FixturePlans.Fixture.PLATFORM)));
			expect(failures, "claim with the wrong name", EnvironmentException.Reason.NOT_OWNED, world.getServer().computeOnServer(server -> claimReason(server, "my survival world")));
			expect(failures, "claim with the world's name", null, world.getServer().computeOnServer(server -> claimReason(server, WORLD_NAME)));

			expect(failures, "viewpoint after a failed reset", EnvironmentException.Reason.INVALID_STATE, rejection(context, runtime -> runtime.setViewpoint(FixturePlans.VIEWPOINT.pose())));
			expect(failures, "step", EnvironmentException.Reason.UNSUPPORTED, rejection(context, runtime -> runtime.step("move_forward")));

			Map<BlockPos, BlockState> sentinels = placeSentinels(context, world);

			Map<String, String> firstHashes = new LinkedHashMap<>();
			for (FixturePlans.Fixture fixture : FixturePlans.Fixture.values()) {
				EnvironmentRuntime.SceneState scene = await(context, runtime -> resetFixture(runtime, fixture));
				String expected = FixturePlans.plan(fixture).layoutSha256(FixturePlans.ARENA);
				if (!expected.equals(scene.layoutSha256())) {
					failures.add(fixture + " layout " + scene.layoutSha256() + " differs from its plan " + expected);
				}

				firstHashes.put(fixture.name(), scene.layoutSha256());
			}

			for (FixturePlans.Fixture fixture : List.of(FixturePlans.Fixture.OPEN, FixturePlans.Fixture.EMPTY, FixturePlans.Fixture.FULL)) {
				String rebuilt = await(context, runtime -> resetFixture(runtime, fixture)).layoutSha256();
				if (!rebuilt.equals(firstHashes.get(fixture.name()))) {
					failures.add(fixture + " rebuild changed its layout checksum");
				}
			}

			checkSentinels(context, world, sentinels, failures);

			CompletableFuture<EnvironmentRuntime.SceneState> running = context.computeOnClient(minecraft -> resetFixture(EnvironmentRuntime.get(), FixturePlans.Fixture.OPEN));
			expect(failures, "concurrent reset", EnvironmentException.Reason.BUSY, rejection(context, runtime -> resetFixture(runtime, FixturePlans.Fixture.PLATFORM)));
			context.waitFor(minecraft -> running.isDone());
			running.join();

			observe(context, "m2_open", failures, true);
			await(context, runtime -> resetFixture(runtime, FixturePlans.Fixture.FULL));
			observe(context, "m2_full", failures, false);

			EnvironmentRuntime.ViewpointState view = await(context, runtime -> runtime.setViewpoint(new CameraPose(10.5, 65.0, 10.5, 45.0F, 5.0F)));
			if (Math.abs(view.pose().x() - 10.5) > 1e-9 || view.pose().yawDegrees() != 45.0F) {
				failures.add("viewpoint resolved to " + view.pose());
			}

			expect(failures, "viewpoint outside the arena", EnvironmentException.Reason.INVALID_REQUEST, rejection(context, runtime -> runtime.setViewpoint(new CameraPose(500.5, 65.0, 0.5, 0.0F, 0.0F))));
			if (context.computeOnClient(minecraft -> EnvironmentRuntime.get().status().state()) != OperationState.FAILED) {
				failures.add("a failed viewpoint did not leave the coordinator FAILED");
			}

			await(context, runtime -> resetFixture(runtime, FixturePlans.Fixture.PLATFORM));

			boolean restored = world.getServer().computeOnServer(server -> Baseline.restore(server));
			Map<String, String> rulesAfter = world.getServer().computeOnServer(M2EnvironmentTest::controlledRules);
			GameType modeAfter = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().gameMode());
			if (!restored || !rulesBefore.equals(rulesAfter) || modeBefore != modeAfter) {
				failures.add("release restored=" + restored + " rules " + rulesBefore + " -> " + rulesAfter + ", game mode " + modeBefore + " -> " + modeAfter);
			}

			boolean inOverworld = world.getServer().computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().level().dimension().equals(Level.OVERWORLD));
			if (!inOverworld) {
				failures.add("release did not return the player to the overworld");
			}
		}
	}

	private static CompletableFuture<EnvironmentRuntime.SceneState> resetFixture(EnvironmentRuntime runtime, FixturePlans.Fixture fixture) {
		return runtime.reset(FixturePlans.ARENA, FixturePlans.plan(fixture), FixturePlans.VIEWPOINT.pose());
	}

	private static void observe(ClientGameTestContext context, String label, List<String> failures, boolean visible) {
		CapturedImages images = await(context, runtime -> runtime.observe(new PerspectiveProjection(64, 64))).capture().images();
		int visiblePixels = MaskStatistics.of(images.visibleMask(), 64, 64).positivePixels();
		int unoccluded = images.unoccludedPixels();
		boolean passed = unoccluded > 0 && (visible ? visiblePixels == unoccluded : visiblePixels == 0);
		if (!passed) {
			failures.add(label + ": visible=" + visiblePixels + " unoccluded=" + unoccluded);
		}
	}

	/** Blocks just outside the reset volume on every side, below the floor and at a corner, plus debris inside. */
	private static Map<BlockPos, BlockState> placeSentinels(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockBox volume = FixturePlans.ARENA.resetVolume();
		int midX = (volume.minX() + volume.maxX()) / 2;
		int midY = (volume.minY() + volume.maxY()) / 2;
		int midZ = (volume.minZ() + volume.maxZ()) / 2;
		Map<BlockPos, BlockState> sentinels = new LinkedHashMap<>();
		BlockState marker = Blocks.GOLD_BLOCK.defaultBlockState();
		for (BlockPos pos : List.of(
			new BlockPos(volume.minX() - 1, midY, midZ),
			new BlockPos(volume.maxX() + 1, midY, midZ),
			new BlockPos(midX, midY, volume.minZ() - 1),
			new BlockPos(midX, midY, volume.maxZ() + 1),
			new BlockPos(midX, volume.maxY() + 1, midZ),
			new BlockPos(midX, volume.minY() - 1, midZ),
			new BlockPos(volume.maxX() + 1, volume.maxY() + 1, volume.maxZ() + 1),
			new BlockPos(volume.minX(), volume.minY() - 1, volume.minZ())
		)) {
			sentinels.put(pos, marker);
		}

		world.getServer().runOnServer(server -> {
			ServerLevel arena = server.getLevel(ArenaDimension.LEVEL);
			sentinels.forEach((pos, state) -> {
				arena.getChunk(pos);
				arena.setBlock(pos, state, 2);
			});
			arena.getChunk(new BlockPos(volume.minX(), volume.maxY(), volume.minZ()));
			arena.setBlock(new BlockPos(volume.minX(), volume.maxY(), volume.minZ()), Blocks.DIRT.defaultBlockState(), 2);
			arena.setBlock(new BlockPos(volume.maxX(), volume.minY() + 3, volume.maxZ()), Blocks.DIRT.defaultBlockState(), 2);
			ItemEntity item = new ItemEntity(arena, 2.5, 66.0, 2.5, new ItemStack(Items.DIAMOND));
			item.setNoGravity(true);
			arena.addFreshEntity(item);
		});
		context.waitTick();
		return sentinels;
	}

	private static void checkSentinels(ClientGameTestContext context, TestSingleplayerContext world, Map<BlockPos, BlockState> sentinels, List<String> failures) {
		BlockBox volume = FixturePlans.ARENA.resetVolume();
		world.getServer().runOnServer(server -> {
			ServerLevel arena = server.getLevel(ArenaDimension.LEVEL);
			sentinels.forEach((pos, state) -> {
				if (arena.getBlockState(pos) != state) {
					failures.add("sentinel at " + pos + " changed to " + arena.getBlockState(pos));
				}
			});
			for (BlockPos debris : List.of(new BlockPos(volume.minX(), volume.maxY(), volume.minZ()), new BlockPos(volume.maxX(), volume.minY() + 3, volume.maxZ()))) {
				if (!arena.getBlockState(debris).isAir()) {
					failures.add("margin debris at " + debris + " survived the reset");
				}
			}

			AABB bounds = new AABB(volume.minX(), volume.minY(), volume.minZ(), volume.maxX() + 1, volume.maxY() + 1, volume.maxZ() + 1);
			if (!arena.getEntitiesOfClass(ItemEntity.class, bounds).isEmpty()) {
				failures.add("an item entity survived the reset");
			}
		});
	}

	private static Map<String, String> controlledRules(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		Map<String, String> values = new LinkedHashMap<>();
		values.put("spawn_monsters", rules.getAsString(GameRules.SPAWN_MONSTERS));
		values.put("random_tick_speed", rules.getAsString(GameRules.RANDOM_TICK_SPEED));
		values.put("mob_griefing", rules.getAsString(GameRules.MOB_GRIEFING));
		return values;
	}

	private static EnvironmentException.Reason claimReason(MinecraftServer server, String name) {
		try {
			WorldGuard.claim(server, name);
			return null;
		} catch (EnvironmentException e) {
			return e.reason();
		}
	}

	private static <T> T await(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		return GameTestSupport.await(context, request);
	}

	private static <T> EnvironmentException.Reason rejection(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		return GameTestSupport.rejection(context, request);
	}

	private static void expect(List<String> failures, String what, EnvironmentException.Reason expected, EnvironmentException.Reason actual) {
		if (expected != actual) {
			failures.add(what + ": expected " + expected + " but got " + actual);
		}
	}
}
