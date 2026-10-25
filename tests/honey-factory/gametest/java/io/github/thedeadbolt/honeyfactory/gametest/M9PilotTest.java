package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Specification acceptance run 3, the production-scale pilot: one uninterrupted level-5
 * {@code PANORAMA_310} dataset at the default resolution with 1,000 accepted scenes, 8
 * viewpoints and the default 80/20 target balance, 8,000 base samples. It passes only with
 * the exact quotas, a completed validation and lifecycle, and no failure. Runs only when
 * named ({@code -Dhoneyfactory.gametests=M9Pilot}); see {@link AcceptanceRun} for outputs.
 */
public class M9PilotTest implements FabricClientGameTest {
	static final long WORLD_SEED = 9_026_002L;
	private static final int TIMEOUT_TICKS = 20 * 60 * 60 * 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		Path directory = AcceptanceRun.directory("pilot");
		GameTestSupport.pinCaptureSettings(context);
		JsonObject[] metrics = new JsonObject[1];
		AcceptanceRun.inWorld(context, directory, "honey-factory-m9-pilot", WORLD_SEED, world -> {
			String config = """
				{
				  "dataset": {"name": "m9-pilot-l5-panorama", "seed": 9500, "difficulty": 5},
				  "viewpoints": {"count": 8},
				  "capture": {"mode": "PANORAMA_310"},
				  "quotas": {"accepted_scenes": 1000},
				  "output": {"root": "%s", "free_space_reserve_bytes": 10737418240}
				}
				""".formatted(directory.toString().replace("\\", "\\\\"));
			metrics[0] = AcceptanceRun.generate(context, directory, "m9-pilot-l5-panorama", config, TIMEOUT_TICKS);
		});
		AcceptanceRun.summary(directory, List.of(metrics[0]));
		if (!"complete".equals(metrics[0].get("lifecycle").getAsString()) || metrics[0].get("accepted_samples").getAsInt() != 8000) {
			throw new AssertionError("M9 pilot: " + metrics[0].get("lifecycle").getAsString() + " with " + metrics[0].get("accepted_samples").getAsInt() + " samples (" + metrics[0].get("failure") + ")");
		}
	}
}
