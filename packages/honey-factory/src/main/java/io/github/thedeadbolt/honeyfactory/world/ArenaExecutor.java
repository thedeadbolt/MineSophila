package io.github.thedeadbolt.honeyfactory.world;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatch;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
import org.jspecify.annotations.Nullable;

/**
 * Applies scene plans to the owned arena on the logical server thread and reports what
 * the world now contains. Every write is checked against the ownership record first.
 * Plans on a source patch go to the natural region ({@link NaturalPatches}).
 */
public final class ArenaExecutor {
	/** Exact states, no neighbour updates, drops or placement side effects. */
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_ON_PLACE | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	/**
	 * World facts after a reset; the expected and actual hashes are equal on success. The
	 * client checks it shows the scene by hashing {@code readinessVolume}, the whole reset
	 * volume of a platform arena but only the core of a copied patch, against
	 * {@code readinessSha256}. {@code copy} is set when a source patch was copied.
	 */
	public record ResetResult(
		String layoutSha256,
		long blocksWritten,
		long serverTick,
		BlockBox readinessVolume,
		String readinessSha256,
		NaturalPatches.@Nullable CopyStats copy
	) {
	}

	/** The source patch the natural region holds, and the cells the last scene changed in it. */
	private record NaturalState(String worldId, String patchId, ArenaLayout arena, List<BlockPos> changed) {
	}

	private static @Nullable NaturalState natural;

	private ArenaExecutor() {
	}

