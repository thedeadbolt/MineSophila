package io.github.thedeadbolt.honeyfactory.world;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.LayoutHasher;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PatchTerrain;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatch;
import io.github.thedeadbolt.honeyfactory.scene.SourcePatches;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * Level-5 source patches on the logical server: choosing one in the dataset world's normal
 * Overworld, copying it into the owned natural region of the void arena, and hashing
 * layouts from the source. The source is only ever read.
 *
 * <p>A candidate ({@link SourcePatches}) is first screened with the terrain generator's
 * noise heights, which need no chunk ({@link #prepareAsync}). One that passes is generated
 * and rejected, in this order, when
 *
 * <ul>
 *   <li>{@value #VOID_BELOW}: some column's highest motion-blocking non-fluid block lies
 *       below the copied window, so void would show beneath water or through a gap;</li>
 *   <li>{@value #NATURAL_TARGET}: the copied window holds a block of the target type, which
 *       would look like a target without being labelled as one;</li>
 *   <li>{@value #FEW_TARGET_SITES}: the core has fewer than {@value #MIN_TARGET_SITES}
 *       {@link PatchTerrain#targetSites() target sites}.</li>
 * </ul>
 *
 * <p>Copying replaces whole chunk sections: block states and biomes, then block entities
 * (beehives lose their bees, so no mob leaves them). Sections of the natural region the
 * previous copy wrote and this one does not are emptied. Lighting is then recalculated in
 * two passes: the old light data of every changed chunk is dropped, then light is enabled
 * for all of them before any propagates, so light crosses chunk borders as in a freshly
 * generated world.
 */
public final class NaturalPatches {
	public static final String DIMENSION = "minecraft:overworld";
	public static final String VOID_BELOW = "patch_void_below";
	public static final String NATURAL_TARGET = "patch_natural_target";
	public static final String FEW_TARGET_SITES = "patch_few_target_sites";
	public static final String VOID_BELOW_ESTIMATED = "patch_void_below_estimated";
	public static final String OCEAN_ESTIMATED = "patch_ocean_estimated";
	public static final int MIN_TARGET_SITES = 16;
	private static final int SCREEN_STEP = 32;
	private static final int SCREEN_MARGIN = 8;
	/**
	 * Keeps a candidate's source chunks loading and loaded for five minutes, long enough to
	 * generate, check and copy them. Minecraft's own temporary ticket expires after one tick,
	 * which drops chunks still generating.
	 */
	public static final TicketType SOURCE_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE, HoneyFactory.id("source_patch"), new TicketType(6000L, TicketType.FLAG_LOADING));
	/** Screens candidates and requests their chunks, never on the server thread. */
	private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Honey Factory patch loader");
		thread.setDaemon(true);
		return thread;
	});

	/** What to prepare: patch {@code patchIndex} of the dataset with {@code datasetSeed}. */
	public record Request(long datasetSeed, SceneBase.NaturalTerrain base, int patchIndex, String targetBlockId) {
	}

	/** Blocks and section changes of one copy, and how long its phases took. */
	public record CopyStats(int chunks, int sectionsCopied, int sectionsCleared, int blockEntities, long copyNanos, long lightNanos) {
	}

	private NaturalPatches() {
	}

	/** {@code minecraft-<version>}, the terrain generator a patch came from. */
	public static String generatorVersion() {
		return "minecraft-" + SharedConstants.getCurrentVersion().name();
	}

	/**
	 * The first acceptable candidate of the requested patch, waiting on the server thread;
	 * see {@link #prepareAsync}.
	 *
	 * @throws EnvironmentException when every candidate is rejected
	 */
	public static PreparedPatch prepare(MinecraftServer server, Request request) {
		CompletableFuture<PreparedPatch> future = prepareAsync(server, request);
		server.managedBlock(future::isDone);
		try {
			return future.join();
		} catch (java.util.concurrent.CompletionException e) {
			throw e.getCause() instanceof RuntimeException cause ? cause : e;
		}
	}

	/**
	 * Finds the first acceptable candidate of the requested patch without holding the server
	 * thread while terrain generates. Each candidate is first screened with the terrain
	 * generator's noise heights ({@value #VOID_BELOW_ESTIMATED}, {@value #OCEAN_ESTIMATED}),
	 * which costs no chunk; a candidate that passes has all its chunks requested at once, so
	 * they generate in parallel, and is then checked on the server thread. Callable from any
	 * thread.
	 */
	public static CompletableFuture<PreparedPatch> prepareAsync(MinecraftServer server, Request request) {
		return CompletableFuture.supplyAsync(() -> {
			WorldGuard.requireOwned(server);
			return new TreeMap<String, Integer>();
		}, server).thenComposeAsync(rejected -> candidate(server, request, 0, rejected), LOADER);
	}

	/** Candidate {@code draw}, on the loader thread, then the next one if it is rejected. */
	private static CompletableFuture<PreparedPatch> candidate(MinecraftServer server, Request request, int draw, Map<String, Integer> rejected) {
		if (draw == SourcePatches.DRAWS) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST,
				"no acceptable source patch for patch " + request.patchIndex() + " in " + SourcePatches.DRAWS + " candidates: " + rejected));
		}

		SceneBase.NaturalTerrain base = request.base();
		ServerLevel source = server.overworld();
		int[] corner = SourcePatches.corner(request.datasetSeed(), base, request.patchIndex(), draw);
		String screened = screen(source, corner, base);
		if (screened != null) {
			return reject(server, request, draw, corner, screened, rejected);
		}

		long loading = System.nanoTime();
		return load(server, source, corner[0], corner[1], base.copyWidth(), base.copyLength())
			.thenComposeAsync(ignored -> CompletableFuture.supplyAsync(() -> {
				long evaluating = System.nanoTime();
				PreparedPatch patch = evaluate(server, request, draw, corner, rejected);
				HoneyFactory.LOGGER.info("Source patch {} draw {}: terrain loaded in {} s, checked in {} s{}", request.patchIndex(), draw,
					String.format(java.util.Locale.ROOT, "%.2f", (evaluating - loading) / 1e9), String.format(java.util.Locale.ROOT, "%.2f", (System.nanoTime() - evaluating) / 1e9),
					patch == null ? "" : ", accepted");
				return patch;
			}, server), LOADER)
			.thenComposeAsync(patch -> patch != null ? CompletableFuture.completedFuture(patch) : candidate(server, request, draw + 1, rejected), LOADER);
	}

	private static CompletableFuture<PreparedPatch> reject(MinecraftServer server, Request request, int draw, int[] corner, String reason, Map<String, Integer> rejected) {
		rejected.merge(reason, 1, Integer::sum);
		HoneyFactory.LOGGER.info("Source patch {} draw {} at {} rejected: {}", request.patchIndex(), draw, Arrays.toString(corner), reason);
		return candidate(server, request, draw + 1, rejected);
	}

	/**
	 * Rejects a candidate from noise heights alone, sampled every {@value #SCREEN_STEP} blocks:
	 * when the lowest estimated ground lies {@value #SCREEN_MARGIN} blocks below the window the
	 * highest estimated surface implies, or when nearly every core sample is water. The
	 * estimates leave out trees and other features, which only raise the real window, so a
	 * screened-out candidate would also fail the real checks.
	 */
	private static @Nullable String screen(ServerLevel source, int[] corner, SceneBase.NaturalTerrain base) {
		ChunkGenerator generator = source.getChunkSource().getGenerator();
		RandomState random = source.getChunkSource().randomState();
		int highest = Integer.MIN_VALUE;
		int lowest = Integer.MAX_VALUE;
		int coreSamples = 0;
		int coreWater = 0;
		for (int dz = 0; dz < base.copyLength(); dz += SCREEN_STEP) {
			for (int dx = 0; dx < base.copyWidth(); dx += SCREEN_STEP) {
				int x = corner[0] + dx;
				int z = corner[1] + dz;
				int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, source, random);
				int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, source, random);
				highest = Math.max(highest, surface);
				lowest = Math.min(lowest, floor);
				boolean core = dx >= base.contextPadding() && dx < base.contextPadding() + base.coreWidth() && dz >= base.contextPadding() && dz < base.contextPadding() + base.coreLength();
				if (core) {
					coreSamples++;
					coreWater += surface > floor ? 1 : 0;
				}
			}
		}

		if (lowest < SourcePatches.window(highest, base.height(), source.getMinY(), source.getMaxY()) - SCREEN_MARGIN) {
			return VOID_BELOW_ESTIMATED;
		}

		return coreSamples > 0 && coreWater * 10 >= coreSamples * 9 ? OCEAN_ESTIMATED : null;
	}

	/**
	 * Tickets every chunk of the box on the server thread, then requests them all from the
	 * loader thread, so they generate in parallel and stay loaded for the checks and the copy.
	 */
	private static CompletableFuture<Void> load(MinecraftServer server, ServerLevel level, int minX, int minZ, int width, int length) {
		List<ChunkPos> chunks = new ArrayList<>();
		for (int chunkX = SectionPos.blockToSectionCoord(minX); chunkX <= SectionPos.blockToSectionCoord(minX + width - 1); chunkX++) {
			for (int chunkZ = SectionPos.blockToSectionCoord(minZ); chunkZ <= SectionPos.blockToSectionCoord(minZ + length - 1); chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ));
			}
		}

		return CompletableFuture.runAsync(() -> chunks.forEach(pos -> level.getChunkSource().addTicketWithRadius(SOURCE_TICKET, pos, 0)), server)
			.thenComposeAsync(ignored -> CompletableFuture.allOf(chunks.stream()
				.map(pos -> level.getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true))
				.toArray(CompletableFuture[]::new)), LOADER);
	}

	/** The real checks of a loaded candidate, on the server thread: the patch, or null after recording why it was rejected. */
	private static @Nullable PreparedPatch evaluate(MinecraftServer server, Request request, int draw, int[] corner, Map<String, Integer> rejected) {
		ServerLevel source = server.overworld();
		SceneBase.NaturalTerrain base = request.base();
		Chunks chunks = new Chunks(source, corner[0], corner[1], base.copyWidth(), base.copyLength());
		int highest = Integer.MIN_VALUE;
		int lowestFloor = Integer.MAX_VALUE;
		for (int z = corner[1]; z < corner[1] + base.copyLength(); z++) {
			for (int x = corner[0]; x < corner[0] + base.copyWidth(); x++) {
				LevelChunk chunk = chunks.at(x, z);
				highest = Math.max(highest, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
				lowestFloor = Math.min(lowestFloor, chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z));
			}
		}

		int bottom = SourcePatches.window(highest, base.height(), source.getMinY(), source.getMaxY());
		int[] min = {corner[0], bottom, corner[1]};
		int[] max = {corner[0] + base.copyWidth() - 1, bottom + base.height() - 1, corner[1] + base.copyLength() - 1};
		String reason = null;
		PatchTerrain terrain = null;
		if (lowestFloor < bottom) {
			reason = VOID_BELOW;
		} else if (containsBlock(chunks, min, max, request.targetBlockId())) {
			reason = NATURAL_TARGET;
		} else {
			terrain = terrain(source, chunks, new int[] {min[0] + base.contextPadding(), bottom, min[2] + base.contextPadding()}, base);
			if (terrain.targetSites().size() < MIN_TARGET_SITES) {
				reason = FEW_TARGET_SITES;
			}
		}

		if (reason != null) {
			rejected.merge(reason, 1, Integer::sum);
			HoneyFactory.LOGGER.info("Source patch {} draw {} at {} rejected: {}", request.patchIndex(), draw, Arrays.toString(min), reason);
			return null;
		}

		String content = layoutSha256(chunks, min, ArenaLayout.natural(base).resetVolume(), Map.of());
		String id = SourcePatch.id(source.getSeed(), DIMENSION, min, max, generatorVersion());
		return new PreparedPatch(new SourcePatch(id, source.getSeed(), DIMENSION, min, max, generatorVersion(), request.patchIndex(), draw, content, rejected), terrain);
	}

	/**
	 * Copies {@code patch} into {@code arena}'s reset volume and relights it. Players must
	 * already be away from the natural region.
	 */
	public static CopyStats copy(MinecraftServer server, ArenaLayout arena, SourcePatch patch) {
		WorldOwnership ownership = WorldOwnership.of(server);
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		BlockBox reset = arena.resetVolume();
		WorldGuard.requireWritable(record, reset);
		long started = System.nanoTime();
		ServerLevel source = server.overworld();
		ServerLevel level = WorldGuard.arena(server);
		int[] sourceMin = patch.sourceMin();
		int shiftX = reset.minX() - sourceMin[0];
		int shiftY = reset.minY() - sourceMin[1];
		int shiftZ = reset.minZ() - sourceMin[2];
		Chunks chunks = new Chunks(source, sourceMin[0], sourceMin[2], reset.maxX() - reset.minX() + 1, reset.maxZ() - reset.minZ() + 1);
		BlockBox owned = ArenaLayout.NATURAL_OWNED;
		BlockBox previous = record.naturalWritten().orElse(null);
		int[] range = reset.chunkRange();
		int[] previousRange = previous == null ? range : previous.chunkRange();
		List<LevelChunk> changed = new ArrayList<>();
		int copied = 0;
		int cleared = 0;
		int blockEntities = 0;
		for (int chunkX = Math.min(range[0], previousRange[0]); chunkX <= Math.max(range[2], previousRange[2]); chunkX++) {
			for (int chunkZ = Math.min(range[1], previousRange[1]); chunkZ <= Math.max(range[3], previousRange[3]); chunkZ++) {
				boolean inside = chunkX >= range[0] && chunkX <= range[2] && chunkZ >= range[1] && chunkZ <= range[3];
				boolean written = chunkX >= previousRange[0] && chunkX <= previousRange[2] && chunkZ >= previousRange[1] && chunkZ <= previousRange[3];
				if (!inside && !(previous != null && written)) {
					continue;
				}

				level.setChunkForced(chunkX, chunkZ, inside);
				LevelChunk chunk = level.getChunk(chunkX, chunkZ);
				for (BlockPos pos : List.copyOf(chunk.getBlockEntitiesPos())) {
					if (pos.getY() >= owned.minY() && pos.getY() <= owned.maxY()) {
						chunk.removeBlockEntity(pos);
					}
				}

				LevelChunk from = inside ? chunks.at((chunkX << 4) - shiftX, (chunkZ << 4) - shiftZ) : null;
				for (int sectionY = SectionPos.blockToSectionCoord(owned.minY()); sectionY <= SectionPos.blockToSectionCoord(owned.maxY()); sectionY++) {
					int index = level.getSectionIndexFromSectionY(sectionY);
					int y = SectionPos.sectionToBlockCoord(sectionY);
					if (from != null && y >= reset.minY() && y <= reset.maxY()) {
						chunk.getSections()[index] = from.getSection(source.getSectionIndexFromSectionY(SectionPos.blockToSectionCoord(y - shiftY))).copy();
						copied++;
					} else if (!chunk.getSection(index).hasOnlyAir()) {
						chunk.getSections()[index] = new LevelChunkSection(level.palettedContainerFactory());
						cleared++;
					}
				}

				if (from != null) {
					blockEntities += copyBlockEntities(server, from, chunk, sourceMin[1], sourceMin[1] + reset.maxY() - reset.minY(), shiftX, shiftY, shiftZ);
				}

				Heightmap.primeHeightmaps(chunk, ChunkStatus.FULL.heightmapsAfter());
				chunk.initializeLightSources();
				chunk.markUnsaved();
				changed.add(chunk);
			}
		}

		long copiedAt = System.nanoTime();
		relight(server, level, changed);
		ownership.set(WorldGuard.requireOwned(server).withNaturalWritten(reset));
		return new CopyStats(changed.size(), copied, cleared, blockEntities, copiedAt - started, System.nanoTime() - copiedAt);
	}

	/** Every block entity of {@code from} within source rows {@code [minY, maxY]}, moved by the shift; returns how many. */
	private static int copyBlockEntities(MinecraftServer server, LevelChunk from, LevelChunk to, int minY, int maxY, int shiftX, int shiftY, int shiftZ) {
		int count = 0;
		for (BlockPos pos : List.copyOf(from.getBlockEntitiesPos())) {
			BlockEntity entity = from.getBlockEntity(pos);
			if (entity == null || pos.getY() < minY || pos.getY() > maxY) {
				continue;
			}

			CompoundTag tag = entity.saveWithFullMetadata(server.registryAccess());
			tag.remove("bees");
			BlockPos target = pos.offset(shiftX, shiftY, shiftZ);
			BlockEntity copy = BlockEntity.loadStatic(target, to.getBlockState(target), tag, server.registryAccess());
			if (copy != null) {
				to.addAndRegisterBlockEntity(copy);
				count++;
			}
		}

		return count;
	}

	/**
	 * Drops the light of {@code chunks} and recalculates it, waiting on the server thread
	 * while the light engine works.
	 */
	private static void relight(MinecraftServer server, ServerLevel level, List<LevelChunk> chunks) {
		ThreadedLevelLightEngine engine = level.getChunkSource().getLightEngine();
		List<CompletableFuture<?>> dropped = new ArrayList<>();
		for (LevelChunk chunk : chunks) {
			ChunkPos pos = chunk.getPos();
			engine.retainData(pos, false);
			engine.setLightEnabled(pos, false);
			for (int sectionY = engine.getMinLightSection(); sectionY < engine.getMaxLightSection(); sectionY++) {
				engine.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, sectionY), null);
				engine.queueSectionData(LightLayer.SKY, SectionPos.of(pos, sectionY), null);
			}

			for (int sectionY = level.getMinSectionY(); sectionY <= level.getMaxSectionY(); sectionY++) {
				engine.updateSectionStatus(SectionPos.of(pos, sectionY), true);
			}

			dropped.add(engine.waitForPendingTasks(pos.x(), pos.z()));
		}

		await(server, engine, dropped);
		List<CompletableFuture<?>> enabled = new ArrayList<>();
		for (LevelChunk chunk : chunks) {
			enabled.add(engine.initializeLight(chunk, true));
		}

		await(server, engine, enabled);
		List<CompletableFuture<?>> lit = new ArrayList<>();
		for (LevelChunk chunk : chunks) {
			lit.add(engine.lightChunk(chunk, false));
		}

		await(server, engine, lit);
	}

	private static void await(MinecraftServer server, ThreadedLevelLightEngine engine, List<CompletableFuture<?>> futures) {
		CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
		server.managedBlock(() -> {
			engine.tryScheduleUpdate();
			return all.isDone();
		});
		all.join();
	}

	/**
	 * {@code layout-v1} hash of {@code box}, part of {@code arena}'s reset volume, as copying
	 * {@code patch} and then applying {@code delta} (core-relative placements in order) would
	 * leave it, read from the source.
	 */
	public static String layoutSha256(MinecraftServer server, ArenaLayout arena, SourcePatch patch, BlockBox box, List<Placement> delta) {
		BlockBox reset = arena.resetVolume();
		if (!reset.contains(box)) {
			throw new IllegalArgumentException(box + " is not inside the reset volume " + reset);
		}

		int[] min = patch.sourceMin();
		int[] sourceMin = {min[0] + box.minX() - reset.minX(), min[1] + box.minY() - reset.minY(), min[2] + box.minZ() - reset.minZ()};
		Chunks chunks = new Chunks(server.overworld(), sourceMin[0], sourceMin[2], box.maxX() - box.minX() + 1, box.maxZ() - box.minZ() + 1);
		Map<Long, String> overrides = new HashMap<>();
		int[] core = arena.core().min();
		for (Placement placement : delta) {
			overrides.put(BlockPos.asLong(core[0] + placement.x(), core[1] + placement.y(), core[2] + placement.z()), placement.state());
		}

		return layoutSha256(chunks, sourceMin, box, overrides);
	}

	/** Streams the source box starting at {@code sourceMin} as if it filled {@code box}, with absolute {@code overrides}. */
	private static String layoutSha256(Chunks chunks, int[] sourceMin, BlockBox box, Map<Long, String> overrides) {
		LayoutHasher hasher = new LayoutHasher(box.min(), box.max());
		Map<BlockState, String> names = new IdentityHashMap<>();
		int shiftX = box.minX() - sourceMin[0];
		int shiftY = box.minY() - sourceMin[1];
		int shiftZ = box.minZ() - sourceMin[2];
		for (int y = box.minY(); y <= box.maxY(); y++) {
			for (int z = box.minZ(); z <= box.maxZ(); z++) {
				for (int x = box.minX(); x <= box.maxX(); x++) {
					String override = overrides.isEmpty() ? null : overrides.get(BlockPos.asLong(x, y, z));
					if (override != null) {
						if (!override.equals(Placement.AIR)) {
							hasher.block(x, y, z, override);
						}

						continue;
					}

					BlockState state = chunks.state(x - shiftX, y - shiftY, z - shiftZ);
					if (!state.isAir()) {
						hasher.block(x, y, z, names.computeIfAbsent(state, BlockStates::canonical));
					}
				}
			}
		}

		return hasher.finish();
	}

	/**
	 * Whether the box holds a block of {@code blockId}. Each section's palette is asked first;
	 * a palette can keep entries no block uses any more, so only sections that may hold the
	 * block are scanned cell by cell.
	 */
	private static boolean containsBlock(Chunks chunks, int[] min, int[] max, String blockId) {
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockId));
		ServerLevel level = chunks.level;
		for (int chunkZ = SectionPos.blockToSectionCoord(min[2]); chunkZ <= SectionPos.blockToSectionCoord(max[2]); chunkZ++) {
			for (int chunkX = SectionPos.blockToSectionCoord(min[0]); chunkX <= SectionPos.blockToSectionCoord(max[0]); chunkX++) {
				LevelChunk chunk = chunks.at(chunkX << 4, chunkZ << 4);
				for (int sectionY = SectionPos.blockToSectionCoord(min[1]); sectionY <= SectionPos.blockToSectionCoord(max[1]); sectionY++) {
					int index = level.getSectionIndexFromSectionY(sectionY);
					if (index < 0 || index >= chunk.getSectionsCount()) {
						continue;
					}

					LevelChunkSection section = chunk.getSection(index);
					if (!section.getStates().maybeHas(state -> state.is(block))) {
						continue;
					}

					for (int y = Math.max(min[1], sectionY << 4); y <= Math.min(max[1], (sectionY << 4) + 15); y++) {
						for (int z = Math.max(min[2], chunkZ << 4); z <= Math.min(max[2], (chunkZ << 4) + 15); z++) {
							for (int x = Math.max(min[0], chunkX << 4); x <= Math.min(max[0], (chunkX << 4) + 15); x++) {
								if (section.getBlockState(x & 15, y & 15, z & 15).is(block)) {
									return true;
								}
							}
						}
					}
				}
			}
		}

		return false;
	}

	/** The core's terrain, {@code coreMin} being its minimum corner in the source. */
	private static PatchTerrain terrain(ServerLevel source, Chunks chunks, int[] coreMin, SceneBase.NaturalTerrain base) {
		int width = base.coreWidth();
		int height = base.height();
		int length = base.coreLength();
		byte[] flags = new byte[width * height * length];
		int[] surface = new int[width * length];
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < length; z++) {
			for (int x = 0; x < width; x++) {
				surface[z * width + x] = chunks.at(coreMin[0] + x, coreMin[2] + z).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, coreMin[0] + x, coreMin[2] + z) + 1 - coreMin[1];
				for (int y = 0; y < height; y++) {
					pos.set(coreMin[0] + x, coreMin[1] + y, coreMin[2] + z);
					BlockState state = chunks.state(pos.getX(), pos.getY(), pos.getZ());
					flags[(y * length + z) * width + x] = (byte) flags(source, pos, state);
				}
			}
		}

		return new PatchTerrain(width, height, length, flags, surface);
	}

	/** The {@link PatchTerrain} flags of {@code state} at {@code pos}; some shapes, such as bamboo's, depend on the position. */
	private static int flags(ServerLevel level, BlockPos pos, BlockState state) {
		int flags = 0;
		boolean fluid = !state.getFluidState().isEmpty();
		flags |= state.isAir() ? 0 : PatchTerrain.OCCUPIED;
		flags |= state.isSolidRender() ? PatchTerrain.OCCLUDES : 0;
		flags |= state.getCollisionShape(level, pos).isEmpty() ? 0 : PatchTerrain.COLLIDES;
		flags |= fluid ? PatchTerrain.FLUID : 0;
		flags |= state.isFaceSturdy(level, pos, Direction.UP) ? PatchTerrain.STURDY_TOP : 0;
		flags |= state.isAir() || (state.canBeReplaced() && !fluid) ? PatchTerrain.REPLACEABLE : 0;
		flags |= state.is(BlockTags.LEAVES) ? PatchTerrain.LEAVES : 0;
		return flags;
	}

	/** The loaded chunks of a horizontal box, generated on first use; reads go straight to sections. */
	private static final class Chunks {
		final ServerLevel level;
		private final int minChunkX;
		private final int minChunkZ;
		private final LevelChunk[][] chunks;

		Chunks(ServerLevel level, int minX, int minZ, int width, int length) {
			this.level = level;
			this.minChunkX = SectionPos.blockToSectionCoord(minX);
			this.minChunkZ = SectionPos.blockToSectionCoord(minZ);
			this.chunks = new LevelChunk[SectionPos.blockToSectionCoord(minX + width - 1) - this.minChunkX + 1][SectionPos.blockToSectionCoord(minZ + length - 1) - this.minChunkZ + 1];
		}

		LevelChunk at(int x, int z) {
			int i = SectionPos.blockToSectionCoord(x) - this.minChunkX;
			int j = SectionPos.blockToSectionCoord(z) - this.minChunkZ;
			LevelChunk chunk = this.chunks[i][j];
			if (chunk == null) {
				chunk = this.level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
				this.chunks[i][j] = chunk;
			}

			return chunk;
		}

		BlockState state(int x, int y, int z) {
			LevelChunk chunk = at(x, z);
			int index = this.level.getSectionIndex(y);
			if (index < 0 || index >= chunk.getSectionsCount()) {
				return Blocks.AIR.defaultBlockState();
			}

			return chunk.getSection(index).getBlockState(x & 15, y & 15, z & 15);
		}
	}
}
