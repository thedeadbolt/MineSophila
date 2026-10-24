package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.ScenePresets;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import io.github.thedeadbolt.honeyfactory.world.BlockStates;
import io.github.thedeadbolt.honeyfactory.world.NaturalPatches;
import io.github.thedeadbolt.honeyfactory.world.WorldGuard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Roadmap M6 exit gate for level 5, natural Minecraft terrain.
 *
 * <ul>
 *   <li>Copying a source patch leaves the source unchanged, matches the patch's content hash
 *       read from the source over the whole copy, reproduces the scene's core hash when the same
 *       patch is copied again, and relights the copy to the source's own light near the
 *       surface.</li>
 *   <li>A second scene on the same patch restores only the previous scene's cells.</li>
 *   <li>Level-5 smoke datasets in perspective and panorama mode reach their quotas, keep every
 *       source patch in one split and pass validation; outputs stay under
 *       {@code build/m6-natural} for the outside validator.</li>
 *   <li>{@code /honeyfactory_env reset preset level5} builds a natural scene.</li>
 *   <li>Preparation, copy, lighting and readiness are timed for several context paddings, with
 *       patch reuse, to choose the defaults.</li>
 * </ul>
 */
public class M6NaturalTest implements FabricClientGameTest {
	private static final int RENDER_DISTANCE = 12;
	private static final long SEED = 2036;
	private static final int TIMEOUT_TICKS = 20 * 60 * 60;
	/** Light is compared this many blocks around each core column's surface. */
	private static final int LIGHT_BAND = 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		Path output = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m6-natural");
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		context.runOnClient(minecraft -> minecraft.options.renderDistance().set(RENDER_DISTANCE));
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		summary.add("patch", patchMechanics(context, output.resolve("review"), failures));
		summary.add("void_boundary", voidBoundary(context, failures));
		summary.add("level5_perspective", dataset(context, "honey-factory-m6-l5-perspective", config("m6-l5-perspective", 6, 2, """
			{"width": 640, "height": 480}""", output.resolve("level5-perspective")), failures));
		summary.add("level5_panorama", dataset(context, "honey-factory-m6-l5-panorama", config("m6-l5-panorama", 4, 2, """
			{"mode": "PANORAMA_310", "width": 1240, "height": 640, "cube_face_size": 512}""", output.resolve("level5-panorama")), failures));
		summary.add("preset_command", presetCommand(context, failures));
		summary.add("benchmark", benchmark(context, failures));
		try {
			Files.createDirectories(output);
			Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M6 natural failures:\n" + String.join("\n", failures));
		}
	}

	private static ResolvedConfig level5(SceneBase.NaturalTerrain base) {
		return ConfigResolver.resolve("""
			{"dataset": {"name": "m6-natural", "seed": %d, "difficulty": 5}, "scene": {"base": %s}, "output": {"root": "/tmp/honey-factory-unused"}}
			""".formatted(SEED, base.toJson()));
	}

	private static PreparedPatch prepare(TestSingleplayerContext world, SceneBase.NaturalTerrain base, int patch) {
		return world.getServer().computeOnServer(server -> NaturalPatches.prepare(server, new NaturalPatches.Request(SEED, base, patch, "minecraft:honey_block")));
	}

	/** Scene {@code index} on {@code patch} and its first placeable viewpoint. */
	private record Built(GeneratedScene scene, ViewpointPlan viewpoint) {
	}

	private static Built build(NaturalGenerator generator, PreparedPatch patch, int index, boolean present) {
		for (int attempt = 0; ; attempt++) {
			GeneratedScene scene = generator.scene(SceneSeeds.derive(SEED, index, attempt), present, patch);
			for (int draw = 0; draw < 200; draw++) {
				if (generator.viewpoint(scene, 0, draw) instanceof ViewpointCandidate.Placeable placeable) {
					return new Built(scene, placeable.plan());
				}
			}
		}
	}

	private static EnvironmentRuntime.SceneState reset(ClientGameTestContext context, ArenaLayout arena, Built built) {
		return GameTestSupport.await(context, runtime -> runtime.reset(arena, built.scene().plan(), built.viewpoint().pose(arena)), 20 * 60 * 10);
	}

	private static JsonObject patchMechanics(ClientGameTestContext context, Path review, List<String> failures) {
		String name = "honey-factory-m6-natural";
		SceneBase.NaturalTerrain base = SceneBase.NaturalTerrain.DEFAULT;
		ArenaLayout arena = ArenaLayout.natural(base);
		NaturalGenerator generator = new NaturalGenerator(level5(base));
		JsonObject summary = new JsonObject();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			long started = System.nanoTime();
			PreparedPatch patch = prepare(world, base, 0);
			summary.addProperty("prepare_seconds", (System.nanoTime() - started) / 1e9);
			summary.addProperty("patch_id", patch.patch().patchId());
			summary.add("rejected_draws", new GsonBuilder().create().toJsonTree(patch.patch().rejectedDraws()));
			summary.addProperty("target_sites", patch.terrain().targetSites().size());
			BlockBox source = new BlockBox(patch.patch().sourceMin()[0], patch.patch().sourceMin()[1], patch.patch().sourceMin()[2],
				patch.patch().sourceMax()[0], patch.patch().sourceMax()[1], patch.patch().sourceMax()[2]);
			String sourceBefore = world.getServer().computeOnServer(server -> BlockStates.layoutSha256(server.overworld(), source));

			Built absent = build(generator, patch, 0, false);
			EnvironmentRuntime.SceneState first = reset(context, arena, absent);
			ArenaExecutor.ResetResult copied = context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastReset());
			if (copied == null || copied.copy() == null) {
				failures.add(name + ": the first natural reset did not copy its patch");
			} else {
				summary.add("copy", copyStats(copied.copy()));
			}

			summary.addProperty("layout_sha256", first.layoutSha256());
			summary.addProperty("client_ready_ticks", first.settledTicks());
			String whole = world.getServer().computeOnServer(server -> BlockStates.layoutSha256(WorldGuard.arena(server), arena.resetVolume()));
			if (!patch.patch().contentSha256().equals(whole)) {
				failures.add(name + ": the copied, unchanged patch hashes to " + whole + ", not its content hash " + patch.patch().contentSha256());
			}

			summary.add("light", world.getServer().computeOnServer(server -> compareLight(server, arena, patch)));
			JsonObject light = summary.getAsJsonObject("light");
			if (light.get("sky_mismatches").getAsInt() + light.get("block_mismatches").getAsInt() > 0) {
				failures.add(name + ": relit copy differs from the source's light near the surface: " + light);
			}

			Built present = build(generator, patch, 1, true);
			EnvironmentRuntime.SceneState second = reset(context, arena, present);
			ArenaExecutor.ResetResult reused = context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastReset());
			if (reused == null || reused.copy() != null) {
				failures.add(name + ": a second scene on the same patch copied it again");
			}

			summary.addProperty("reuse_client_ready_ticks", second.settledTicks());
			try {
				CaptureDebugFiles.write(review.resolve("patch0-scene1"), GameTestSupport.await(context, runtime -> runtime.observe(new PanoramicProjection(1240, 640, 512))).capture().images());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			PreparedPatch other = prepare(world, base, 1);
			reset(context, arena, build(generator, other, 2, true));
			EnvironmentRuntime.SceneState again = reset(context, arena, absent);
			if (!first.layoutSha256().equals(again.layoutSha256())) {
				failures.add(name + ": copying patch 0 again gave layout " + again.layoutSha256() + " instead of " + first.layoutSha256());
			}

			String sourceAfter = world.getServer().computeOnServer(server -> BlockStates.layoutSha256(server.overworld(), source));
			if (!sourceBefore.equals(sourceAfter)) {
				failures.add(name + ": the source terrain changed from " + sourceBefore + " to " + sourceAfter);
			}

			summary.addProperty("source_unchanged", sourceBefore.equals(sourceAfter));
		}

		return summary;
	}

	/**
	 * Sky and block light at every core cell within {@value #LIGHT_BAND} blocks of its column's
	 * surface, in the source and in the copy.
	 */
	private static JsonObject compareLight(MinecraftServer server, ArenaLayout arena, PreparedPatch patch) {
		ServerLevel source = server.overworld();
		ServerLevel copy = WorldGuard.arena(server);
		int[] core = arena.core().min();
		SceneBase.NaturalTerrain base = SceneBase.NaturalTerrain.DEFAULT;
		int[] sourceCore = {patch.patch().sourceMin()[0] + base.contextPadding(), patch.patch().sourceMin()[1], patch.patch().sourceMin()[2] + base.contextPadding()};
		int compared = 0;
		int sky = 0;
		int block = 0;
		for (int z = 0; z < base.coreLength(); z++) {
			for (int x = 0; x < base.coreWidth(); x++) {
				int surface = patch.terrain().surface(x, z);
				for (int y = Math.max(0, surface - LIGHT_BAND); y <= Math.min(base.height() - 1, surface + LIGHT_BAND); y++) {
					BlockPos from = new BlockPos(sourceCore[0] + x, sourceCore[1] + y, sourceCore[2] + z);
					BlockPos to = new BlockPos(core[0] + x, core[1] + y, core[2] + z);
					compared++;
					sky += source.getBrightness(LightLayer.SKY, from) == copy.getBrightness(LightLayer.SKY, to) ? 0 : 1;
					block += source.getBrightness(LightLayer.BLOCK, from) == copy.getBrightness(LightLayer.BLOCK, to) ? 0 : 1;
				}
			}
		}

		JsonObject result = new JsonObject();
		result.addProperty("cells", compared);
		result.addProperty("sky_mismatches", sky);
		result.addProperty("block_mismatches", block);
		return result;
	}

	/**
	 * With the default padding, every chunk the client renders around a camera anywhere in
	 * the core lies inside the copied region, so no void edge is drawn at the render
	 * distance: checked at the four core corners, the cameras farthest from the centre.
	 */
	private static JsonObject voidBoundary(ClientGameTestContext context, List<String> failures) {
		int distance = context.computeOnClient(minecraft -> minecraft.options.getEffectiveRenderDistance());
		ArenaLayout arena = ArenaLayout.natural(SceneBase.NaturalTerrain.DEFAULT);
		int[] copied = arena.resetVolume().chunkRange();
		BlockBox core = arena.core();
		int outside = 0;
		for (int cornerX : new int[] {core.minX(), core.maxX()}) {
			for (int cornerZ : new int[] {core.minZ(), core.maxZ()}) {
				int centreX = cornerX >> 4;
				int centreZ = cornerZ >> 4;
				for (int x = centreX - distance - 2; x <= centreX + distance + 2; x++) {
					for (int z = centreZ - distance - 2; z <= centreZ + distance + 2; z++) {
						boolean rendered = net.minecraft.server.level.ChunkTrackingView.isInViewDistance(centreX, centreZ, distance, x, z);
						if (rendered && (x < copied[0] || x > copied[2] || z < copied[1] || z > copied[3])) {
							outside++;
						}
					}
				}
			}
		}

		if (outside > 0) {
			failures.add("void boundary: " + outside + " rendered chunks lie outside the copied region at render distance " + distance);
		}

		JsonObject summary = new JsonObject();
		summary.addProperty("render_distance", distance);
		summary.addProperty("context_padding", SceneBase.NaturalTerrain.DEFAULT.contextPadding());
		summary.addProperty("rendered_chunks_outside_copy", outside);
		return summary;
	}

	private static JsonObject copyStats(NaturalPatches.CopyStats stats) {
		JsonObject json = new JsonObject();
		json.addProperty("chunks", stats.chunks());
		json.addProperty("sections_copied", stats.sectionsCopied());
		json.addProperty("sections_cleared", stats.sectionsCleared());
		json.addProperty("block_entities", stats.blockEntities());
		json.addProperty("copy_seconds", stats.copyNanos() / 1e9);
		json.addProperty("light_seconds", stats.lightNanos() / 1e9);
		return json;
	}

	private static JsonObject dataset(ClientGameTestContext context, String name, String config, List<String> failures) {
		DatasetGenerator.Result result;
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			result = GameTestSupport.generate(context, name, config, progress -> false, TIMEOUT_TICKS);
		}

		DatasetManifest manifest = result.manifest();
		JsonObject summary = new JsonObject();
		summary.addProperty("dataset_id", manifest.datasetId());
		summary.addProperty("lifecycle", manifest.lifecycle().jsonName());
		summary.addProperty("failure", result.failure());
		summary.addProperty("accepted_scenes", manifest.counts().acceptedScenes());
		summary.addProperty("attempted_scenes", manifest.counts().attemptedScenes());
		summary.addProperty("accepted_samples", manifest.counts().acceptedSamples());
		summary.add("rejection_reasons", new GsonBuilder().create().toJsonTree(manifest.counts().rejectionReasons()));
		if (manifest.completedAtUtc() != null) {
			double seconds = java.time.Duration.between(manifest.createdAtUtc(), manifest.completedAtUtc()).toMillis() / 1000.0;
			summary.addProperty("seconds", seconds);
			summary.addProperty("seconds_per_sample", seconds / Math.max(1, manifest.counts().acceptedSamples()));
		}

		if (result.failure() != null) {
			failures.add(name + ": generation failed: " + result.failure() + " " + result.validationErrors());
			return summary;
		}

		DatasetValidator.Report report;
		try {
			report = DatasetValidator.validate(result.directory());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!report.passed()) {
			failures.add(name + ": validation errors " + report.errors());
		}

		Map<String, List<String>> splitsByPatch = report.scenes().stream().collect(Collectors.groupingBy(scene -> scene.sourcePatch().patchId(),
			Collectors.mapping(scene -> scene.split().jsonName(), Collectors.toList())));
		splitsByPatch.forEach((patch, splits) -> {
			if (splits.stream().distinct().count() != 1) {
				failures.add(name + ": source patch " + patch + " reaches splits " + splits);
			}
		});
		summary.add("splits_by_patch", new GsonBuilder().create().toJsonTree(splitsByPatch));
		summary.add("biomes", new GsonBuilder().create().toJsonTree(report.samples().stream().map(sample -> sample.conditions().biome()).distinct().sorted().toList()));
		summary.add("visibility_ratios", new GsonBuilder().create().toJsonTree(report.samples().stream()
			.map(SampleRecord::target)
			.filter(target -> target.targetPresent() && target.visibilityRatio() != null)
			.map(SampleRecord.Target::visibilityRatio)
			.sorted()
			.toList()));
		JsonArray environments = new JsonArray();
		for (SceneRecord scene : report.scenes()) {
			environments.add(scene.environment().timeOfDayTicks() + " " + scene.environment().weather().jsonName());
		}

		summary.add("environments", environments);
		return summary;
	}

	private static JsonObject presetCommand(ClientGameTestContext context, List<String> failures) {
		String name = "honey-factory-m6-l5-preview";
		JsonObject summary = new JsonObject();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("honeyfactory_env reset preset level5 7 0"));
			context.waitFor(minecraft -> EnvironmentRuntime.get().scene() != null && EnvironmentRuntime.get().status().current() == null, 20 * 60 * 10);
			String patch = context.computeOnClient(minecraft -> EnvironmentRuntime.get().scene().patch() == null ? null : EnvironmentRuntime.get().scene().patch().patchId());
			if (patch == null) {
				failures.add(name + ": the level-5 preset did not build a natural scene");
			}

			summary.addProperty("patch_id", patch);
		}

		return summary;
	}

	/** Each padding in its own world: prepare and copy one patch, then reuse it once. */
	private static JsonArray benchmark(ClientGameTestContext context, List<String> failures) {
		JsonArray rows = new JsonArray();
		for (int padding : new int[] {64, 128, 192, 256}) {
			String name = "honey-factory-m6-bench-" + padding;
			SceneBase.NaturalTerrain base = new SceneBase.NaturalTerrain(32, 32, 128, padding, 4, 256);
			ArenaLayout arena = ArenaLayout.natural(base);
			NaturalGenerator generator = new NaturalGenerator(level5(base));
			JsonObject row = new JsonObject();
			row.addProperty("context_padding", padding);
			try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
				GameTestSupport.claim(world, name);
				long before = worldBytes(world);
				long started = System.nanoTime();
				PreparedPatch patch = prepare(world, base, 0);
				row.addProperty("prepare_seconds", (System.nanoTime() - started) / 1e9);
				row.add("rejected_draws", new GsonBuilder().create().toJsonTree(patch.patch().rejectedDraws()));
				started = System.nanoTime();
				EnvironmentRuntime.SceneState copied = reset(context, arena, build(generator, patch, 0, true));
				row.addProperty("copy_reset_seconds", (System.nanoTime() - started) / 1e9);
				row.addProperty("copy_client_ready_ticks", copied.settledTicks());
				ArenaExecutor.ResetResult result = context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastReset());
				if (result != null && result.copy() != null) {
					row.add("copy", copyStats(result.copy()));
				}

				started = System.nanoTime();
				EnvironmentRuntime.SceneState reused = reset(context, arena, build(generator, patch, 1, true));
				row.addProperty("reuse_reset_seconds", (System.nanoTime() - started) / 1e9);
				row.addProperty("reuse_client_ready_ticks", reused.settledTicks());
				world.getServer().runOnServer(server -> server.saveEverything(true, true, true));
				row.addProperty("world_bytes_added", worldBytes(world) - before);
			} catch (RuntimeException e) {
				failures.add(name + ": " + e);
			}

			rows.add(row);
		}

		return rows;
	}

	private static long worldBytes(TestSingleplayerContext world) {
		Path root = world.getServer().computeOnServer(server -> server.getWorldPath(LevelResource.ROOT));
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String config(String name, int scenes, int scenesPerPatch, String capture, Path root) {
		return """
			{
			  "dataset": {"name": "%s", "seed": %d, "difficulty": 5},
			  "scene": {"base": {"type": "natural_patch", "scenes_per_patch": %d}},
			  "viewpoints": {"count": 4},
			  "capture": %s,
			  "quotas": {"accepted_scenes": %d},
			  "splits": {"train": 0.34, "validation": 0.33, "test": 0.33},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1073741824}
			}
			""".formatted(name, SEED, scenesPerPatch, capture, scenes, root.toString().replace("\\", "\\\\"));
	}

	static {
		// The level-5 preset is the default natural base this gate measures.
		if (!ScenePresets.spec("level5").base().equals(SceneBase.NaturalTerrain.DEFAULT)) {
			throw new IllegalStateException("The level-5 preset no longer uses the default natural base");
		}
	}
}