	/**
	 * Resets the arena to {@code plan}: stage players, write every cell of the reset volume
	 * that differs from the template with the delta applied in order (air elsewhere), set the
	 * environment and verify the layout. Cells that already hold the scene's state are left
	 * alone, so a reset costs only what changed since the last scene.
	 *
	 * @throws EnvironmentException if the world is not owned or the result differs from the plan
	 */
	public static ResetResult reset(MinecraftServer server, ArenaLayout arena, ScenePlan plan) {
		if (plan.patch() != null) {
			return resetNatural(server, arena, plan, plan.patch());
		}

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

		// The scene's state of every cell it sets, the template first and the delta over it;
		// every other cell of the reset volume is air. Only cells that hold something else are
		// written, so the client is sent, and rebuilds, only what changed since the last scene.
		Long2ObjectOpenHashMap<BlockState> wanted = new Long2ObjectOpenHashMap<>();
		BlockBox core = arena.core();
		for (List<Placement> placements : List.of(plan.template().blocks(), plan.delta())) {
			for (Placement placement : placements) {
				wanted.put(BlockPos.asLong(core.minX() + placement.x(), core.minY() + placement.y(), core.minZ() + placement.z()), BlockStates.parse(placement.state(), blocks));
			}
		}

		long written = 0;
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = volume.minY(); y <= volume.maxY(); y++) {
			for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
				for (int x = volume.minX(); x <= volume.maxX(); x++) {
					BlockState want = wanted.getOrDefault(BlockPos.asLong(x, y, z), air);
					if (level.getBlockState(pos.set(x, y, z)) != want) {
						level.setBlock(pos, want, FLAGS);
						written++;
					}
				}
			}
		}

		Baseline.applyEnvironment(server, plan.timeOfDayTicks(), plan.weather());

		String expected = plan.layoutSha256(arena);
		String actual = BlockStates.layoutSha256(level, volume);
		if (!expected.equals(actual)) {
			throw new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH, "arena layout " + actual + " differs from the plan's " + expected);
		}

		return new ResetResult(actual, written, server.getTickCount(), volume, actual, null);
	}

	/**
	 * Resets the natural region to a source patch plus the plan's delta. When the region
	 * still holds this patch from the previous scene, only the cells that scene changed are
	 * read back from the source; otherwise the patch is copied, relit and checked once
	 * against its content hash over the whole reset volume. Every scene then checks its core,
	 * the only part a scene ever writes, against the hash read from the source.
	 */
	private static ResetResult resetNatural(MinecraftServer server, ArenaLayout arena, ScenePlan plan, SourcePatch patch) {
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		WorldGuard.requireWritable(record, arena.resetVolume());
		ServerLevel level = WorldGuard.arena(server);
		HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
		Baseline.applyWorld(server);
		buildStaging(record, level);
		for (ServerPlayer player : List.copyOf(level.players())) {
			stage(server, player);
		}

		BlockBox volume = arena.resetVolume();
		AABB bounds = new AABB(volume.minX(), volume.minY(), volume.minZ(), volume.maxX() + 1, volume.maxY() + 1, volume.maxZ() + 1);
		level.getEntitiesOfClass(Entity.class, bounds, entity -> !(entity instanceof Player)).forEach(Entity::discard);
		NaturalState held = natural;
		natural = null;
		NaturalPatches.CopyStats copy = null;
		long written = 0;
		boolean reuse = held != null && held.worldId().equals(record.worldId()) && held.patchId().equals(patch.patchId()) && held.arena().equals(arena)
			&& record.naturalWritten().map(volume::equals).orElse(false);
		if (reuse) {
			int[] shift = {patch.sourceMin()[0] - volume.minX(), patch.sourceMin()[1] - volume.minY(), patch.sourceMin()[2] - volume.minZ()};
			for (BlockPos pos : held.changed()) {
				level.setBlock(pos, server.overworld().getBlockState(pos.offset(shift[0], shift[1], shift[2])), FLAGS);
				written++;
			}
		} else {
			copy = NaturalPatches.copy(server, arena, patch);
			String copied = BlockStates.layoutSha256(level, volume);
			if (!copied.equals(patch.contentSha256())) {
				throw new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH, "copied natural region " + copied + " differs from source patch " + patch.patchId() + "'s content " + patch.contentSha256());
			}

			resendChunks(level, volume);
		}

		written += place(level, arena, plan.delta(), blocks);
		Baseline.applyEnvironment(server, plan.timeOfDayTicks(), plan.weather());
		BlockBox core = arena.core();
		String expected = NaturalPatches.layoutSha256(server, arena, patch, core, plan.delta());
		String actual = BlockStates.layoutSha256(level, core);
		if (!expected.equals(actual)) {
			throw new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH, "natural core layout " + actual + " differs from source patch " + patch.patchId() + " with the plan's " + expected);
		}

		List<BlockPos> changed = plan.delta().stream().map(p -> new BlockPos(core.minX() + p.x(), core.minY() + p.y(), core.minZ() + p.z())).toList();
		natural = new NaturalState(record.worldId(), patch.patchId(), arena, changed);
		return new ResetResult(actual, written, server.getTickCount(), core, actual, copy);
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

	/**
	 * Gives a player in the arena a body for movement actions where they stand (see
	 * {@link Baseline#prepareBody}); returns the server tick.
	 *
	 * @throws EnvironmentException if the world is not owned, or the player is not in the arena's reset volume
	 */
	public static long embody(MinecraftServer server, ServerPlayer player, ArenaLayout arena) {
		WorldGuard.requireOwned(server);
		if (player.level() != WorldGuard.arena(server) || !arena.resetVolume().contains(player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
			throw new EnvironmentException(EnvironmentException.Reason.OUT_OF_ARENA, "the player is not in the arena reset volume " + arena.resetVolume());
		}

		Baseline.applyWorld(server);
		Baseline.prepareBody(server, player);
		return server.getTickCount();
	}

	/**
	 * Sends every chunk of {@code box} again, light included, to the players tracking it. A
	 * copy replaces whole sections without block updates, and a reset moves players to the
	 * staging platform and back within one server task, so the server never sees those
	 * chunks leave a client's view.
	 */
	private static void resendChunks(ServerLevel level, BlockBox box) {
		int[] chunks = box.chunkRange();
		for (ServerPlayer player : level.players()) {
			for (int chunkX = chunks[0]; chunkX <= chunks[2]; chunkX++) {
				for (int chunkZ = chunks[1]; chunkZ <= chunks[3]; chunkZ++) {
					if (player.getChunkTrackingView().contains(chunkX, chunkZ)) {
						player.connection.send(new ClientboundLevelChunkWithLightPacket(level.getChunk(chunkX, chunkZ), level.getLightEngine(), null, null));
					}
				}
			}
		}
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
