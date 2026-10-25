package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.control.ControlServer;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.control.ControlProtocol;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.scene.Placement;
import io.github.thedeadbolt.honeyfactory.scene.ScenePreview;
import io.github.thedeadbolt.honeyfactory.scene.SceneRecipe;
import io.github.thedeadbolt.honeyfactory.scene.SceneSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * M11 exit gate: a Python program drives the player through the loopback server for
 * {@code -Dhoneyfactory.controlSteps} steps (300 by default). A second run that sleeps
 * 50 ms before every step must produce identical frames, masks and true state, and
 * replaying the first run's commands in the game must reproduce its frames and masks.
 * Timings go to {@code build/m11-exit/summary.json}.
 */
public class M11ExitTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m11-exit";
	private static final long SEED = 11L;
	private static final int SIZE = 64;
	private static final List<String> ANIMATED = List.of("water", "lava", "fire");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		int steps = Integer.parseInt(System.getProperty("honeyfactory.controlSteps", "300").isBlank() ? "300" : System.getProperty("honeyfactory.controlSteps"));
		Path output = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m11-exit");
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		summary.addProperty("steps", steps);
		SceneSpec spec = SceneRecipe.of("level1").build();
		ScenePreview preview = ScenePreview.of(spec, SEED, 0);
		for (Placement placement : preview.scene().plan().delta()) {
			if (ANIMATED.stream().anyMatch(placement.state()::contains)) {
				failures.add("the test scene has an animated block, " + placement.state() + "; RGB replay would not be exact");
			}
		}

		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			CompletableFuture<ControlServer> started = context.computeOnClient(minecraft -> ControlServer.start(0, true));
			context.waitFor(minecraft -> started.isDone(), 1200);
			started.join();
			Path connection = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath().resolve(ControlServer.CONNECTION_FILE));
			List<JsonObject> first = drive(context, connection, steps, output.resolve("run1.jsonl"), 0, summary, "run1");
			List<JsonObject> second = drive(context, connection, steps, output.resolve("run2.jsonl"), 50, summary, "run2");
			context.runOnClient(minecraft -> ControlServer.stop());
			compareRuns(first, second, failures);
			replay(context, preview, first, failures, summary);
		}

		try {
			Files.createDirectories(output);
			Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M11 exit failures:\n" + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
		}
	}

	/** Runs the Python demo against the server and returns its log, one object per step. */
	private static List<JsonObject> drive(ClientGameTestContext context, Path connection, int steps, Path log, int delayMs, JsonObject summary, String name) {
		try {
			Files.createDirectories(log.getParent());
			Path repo = GameTestSupport.repoRoot();
			ProcessBuilder builder = new ProcessBuilder(uv(), "run", "--locked", "--package", "honey-factory-tools", "honey-factory-tools", "control-demo",
				"--connection", connection.toString(), "--steps", Integer.toString(steps), "--seed", Long.toString(SEED), "--log", log.toString(),
				"--width", Integer.toString(SIZE), "--height", Integer.toString(SIZE), "--client-delay-ms", Integer.toString(delayMs))
				.directory(repo.toFile()).redirectErrorStream(true).redirectOutput(log.resolveSibling(name + ".out").toFile());
			long start = System.nanoTime();
			Process process = builder.start();
			context.waitFor(minecraft -> !process.isAlive(), steps * 40 + 2400);
			double seconds = (System.nanoTime() - start) / 1e9;
			JsonObject run = new JsonObject();
			run.addProperty("exit_status", process.exitValue());
			run.addProperty("seconds", seconds);
			run.addProperty("steps_per_second", steps / seconds);
			run.addProperty("client_delay_ms", delayMs);
			summary.add(name, run);
			HoneyFactory.LOGGER.info("M11 exit {}: {} steps in {} s, status {}", name, steps, seconds, process.exitValue());
			if (process.exitValue() != 0) {
				throw new AssertionError(name + " exited with " + process.exitValue() + ": " + Files.readString(log.resolveSibling(name + ".out")));
			}

			List<JsonObject> lines = new ArrayList<>();
			for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
				lines.add(JsonFields.parseObject(line));
			}

			if (lines.size() != steps + 1) {
				throw new AssertionError(name + " logged " + lines.size() + " observations, expected " + (steps + 1));
			}

			return lines;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String uv() {
		Path local = Path.of(System.getProperty("user.home"), ".local", "bin", "uv");
		return Files.isExecutable(local) ? local.toString() : "uv";
	}

	/**
	 * Both runs must match step for step. The absolute server tick is the world's clock,
	 * which keeps running across resets, so it is left out; instead it must advance by
	 * exactly one per step in each run.
	 */
	private static void compareRuns(List<JsonObject> first, List<JsonObject> second, List<String> failures) {
		for (int i = 0; i < first.size(); i++) {
			for (String key : List.of("rgb_sha256", "mask_sha256", "truth", "senses")) {
				JsonElement a = withoutClock(first.get(i).get(key));
				JsonElement b = withoutClock(second.get(i).get(key));
				if (!a.equals(b)) {
					failures.add("step " + i + ": " + key + " differs between the runs with and without client delay");
				}
			}

			if (i > 0) {
				for (List<JsonObject> run : List.of(first, second)) {
					long tick = run.get(i).getAsJsonObject("truth").get("server_tick").getAsLong();
					long previous = run.get(i - 1).getAsJsonObject("truth").get("server_tick").getAsLong();
					if (tick != previous + 1) {
						failures.add("step " + i + " advanced the server from tick " + previous + " to " + tick);
					}
				}
			}
		}
	}

	private static JsonElement withoutClock(JsonElement element) {
		if (!element.isJsonObject() || !element.getAsJsonObject().has("server_tick")) {
			return element;
		}

		JsonObject copy = element.getAsJsonObject().deepCopy();
		copy.remove("server_tick");
		return copy;
	}

	/** Replays the first run's executed commands in the game and compares frames and masks. */
	private static void replay(ClientGameTestContext context, ScenePreview preview, List<JsonObject> log, List<String> failures, JsonObject summary) {
		PerspectiveProjection projection = new PerspectiveProjection(SIZE, SIZE);
		long start = System.nanoTime();
		EnvironmentRuntime.ControlResult result = GameTestSupport.await(context, runtime -> runtime.controlReset(preview.arena(), preview.scene().plan(),
			preview.viewpoint().pose(preview.arena()), projection, SEED));
		compare(0, result.capture().images(), log.getFirst(), failures);
		for (int i = 1; i < log.size(); i++) {
			MotorCommand command = ControlProtocol.command(log.get(i).getAsJsonObject("command"));
			result = GameTestSupport.await(context, runtime -> runtime.controlStep(command, null, ControlStep.Executed.STUDENT));
			compare(i, result.capture().images(), log.get(i), failures);
		}

		context.runOnClient(minecraft -> EnvironmentRuntime.get().controlEnd());
		summary.addProperty("replay_seconds", (System.nanoTime() - start) / 1e9);
	}

	private static void compare(int index, CapturedImages images, JsonObject logged, List<String> failures) {
		if (!Sha256.hex(images.rgb()).equals(logged.get("rgb_sha256").getAsString())) {
			failures.add("replayed step " + index + ": RGB differs");
		}

		if (!Sha256.hex(images.visibleMask()).equals(logged.get("mask_sha256").getAsString())) {
			failures.add("replayed step " + index + ": mask differs");
		}
	}
}
