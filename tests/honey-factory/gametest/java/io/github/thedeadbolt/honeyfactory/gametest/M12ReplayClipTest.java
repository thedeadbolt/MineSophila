package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.replay.ReplayRuns;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import io.github.thedeadbolt.honeyfactory.replay.ReplayClip;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * M12-2: clips replay exactly. For each level 1 to 5 a small clip dataset is generated, then
 * each clip is replayed in the same world from its scene seeds and executed commands: every
 * frame's pose, labels and visible mask must match, and the RGB too where it is compared
 * (lossless codec, no animated blocks or rain). Results go to
 * {@code build/m12-replay-clip/<label>/metrics/clip-replay.json}. Runs only when named
 * ({@code -Dhoneyfactory.gametests=M12ReplayClip}).
 */
public class M12ReplayClipTest implements FabricClientGameTest {
	private static final String WORLD = "honey-factory-m12-replay-clip";
	private static final long WORLD_SEED = 12_028L;
	private static final int TIMEOUT_TICKS = 20 * 60 * 20;
	private static final String CONFIG = """
		{
		  "dataset": {"name": "m12-replay-l%d", "seed": %d, "difficulty": %d},
		  "capture": {"mode": "PANORAMA_310", "width": 1240, "height": 640, "cube_face_size": 512},
		  "quotas": {"accepted_scenes": 2, "scene_attempts_per_accepted_scene": 3},
		  "target": {"present_fraction": 1.0},
		  "splits": {"train": 0.5, "validation": 0.5, "test": 0.0},
		  "clips": {"clips_per_scene": 2, "ticks": {"approach": 24, "pass_by": 12, "scan": 12, "occlusion": 16, "detour": 24, "search": 24, "no_honey": 12},
		            "codec": {"name": "h264-lossless"}},
		  "output": {"root": "%s"}
		}
		""";

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skippedUnlessNamed(this)) {
			return;
		}

		String label = System.getProperty("honeyfactory.acceptanceLabel", "");
		Path root = AcceptanceRun.directory(GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m12-replay-clip").toString(), "",
			label.isBlank() ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm", Locale.ROOT)) : label);
		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		JsonArray results = new JsonArray();
		AcceptanceRun.inWorld(context, root, WORLD, WORLD_SEED, world -> {
			for (int level = 1; level <= 5; level++) {
				String config = CONFIG.formatted(level, 12_028 + level, level, root.toString().replace("\\", "\\\\"));
				JsonObject metrics = AcceptanceRun.generate(context, root, "m12-replay-l" + level, config, TIMEOUT_TICKS);
				if (!"complete".equals(metrics.get("lifecycle").getAsString())) {
					failures.add("level " + level + ": the clip dataset did not complete: " + metrics.get("failure"));
					continue;
				}

				Path dataset = root.resolve(metrics.get("directory").getAsString());
				for (String clipId : clipIds(dataset)) {
					ReplayClip.Result result = replay(context, dataset, clipId);
					JsonObject entry = result.toJson();
					entry.addProperty("level", level);
					results.add(entry);
					HoneyFactory.LOGGER.info("M12 replay clip: level {} {}", level, entry);
					if (!result.exact()) {
						failures.add("level " + level + " " + clipId + " does not replay exactly: " + result.differences());
					}

					if (level < 5 && !result.rgbCompared()) {
						failures.add("level " + level + " " + clipId + ": RGB was not compared on a platform level");
					}
				}
			}
		});

		try {
			Files.writeString(root.resolve("metrics").resolve("clip-replay.json"), new GsonBuilder().setPrettyPrinting().create().toJson(results) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M12 clip replay failures:\n" + String.join("\n", failures));
		}
	}

	private static List<String> clipIds(Path dataset) {
		try {
			return Files.readAllLines(dataset.resolve(DatasetWriter.CLIPS), StandardCharsets.UTF_8).stream()
				.map(line -> ClipRecord.fromJson(JsonFields.parseObject(line)).clipId()).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ReplayClip.Result replay(ClientGameTestContext context, Path dataset, String clipId) {
		CompletableFuture<ReplayClip.Result> future = context.computeOnClient(minecraft -> {
			try {
				return ReplayRuns.replayClip(dataset, clipId);
			} catch (RuntimeException e) {
				return CompletableFuture.failedFuture(e);
			}
		});
		context.waitFor(minecraft -> future.isDone(), TIMEOUT_TICKS);
		return future.join();
	}
}
