package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Runs one instance's part of {@code honey-factory-tools generate --instances N} (M12-1): the
 * job file named by {@code -Dhoneyfactory.job} gives a configuration (usually with an
 * {@code output.slice}), a world name and seed, and an output root. The instance creates and
 * claims that world, generates the configuration by command, writes its measurements to
 * {@code <output root>/metrics/<world>.json} and copies the world to
 * {@code <output root>/worlds/<world>}, so natural scenes replay in the world that generated
 * them. It fails unless the dataset completes. Runs only when named
 * ({@code -Dhoneyfactory.gametests=GenerationJob}).
 */
public class GenerationJobTest implements FabricClientGameTest {
	private static final int TIMEOUT_TICKS = 20 * 60 * 60 * 12;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		String file = System.getProperty("honeyfactory.job", "");
		if (file.isBlank()) {
			throw new AssertionError("GenerationJob needs -Dhoneyfactory.job=<job file>");
		}

		JsonObject job;
		String config;
		try {
			job = JsonFields.parseObject(Files.readString(Path.of(file), StandardCharsets.UTF_8));
			config = Files.readString(Path.of(job.get("config").getAsString()), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		String world = job.get("world").getAsString();
		Path root = AcceptanceRun.directory(job.get("output_root").getAsString(), "", "");
		GameTestSupport.pinCaptureSettings(context);
		JsonObject[] metrics = new JsonObject[1];
		AcceptanceRun.inWorld(context, root, world, job.get("world_seed").getAsLong(), singleplayer -> metrics[0] = AcceptanceRun.generate(context, root, world, config, TIMEOUT_TICKS));
		if (!"complete".equals(metrics[0].get("lifecycle").getAsString())) {
			throw new AssertionError("generation job " + world + ": " + metrics[0].get("lifecycle").getAsString() + " (" + metrics[0].get("failure") + ")");
		}
	}
}
