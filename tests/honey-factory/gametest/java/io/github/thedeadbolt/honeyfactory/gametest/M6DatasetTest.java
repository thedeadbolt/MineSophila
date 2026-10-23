package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.scene.BlockPalettes;
import io.github.thedeadbolt.honeyfactory.scene.BlockShapes;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerators;
import io.github.thedeadbolt.honeyfactory.world.BlockStates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Roadmap M6 exit gate for the generated levels.
 *
 * <ul>
 *   <li>Every block state level 4 places is a valid Minecraft state in canonical form, and
 *       {@link BlockShapes} agrees with Minecraft: full cubes occlude, slabs and straight
 *       stairs match their outline shape exactly, fence boxes lie inside the fence's
 *       outline, and leaves and plants do not occlude.</li>
 *   <li>Level-4 smoke datasets in perspective and panorama mode reach their quotas, keep
 *       every positive sample at least the configured visible fraction and pass validation.
 *       Outputs stay under {@code build/m6-datasets} for the outside validator.</li>
 * </ul>
 */
public class M6DatasetTest implements FabricClientGameTest {
	private static final int SCENES = 8;
	private static final int VIEWPOINTS = 4;
	private static final int TIMEOUT_TICKS = 20 * 60 * 30;
	private static final int PALETTE_SCENES = 300;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		Path output = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m6-datasets");
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		summary.add("level4_palette", level4Palette(context, failures));
		summary.add("level4_perspective", smokeDataset(context, "honey-factory-m6-l4-perspective", config("m6-l4-perspective", 4, """
			{"width": 640, "height": 480}""", output.resolve("level4-perspective")), failures));
		summary.add("level4_panorama", smokeDataset(context, "honey-factory-m6-l4-panorama", config("m6-l4-panorama", 4, """
			{"mode": "PANORAMA_310", "width": 1240, "height": 640, "cube_face_size": 512}""", output.resolve("level4-panorama")), failures));
		try {
			Files.createDirectories(output);
			Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M6 dataset failures:\n" + String.join("\n", failures));
		}
	}

	/** Checks every state level 4 places over {@value #PALETTE_SCENES} scenes against Minecraft's own blocks. */
	private static JsonObject level4Palette(ClientGameTestContext context, List<String> failures) {
		ResolvedConfig config = ConfigResolver.resolve("""
			{"dataset": {"name": "m6-palette", "seed": 7, "difficulty": 4}, "output": {"root": "/tmp/honey-factory-unused"}}
			""");
		TreeSet<String> states = new TreeSet<>();
		for (int index = 0; index < PALETTE_SCENES; index++) {
			for (Placement placement : SceneGenerators.forConfig(config).scene(SceneSeeds.derive(config.seed(), index, 0), false).plan().delta()) {
				states.add(placement.state());
			}
		}

		for (BlockPalettes.Family family : BlockPalettes.FAMILIES) {
			for (String facing : List.of("north", "east", "south", "west")) {
				states.add(family.stair(facing, false));
				states.add(family.stair(facing, true));
			}

			for (String type : List.of("bottom", "top", "double")) {
				states.add(family.slab(type));
			}
		}

		List<String> problems = new ArrayList<>();
		String name = "honey-factory-m6-palette";
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			world.getServer().runOnServer(server -> {
				HolderLookup<Block> blocks = server.registryAccess().lookupOrThrow(Registries.BLOCK);
				for (String canonical : states) {
					BlockState state;
					try {
						state = BlockStates.parse(canonical, blocks);
					} catch (IllegalArgumentException e) {
						problems.add(canonical + ": " + e.getMessage());
						continue;
					}

					if (!BlockStates.canonical(state).equals(canonical)) {
						problems.add(canonical + ": canonical form is " + BlockStates.canonical(state));
					}

					String shape = shapeProblem(canonical, state);
					if (shape != null) {
						problems.add(canonical + ": " + shape);
					}
				}
			});
		}

		problems.forEach(problem -> failures.add("level-4 palette: " + problem));
		JsonObject summary = new JsonObject();
		summary.addProperty("states", states.size());
		summary.addProperty("problems", problems.size());
		return summary;
	}

	private static String shapeProblem(String canonical, BlockState state) {
		VoxelShape outline = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (BlockShapes.seeThrough(canonical)) {
			return state.canOcclude() ? "see-through in the shape table but occludes in Minecraft" : null;
		}

		if (BlockShapes.fullCube(canonical)) {
			return Block.isShapeFullBlock(outline) && state.canOcclude() ? null : "a full cube in the shape table but not in Minecraft";
		}

		boolean fence = BlockShapes.blockId(canonical).endsWith("_fence");
		List<BlockShapes.Box> boxes = BlockShapes.boxes(canonical);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					boolean ours = false;
					for (BlockShapes.Box box : boxes) {
						ours |= x >= box.minX() && x < box.maxX() && y >= box.minY() && y < box.maxY() && z >= box.minZ() && z < box.maxZ();
					}

					boolean minecraft = contains(outline, (x + 0.5) / 16, (y + 0.5) / 16, (z + 0.5) / 16);
					if (fence ? ours && !minecraft : ours != minecraft) {
						return "shape differs at sixteenth (" + x + ", " + y + ", " + z + ")";
					}
				}
			}
		}

		return null;
	}

	private static boolean contains(VoxelShape shape, double x, double y, double z) {
		for (AABB box : shape.toAabbs()) {
			if (box.contains(x, y, z)) {
				return true;
			}
		}

		return false;
	}

	private static JsonObject smokeDataset(ClientGameTestContext context, String name, String config, List<String> failures) {
		DatasetGenerator.Result result;
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			result = GameTestSupport.generate(context, name, config, progress -> false, TIMEOUT_TICKS);
		}

		JsonObject summary = summary(result);
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

		DatasetManifest manifest = report.manifest();
		long present = report.scenes().stream().filter(scene -> scene.target().present()).count();
		if (manifest.lifecycle() != DatasetManifest.Lifecycle.COMPLETE || report.scenes().size() != SCENES || report.samples().size() != SCENES * VIEWPOINTS
			|| present != manifest.targetPresentScenes()) {
			failures.add(name + ": quotas not met: " + manifest.counts());
		}

		double minRatio = manifest.visibility().minRatio();
		for (SampleRecord sample : report.samples()) {
			SampleRecord.Target target = sample.target();
			if (target.targetPresent() && (target.visibilityRatio() == null || target.visibilityRatio() < minRatio)) {
				failures.add(name + ": sample " + sample.sampleId() + " is below the minimum visible fraction: " + target.visibilityRatio());
			}
		}

		Map<String, Long> settings = report.scenes().stream()
			.map(SceneRecord::generation)
			.map(generation -> generation.parameters().layout().get("setting").getAsString())
			.collect(Collectors.groupingBy(setting -> setting, Collectors.counting()));
		summary.add("settings", new GsonBuilder().create().toJsonTree(settings));
		summary.add("visibility_ratios", new GsonBuilder().create().toJsonTree(report.samples().stream()
			.map(SampleRecord::target)
			.filter(target -> target.visibilityRatio() != null && target.targetPresent())
			.map(SampleRecord.Target::visibilityRatio)
			.sorted()
			.toList()));
		return summary;
	}

	private static JsonObject summary(DatasetGenerator.Result result) {
		DatasetManifest manifest = result.manifest();
		DatasetManifest.Counts counts = manifest.counts();
		JsonObject summary = new JsonObject();
		summary.addProperty("dataset_id", manifest.datasetId());
		summary.addProperty("directory", result.directory().getFileName().toString());
		summary.addProperty("lifecycle", manifest.lifecycle().jsonName());
		summary.addProperty("failure", result.failure());
		summary.addProperty("accepted_scenes", counts.acceptedScenes());
		summary.addProperty("attempted_scenes", counts.attemptedScenes());
		summary.addProperty("accepted_samples", counts.acceptedSamples());
		summary.add("rejection_reasons", new GsonBuilder().create().toJsonTree(counts.rejectionReasons()));
		if (manifest.completedAtUtc() != null) {
			summary.addProperty("seconds", java.time.Duration.between(manifest.createdAtUtc(), manifest.completedAtUtc()).toMillis() / 1000.0);
		}

		return summary;
	}

	private static String config(String name, int level, String capture, Path root) {
		return """
			{
			  "dataset": {"name": "%s", "seed": %d, "difficulty": %d},
			  "viewpoints": {"count": %d},
			  "capture": %s,
			  "quotas": {"accepted_scenes": %d},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1073741824}
			}
			""".formatted(name, 2030 + level, level, VIEWPOINTS, capture, SCENES, root.toString().replace("\\", "\\\\"));
	}
}
