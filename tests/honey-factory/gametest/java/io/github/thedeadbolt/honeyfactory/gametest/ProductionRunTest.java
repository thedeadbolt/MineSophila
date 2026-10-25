package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Generates the phase-1 production datasets the specification sets out under generation
 * quotas: one dataset per level and capture mode at the default resolutions, with 8
 * viewpoints and the default 80/20 balance, weighted towards the harder levels.
 *
 * <p>Outputs go to {@code /mnt/fastvault/minesophila/datasets/phase1/} (set
 * {@code -Dhoneyfactory.productionRoot}), with metrics and a copy of the dataset world as in
 * {@link AcceptanceRun}. {@code -Dhoneyfactory.productionRuns} selects datasets by name part
 * (comma-separated, such as {@code perspective}, {@code panorama} or {@code l5-panorama});
 * empty runs all ten. Each run uses its own world. Runs only when named
 * ({@code -Dhoneyfactory.gametests=ProductionRun}).
 */
public class ProductionRunTest implements FabricClientGameTest {
	/** Accepted scenes per level; with 8 viewpoints, 2,000 to 20,000 images. */
	private static final int[] SCENES = {250, 625, 1250, 1250, 2500};
	private static final int TIMEOUT_TICKS = 20 * 60 * 60 * 12;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		List<String> selected = List.of(System.getProperty("honeyfactory.productionRuns", "").split(",")).stream().map(String::trim).filter(part -> !part.isEmpty()).toList();
		Path directory = AcceptanceRun.directory(System.getProperty("honeyfactory.productionRoot", ""), "/mnt/fastvault/minesophila/datasets", "phase1");
		GameTestSupport.pinCaptureSettings(context);
		List<JsonObject> runs = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		String world = "honey-factory-phase1" + (selected.isEmpty() ? "" : "-" + String.join("-", selected));
		AcceptanceRun.inWorld(context, directory, world, 10_026_001L, singleplayer -> {
			for (String mode : new String[] {"perspective", "panorama"}) {
				for (int level = 1; level <= 5; level++) {
					String name = "phase1-l" + level + "-" + mode;
					if (!selected.isEmpty() && selected.stream().noneMatch(name::contains)) {
						continue;
					}

					String scene = level == 5 ? "\"scene\": {\"base\": {\"type\": \"natural_patch\", \"scenes_per_patch\": 4}}," : "";
					String config = """
						{
						  "dataset": {"name": "%s", "seed": %d, "difficulty": %d},
						  %s
						  "viewpoints": {"count": 8},
						  "capture": {"mode": "%s"},
						  "quotas": {"accepted_scenes": %d},
						  "target": {"present_fraction": 0.8},
						  "output": {"root": "%s", "free_space_reserve_bytes": 10737418240}
						}
						""".formatted(name, 10_000 + level * 10 + (mode.equals("perspective") ? 0 : 1), level, scene,
						mode.equals("perspective") ? "PERSPECTIVE_90" : "PANORAMA_310", SCENES[level - 1], directory.toString().replace("\\", "\\\\"));
					JsonObject metrics = AcceptanceRun.generate(context, directory, name, config, TIMEOUT_TICKS);
					runs.add(metrics);
					if (!"complete".equals(metrics.get("lifecycle").getAsString()) || metrics.get("accepted_samples").getAsInt() != SCENES[level - 1] * 8) {
						failures.add(name + ": " + metrics.get("lifecycle").getAsString() + " with " + metrics.get("accepted_samples").getAsInt() + " samples (" + metrics.get("failure") + ")");
					}
				}
			}
		});
		AcceptanceRun.summary(directory, runs);
		if (!failures.isEmpty()) {
			throw new AssertionError("Production failures:\n" + String.join("\n", failures));
		}
	}
}
