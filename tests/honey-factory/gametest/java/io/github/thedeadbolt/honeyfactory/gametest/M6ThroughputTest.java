package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PanoramicProjection;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetRuns;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.generation.GenerationTimings;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.RecipeGenerator;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerator;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.world.NaturalPatches;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Batched capture gives the same images, and where dataset generation spends its time.
 *
 * <ul>
 *   <li>Viewpoints captured in one batch, the player moved by the client, match captures
 *       after a server-side move and settle, in a level-4 and a clear level-5 scene, in
 *       perspective and panorama mode: masks exactly, RGB within what two ordinary captures
 *       of the scene a few ticks apart differ by, which is nothing in a static scene.</li>
 *   <li>Levels 4 and 5 in perspective and panorama mode, 8 scenes of 8 viewpoints each at
 *       the default capture sizes, with a per-phase breakdown written to
 *       {@code build/m6-throughput/summary.json}.</li>
 * </ul>
 */
public class M6ThroughputTest implements FabricClientGameTest {
	private static final int TIMEOUT_TICKS = 20 * 60 * 60;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		Path output = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m6-throughput");
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		context.runOnClient(minecraft -> minecraft.options.renderDistance().set(12));
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		summary.add("batched_capture", batchedCaptureMatches(context, failures));
		for (int level : new int[] {4, 5}) {
			for (String capture : new String[] {"{}", "{\"mode\": \"PANORAMA_310\"}"}) {
				String name = "honey-factory-m6-throughput-l" + level + (capture.length() > 2 ? "-panorama" : "-perspective");
				summary.add(name, run(context, name, level, capture, output.resolve(name), failures));
			}
		}

