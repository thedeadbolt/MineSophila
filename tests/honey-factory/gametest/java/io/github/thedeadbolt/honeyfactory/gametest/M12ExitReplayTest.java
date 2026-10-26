package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.replay.ReplayRuns;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.environment.Futures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;

/**
 * M12-1 exit gate: a scene from each slice of a dataset merged by
 * {@code generate --instances N} replays exactly in its own world. The dataset is named by
 * {@code -Dhoneyfactory.exitDataset}; the slices' worlds lie in {@code worlds/} beside it,
 * as the job copied them. For each world the manifest lists, a copy of that world is opened
 * and the world's first scene is replayed and verified: layout and every viewpoint's mask
 * must match. Results go to {@code metrics/exit-replay.json} beside the dataset. Runs only
 * when named ({@code -Dhoneyfactory.gametests=M12ExitReplay}).
 */
public class M12ExitReplayTest implements FabricClientGameTest {
	private static final int REPLAY_TICKS = 20 * 60 * 10;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		Path dataset = Path.of(System.getProperty("honeyfactory.exitDataset", ""));
		DatasetManifest manifest;
		List<SceneRecord> scenes = new ArrayList<>();
		try {
			manifest = DatasetManifest.fromJson(JsonFields.parseObject(Files.readString(dataset.resolve("dataset.json"), StandardCharsets.UTF_8)));
			for (String line : Files.readAllLines(dataset.resolve("scenes.jsonl"), StandardCharsets.UTF_8)) {
				scenes.add(SceneRecord.fromJson(JsonFields.parseObject(line)));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		if (manifest.worlds().isEmpty()) {
			failures.add(dataset + " lists no worlds; it is not a merged dataset");
		}

		for (DatasetManifest.World world : manifest.worlds()) {
			SceneRecord scene = scenes.stream().filter(record -> record.generation().sceneIndex() == world.slots().getFirst()).findFirst().orElse(null);
			if (scene == null || world.world() == null) {
				failures.add("world " + world.world() + ": no scene of slot " + world.slots().getFirst());
				continue;
			}

			Path saved = dataset.getParent().resolve("worlds").resolve(world.world());
			TestWorldSave save;
			try (TestSingleplayerContext created = GameTestSupport.datasetWorld(context, world.world(), 0L)) {
				save = created.getWorldSave();
			}

			GameTestSupport.deleteRecursively(save.getSaveDirectory());
			copy(saved, save.getSaveDirectory());
			try (TestSingleplayerContext singleplayer = save.open()) {
				GameTestSupport.claim(singleplayer, world.world());
				JsonObject entry = new JsonObject();
				try {
					ReplayRuns.Loaded loaded = await(context, () -> ReplayRuns.load(dataset, scene.sceneId()));
					ReplayRuns.Verification verification = await(context, ReplayRuns::verify);
					entry.addProperty("scene", scene.sceneId());
					entry.addProperty("layout_matches", loaded.layoutMatches());
					entry.addProperty("exact", verification.exact());
					if (!verification.exact()) {
						failures.add("world " + world.world() + " scene " + scene.sceneId() + " does not replay exactly");
					}
				} catch (CompletionException | IllegalStateException e) {
					failures.add("world " + world.world() + " scene " + scene.sceneId() + ": replay failed: " + Futures.cause(e));
				}

				summary.add(world.world(), entry);
			}
		}

		try {
			Files.createDirectories(dataset.getParent().resolve("metrics"));
			Files.writeString(dataset.getParent().resolve("metrics").resolve("exit-replay.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		HoneyFactory.LOGGER.info("M12 exit replay: {}", summary);
		if (!failures.isEmpty()) {
			throw new AssertionError("M12 exit replay failures:\n" + String.join("\n", failures));
		}
	}

	private static <T> T await(ClientGameTestContext context, Supplier<CompletableFuture<T>> request) {
		CompletableFuture<T> future = context.computeOnClient(minecraft -> {
			try {
				return request.get();
			} catch (RuntimeException e) {
				return CompletableFuture.failedFuture(e);
			}
		});
		context.waitFor(minecraft -> future.isDone(), REPLAY_TICKS);
		return future.join();
	}

	/** Copies a world save, leaving out its session lock. */
	private static void copy(Path from, Path to) {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else if (!file.getFileName().toString().equals("session.lock")) {
					Files.copy(file, target);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
