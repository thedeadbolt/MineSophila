package io.github.thedeadbolt.honeyfactory.world;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies scene plans to the owned arena on the logical server thread and reports what
 * the world now contains. Every write is checked against the ownership record first.
 */
public final class ArenaExecutor {
	/** Exact states, no neighbour updates, drops or placement side effects. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_ON_PLACE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	/** World facts after a reset; the expected and actual hashes are equal on success. */
	public record ResetResult(String layoutSha256, long blocksWritten, long serverTick) {
	}

	private ArenaExecutor() {
	}

	/**
	 * Resets the arena to {@code plan}: stage players, clear the reset volume, restore the
	 * template, apply the delta in order, set the environment and verify the layout.
	 *
	 * @throws EnvironmentException if the world is not owned or the result differs from the plan
	 */
	public static ResetResult reset(MinecraftServer server, ArenaLayout arena, ScenePlan plan) {
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		WorldGuard.requireWritable(record, arena.resetVolume());
		ServerLevel level = WorldGuard.arena(server);
		HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		Baseline.applyWorld(server);
		buildStaging(record, level);
		for (ServerPlayer player : List.copyOf(level.players())) {
			stage(server, player);
		}

		loadChunks(level, arena.resetVolume());
		BlockBox volume = arena.resetVolume();
		AABB bounds = new AABB(volume.minX(), volume.minY(), volume.minZ(), volume.maxX() + 1, volume.maxY() + 1, volume.maxZ() + 1);
		level.getEntitiesOfClass(Entity.class, bounds, entity -> !(entity instanceof Player)).forEach(Entity::discard);

		long written = 0;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = volume.minY(); y <= volume.maxY(); y++) {
			for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
				for (int x = volume.minX(); x <= volume.maxX(); x++) {
					if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
						level.setBlock(pos, air, FLAGS);
						written++;
					}
				}
			}
		}

		written += place(level, arena, plan.template().blocks(), blocks);
		written += place(level, arena, plan.delta(), blocks);
		Baseline.applyEnvironment(server, plan.timeOfDayTicks(), plan.weather());

		String expected = plan.layoutSha256(arena);
		String actual = BlockStates.layoutSha256(level, volume);
		if (!expected.equals(actual)) {
			throw new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH, "arena layout " + actual + " differs from the plan's " + expected);
		}

		return new ResetResult(actual, written, server.getTickCount());
	}

	/**
	 * Places a player at {@code pose} in the arena with zero velocity.
	 *
	 * @throws EnvironmentException if the world is not owned or the eye would leave the reset volume
	 */
	public static CameraPose placeViewpoint(MinecraftServer server, ServerPlayer player, ArenaLayout arena, CameraPose pose, double eyeHeight) {
		WorldGuard.requireOwned(server);
		BlockBox volume = arena.resetVolume();
		if (!volume.contains((int) Math.floor(pose.x()), (int) Math.floor(pose.y() + eyeHeight), (int) Math.floor(pose.z()))) {
			throw new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "camera eye must stay inside the arena reset volume " + volume);
		}

		ServerLevel level = WorldGuard.arena(server);
		Baseline.applyWorld(server);
		Baseline.preparePlayer(server, player);
		player.teleportTo(level, pose.x(), pose.y(), pose.z(), Set.of(), pose.yawDegrees(), pose.pitchDegrees(), true);
		player.setDeltaMovement(Vec3.ZERO);
		return new CameraPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	/** Moves a player to the staging platform with zero velocity, outside the arena. */
	public static void stage(MinecraftServer server, ServerPlayer player) {
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		ServerLevel level = WorldGuard.arena(server);
		buildStaging(record, level);
		Baseline.applyWorld(server);
		Baseline.preparePlayer(server, player);
		double[] staging = ArenaLayout.STAGING_POSITION;
		player.teleportTo(level, staging[0], staging[1], staging[2], Set.of(), 0.0F, 0.0F, true);
		player.setDeltaMovement(Vec3.ZERO);
	}

	private static void buildStaging(WorldOwnership.Record record, ServerLevel level) {
		BlockBox platform = record.stagingPlatform();
		WorldGuard.requireWritable(record, platform);
		loadChunks(level, platform);
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		for (BlockPos pos : BlockPos.betweenClosed(platform.minX(), platform.minY(), platform.minZ(), platform.maxX(), platform.maxY(), platform.maxZ())) {
			if (level.getBlockState(pos) != barrier) {
				level.setBlock(pos, barrier, FLAGS);
			}
		}
	}

	private static long place(ServerLevel level, ArenaLayout arena, List<Placement> placements, HolderLookup<Block> blocks) {
		BlockBox core = arena.core();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Placement placement : placements) {
			level.setBlock(pos.set(core.minX() + placement.x(), core.minY() + placement.y(), core.minZ() + placement.z()), BlockStates.parse(placement.state(), blocks), FLAGS);
		}

		return placements.size();
	}

	/** Keeps the box's chunks loaded while the world is used for datasets, and loads them now. */
	private static void loadChunks(ServerLevel level, BlockBox box) {
		int[] chunks = box.chunkRange();
		for (int chunkX = chunks[0]; chunkX <= chunks[2]; chunkX++) {
			for (int chunkZ = chunks[1]; chunkZ <= chunks[3]; chunkZ++) {
				level.setChunkForced(chunkX, chunkZ, true);
				level.getChunk(chunkX, chunkZ);
			}
		}
	}
}