		try {
			Files.createDirectories(output);
			Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M6 throughput failures:\n" + String.join("\n", failures));
		}
	}

	/**
	 * Captures each of four viewpoints of a level-4 and a clear level-5 scene twice: by
	 * moving the player on the server and waiting for the client to settle, and in one batch
	 * with the player moved by the client. RGB and both masks must match byte for byte.
	 */
	private static JsonObject batchedCaptureMatches(ClientGameTestContext context, List<String> failures) {
		JsonObject summary = new JsonObject();
		String name = "honey-factory-m6-batched-capture";
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			ResolvedConfig level4 = ConfigResolver.resolve("""
				{"dataset": {"name": "batched", "seed": 5, "difficulty": 4}, "capture": {"width": 640, "height": 480}, "output": {"root": "/tmp/honey-factory-unused"}}
				""");
			RecipeGenerator platform = new RecipeGenerator(level4);
			GeneratedScene scene = platform.scene(SceneSeeds.derive(5, 0, 0), true);
			summary.add("level4", compare(context, ArenaLayout.of(level4.arena()), scene, viewpoints(platform, scene), new PerspectiveProjection(640, 480), failures));

			SceneSpec clear = SceneRecipe.of("level5").lighting(6000, Weather.CLEAR).build();
			NaturalGenerator natural = new NaturalGenerator(clear, List.of("minecraft:honey_block"), level4.viewpoints(), level4.projection());
			PreparedPatch patch = world.getServer().computeOnServer(server -> NaturalPatches.prepare(server, new NaturalPatches.Request(5, natural.base(), 0, "minecraft:honey_block")));
			GeneratedScene terrain = natural.scene(SceneSeeds.derive(5, 0, 0), true, patch);
			summary.add("level5", compare(context, ArenaLayout.natural(natural.base()), terrain, viewpoints(natural, terrain), new PerspectiveProjection(640, 480), failures));
			summary.add("level5_panorama", compare(context, ArenaLayout.natural(natural.base()), terrain, viewpoints(natural, terrain).subList(0, 2), new PanoramicProjection(1240, 640, 512), failures));
		}

		return summary;
	}

	private static List<ViewpointPlan> viewpoints(SceneGenerator generator, GeneratedScene scene) {
		List<ViewpointPlan> plans = new ArrayList<>();
		for (int index = 0; plans.size() < 4 && index < 8; index++) {
			for (int attempt = 0; attempt < 50; attempt++) {
				if (generator.viewpoint(scene, index, attempt) instanceof ViewpointCandidate.Placeable placeable) {
					plans.add(placeable.plan());
					break;
				}
			}
		}

		return plans;
	}

	/**
	 * Captures every plan both ways. The masks must match. RGB may differ by no more than two
	 * captures of the first pose the usual way, 20 ticks apart, differ from each other, which
	 * is nothing in a static scene. On terrain, animated textures such as distant water change
	 * between any two captures, and how much water a patch shows varies with the world. There,
	 * bytes that also change when the pose is captured again 20 and 31 ticks later (two other
	 * animation frames) are not counted, and up to 0.1 % of the rest may differ.
	 */
	private static JsonObject compare(ClientGameTestContext context, ArenaLayout arena, GeneratedScene scene, List<ViewpointPlan> plans, CaptureProjection projection, List<String> failures) {
		List<CameraPose> poses = plans.stream().map(plan -> plan.pose(arena)).toList();
		GameTestSupport.await(context, runtime -> runtime.reset(arena, scene.plan(), poses.getFirst()), 20 * 60 * 10);
		CapturedImages before = GameTestSupport.await(context, runtime -> runtime.observe(projection)).capture().images();
		context.waitTicks(20);
		CapturedImages later = GameTestSupport.await(context, runtime -> runtime.observe(projection)).capture().images();
		List<EnvironmentRuntime.Observation> batched = GameTestSupport.await(context, runtime -> runtime.observeAll(projection, poses));
		JsonObject summary = new JsonObject();
		int baseline = differing(before.rgb(), later.rgb());
		int allowed = arena.natural() ? Math.max(baseline, before.rgb().length / 1000) : baseline;
		summary.addProperty("baseline_rgb_bytes_differing", baseline);
		JsonArray rgb = new JsonArray();
		int maskDifferences = 0;
		for (int i = 0; i < poses.size(); i++) {
			CameraPose pose = poses.get(i);
			GameTestSupport.await(context, runtime -> runtime.setViewpoint(pose));
			CapturedImages placed = GameTestSupport.await(context, runtime -> runtime.observe(projection)).capture().images();
			CapturedImages moved = batched.get(i).capture().images();
			boolean masks = java.util.Arrays.equals(placed.visibleMask(), moved.visibleMask()) && java.util.Arrays.equals(placed.unoccludedMask(), moved.unoccludedMask());
			int bytes = differing(placed.rgb(), moved.rgb());
			if (arena.natural() && bytes > allowed) {
				context.waitTicks(20);
				byte[] later20 = GameTestSupport.await(context, runtime -> runtime.observe(projection)).capture().images().rgb();
				context.waitTicks(11);
				byte[] later31 = GameTestSupport.await(context, runtime -> runtime.observe(projection)).capture().images().rgb();
				bytes = differingWhereStill(placed.rgb(), moved.rgb(), later20, later31);
			}

			rgb.add(bytes);
			maskDifferences += masks ? 0 : 1;
			if (!masks || bytes > allowed) {
				try {
					Path debug = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m6-throughput/batched-debug/" + projection.mode() + "-" + i);
					io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles.write(debug.resolve("placed"), placed);
					io.github.thedeadbolt.honeyfactory.capture.CaptureDebugFiles.write(debug.resolve("batched"), moved);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}

				failures.add("batched capture of " + pose + " through " + projection.mode() + " differs: " + bytes + " RGB bytes, masks equal " + masks);
			}
		}

		summary.add("rgb_bytes_differing", rgb);
		summary.addProperty("mask_differences", maskDifferences);
		return summary;
	}

	/** Bytes where {@code a} and {@code b} differ but {@code a} matches both later captures of its pose. */
	private static int differingWhereStill(byte[] a, byte[] b, byte[] later, byte[] latest) {
		int count = 0;
		for (int i = 0; i < a.length; i++) {
			count += a[i] != b[i] && a[i] == later[i] && a[i] == latest[i] ? 1 : 0;
		}

		return count;
	}

	private static int differing(byte[] a, byte[] b) {
		int count = 0;
		for (int i = 0; i < a.length; i++) {
			count += a[i] == b[i] ? 0 : 1;
		}

		return count;
	}

	private static JsonObject run(ClientGameTestContext context, String name, int level, String capture, Path root, List<String> failures) {
		String config = """
			{
			  "dataset": {"name": "%s", "seed": 77, "difficulty": %d},
			  "capture": %s,
			  "quotas": {"accepted_scenes": 8},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1073741824}
			}
			""".formatted(name.replace("honey-factory-", ""), level, capture, root.toString().replace("\\", "\\\\"));
		DatasetGenerator.Result result;
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			result = GameTestSupport.generate(context, name, config, progress -> false, TIMEOUT_TICKS);
		}

		JsonObject summary = new JsonObject();
		if (result.failure() != null) {
			failures.add(name + ": " + result.failure());
		}

		int samples = result.manifest().counts().acceptedSamples();
		double seconds = java.time.Duration.between(result.manifest().createdAtUtc(), result.manifest().completedAtUtc()).toMillis() / 1000.0;
		summary.addProperty("samples", samples);
		summary.addProperty("seconds", seconds);
		summary.addProperty("seconds_per_sample", seconds / Math.max(1, samples));
		JsonObject phases = new JsonObject();
		for (Map.Entry<GenerationTimings.Phase, GenerationTimings.Total> entry : context.computeOnClient(minecraft -> DatasetRuns.lastTimings()).totals().entrySet()) {
			JsonObject phase = new JsonObject();
			phase.addProperty("count", entry.getValue().count());
			phase.addProperty("seconds", entry.getValue().seconds());
			phase.addProperty("per_sample", entry.getValue().seconds() / Math.max(1, samples));
			phases.add(entry.getKey().name().toLowerCase(java.util.Locale.ROOT), phase);
		}

		summary.add("phases", phases);
		double[] captures = context.computeOnClient(minecraft -> DatasetRuns.lastCaptureTotals());
		JsonObject inside = new JsonObject();
		inside.addProperty("captures", captures[0]);
		inside.addProperty("render_seconds", captures[1]);
		inside.addProperty("readback_seconds", captures[2]);
		inside.addProperty("reprojection_seconds", captures[3]);
		summary.add("inside_observe", inside);
		summary.add("rejection_reasons", new GsonBuilder().create().toJsonTree(result.manifest().counts().rejectionReasons()));
		return summary;
	}
}
