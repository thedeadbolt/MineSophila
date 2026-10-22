package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.client.m0.M0Capture;
import io.github.thedeadbolt.honeyfactory.m0.M0Fixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Runs the M0 open, partial and full opaque-occlusion fixtures with improved transparency
 * on, at a small resolution and the proposed perspective default, repeating each frozen
 * capture ten times, and checks that capture refuses classic transparency.
 */
public class M0RenderingFeasibilityTest implements FabricClientGameTest {
	private static final int REPEATS = 10;
	private static final int[] SIZES = {64, 1280};

	@Override
	public void runTest(ClientGameTestContext context) {
		List<String> failures = new ArrayList<>();
		JsonArray runs = new JsonArray();
		Path root = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve(M0Capture.OUTPUT_DIRECTORY));

		try (TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> settings.setName(M0Fixture.WORLD_NAME)).create()) {
			context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
			for (M0Fixture.Variant variant : M0Fixture.Variant.values()) {
				String name = variant.name().toLowerCase(Locale.ROOT);
				world.getServer().runCommand("honeyfactory m0 fixture " + name + " @p");
				world.getConnection().waitForClientboundPackets();
				context.waitTicks(5);
				world.getConnection().waitForChunksRender();

				for (int size : SIZES) {
					String label = name + "_" + size;
					Long vramBefore = vramUsedBytes();
					M0Capture.Result result = capture(context, new M0Capture.Request(label, size, size, REPEATS));
					JsonObject run = result.summary().deepCopy();
					run.addProperty("variant", name);
					run.addProperty("vram_used_bytes_before", vramBefore);
					run.addProperty("vram_used_bytes_after", vramUsedBytes());
					runs.add(run);
					check(label, variant, result, failures);
				}
			}

			// ADR 0005: classic transparency writes the target's own depth, so capture must refuse it.
			context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(false));
			context.waitTicks(2);
			M0Capture.Result classic = capture(context, new M0Capture.Request("classic_rejected", SIZES[0], SIZES[0], 1));
			if (!M0Capture.IMPROVED_TRANSPARENCY_REQUIRED.equals(classic.failure())) {
				failures.add("classic transparency capture was not rejected: " + classic.failure());
			}

			context.runOnClient(minecraft -> minecraft.options.improvedTransparency().set(true));
		}

		JsonObject report = new JsonObject();
		report.add("runs", runs);
		JsonArray failureList = new JsonArray();
		failures.forEach(failureList::add);
		report.add("failures", failureList);
		try {
			Files.createDirectories(root);
			Files.writeString(root.resolve("report.json"), new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Could not write M0 report", e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M0 fixture failures:\n" + String.join("\n", failures));
		}
	}

	private static M0Capture.Result capture(ClientGameTestContext context, M0Capture.Request request) {
		context.runOnClient(minecraft -> M0Capture.submit(request));
		context.waitFor(minecraft -> M0Capture.lastResult() != null);
		return context.computeOnClient(minecraft -> M0Capture.lastResult());
	}

	private static void check(String label, M0Fixture.Variant variant, M0Capture.Result result, List<String> failures) {
		if (result.failure() != null) {
			failures.add(label + ": " + result.failure());
			return;
		}

		JsonObject summary = result.summary();
		if (!summary.get("masks_deterministic").getAsBoolean()) {
			failures.add(label + ": masks differ between repeats");
		}

		for (JsonElement element : summary.getAsJsonArray("captures")) {
			JsonObject capture = element.getAsJsonObject();
			int visible = capture.get("visible_pixels").getAsInt();
			int unoccluded = capture.get("unoccluded_pixels").getAsInt();
			boolean identical = capture.get("visible_mask_sha256").getAsString().equals(capture.get("unoccluded_mask_sha256").getAsString());
			boolean passed = switch (variant) {
				case OPEN -> visible > 0 && identical;
				case PARTIAL -> visible > 0 && visible < unoccluded;
				case FULL -> visible == 0 && unoccluded > 0;
			};
			if (!passed) {
				failures.add(label + ": visible=" + visible + " unoccluded=" + unoccluded + " identical=" + identical);
				return;
			}
		}
	}

	/** Device VRAM in use from amdgpu sysfs, or null where unavailable. */
	private static Long vramUsedBytes() {
		try (Stream<Path> cards = Files.list(Path.of("/sys/class/drm"))) {
			for (Path card : cards.filter(path -> path.getFileName().toString().matches("card\\d+")).sorted().toList()) {
				Path used = card.resolve("device/mem_info_vram_used");
				if (Files.isReadable(used)) {
					return Long.parseLong(Files.readString(used).trim());
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}

		return null;
	}
}
