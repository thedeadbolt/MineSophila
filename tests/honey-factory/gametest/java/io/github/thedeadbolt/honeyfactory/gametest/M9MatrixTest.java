package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Specification acceptance run 2, the configuration matrix smoke: 10 accepted scenes with
 * 8 viewpoints for every level 1–5 in both capture modes at their default resolutions,
 * exactly 8 target-present and 2 target-absent scenes each, 800 base samples in all.
 * Every dataset must complete with a passing validation. Runs only when named
 * ({@code -Dhoneyfactory.gametests=M9Matrix}); see {@link AcceptanceRun} for outputs.
 */
public class M9MatrixTest implements FabricClientGameTest {
	static final long WORLD_SEED = 9_026_001L;
	private static final int TIMEOUT_TICKS = 20 * 60 * 60;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		Path directory = AcceptanceRun.directory("matrix");
		GameTestSupport.pinCaptureSettings(context);
		List<JsonObject> runs = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		AcceptanceRun.inWorld(context, directory, "honey-factory-m9-matrix", WORLD_SEED, world -> {
			for (int level = 1; level <= 5; level++) {
				for (String mode : new String[] {"PERSPECTIVE_90", "PANORAMA_310"}) {
					String name = "m9-matrix-l" + level + "-" + (mode.startsWith("PERSPECTIVE") ? "perspective" : "panorama");
					String config = """
						{
						  "dataset": {"name": "%s", "seed": %d, "difficulty": %d},
						  "viewpoints": {"count": 8},
						  "capture": {"mode": "%s"},
						  "quotas": {"accepted_scenes": 10},
						  "target": {"present_fraction": 0.8},
						  "output": {"root": "%s", "free_space_reserve_bytes": 10737418240}
						}
						""".formatted(name, 9000 + level * 10 + (mode.startsWith("PERSPECTIVE") ? 0 : 1), level, mode, directory.toString().replace("\\", "\\\\"));
					JsonObject metrics = AcceptanceRun.generate(context, directory, name, config, TIMEOUT_TICKS);
					runs.add(metrics);
					if (!"complete".equals(metrics.get("lifecycle").getAsString()) || metrics.get("accepted_samples").getAsInt() != 80) {
						failures.add(name + ": " + metrics.get("lifecycle").getAsString() + " with " + metrics.get("accepted_samples").getAsInt() + " samples (" + metrics.get("failure") + ")");
					}
				}
			}
		});
		AcceptanceRun.summary(directory, runs);
		if (!failures.isEmpty()) {
			throw new AssertionError("M9 matrix failures:\n" + String.join("\n", failures));
		}
	}
}
