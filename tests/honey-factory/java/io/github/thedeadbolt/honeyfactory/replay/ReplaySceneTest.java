package io.github.thedeadbolt.honeyfactory.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.contract.CanonicalJson;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.generation.GenerationEnvironment;
import io.github.thedeadbolt.honeyfactory.generation.ScriptedEnvironment;
import io.github.thedeadbolt.honeyfactory.image.PngImages;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplaySceneTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);

	@TempDir
	Path temp;

	private Path generate(String scene, int level) {
		String config = """
			{
			  "dataset": {"name": "m8-unit", "seed": 5, "difficulty": %d},
			  %s
			  "viewpoints": {"count": 2},
			  "capture": {"width": 48, "height": 32},
			  "quotas": {"accepted_scenes": 4, "scene_attempts_per_accepted_scene": 5},
			  "splits": {"train": 0.5, "validation": 0.25, "test": 0.25},
			  "target": {"present_fraction": 0.75},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1000000}
			}
			""".formatted(level, scene, this.temp.toString().replace("\\", "\\\\"));
		ScriptedEnvironment environment = new ScriptedEnvironment();
		try {
			CompletableFuture<DatasetGenerator.Result> result = new DatasetGenerator(ConfigResolver.resolve(config), environment, CLOCK, path -> 1L << 50).start();
			environment.pump();
			DatasetGenerator.Result done = result.join();
			assertNull(done.failure(), () -> done.failure() + " " + done.validationErrors());
			return done.directory();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<SceneRecord> scenes(Path dataset) throws IOException {
		return DatasetValidator.validate(dataset).scenes();
	}

	private static Map<String, String> hashes(Path dataset) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		try (Stream<Path> files = Files.walk(dataset)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				hashes.put(dataset.relativize(file).toString(), Sha256.hex(Files.readAllBytes(file)));
			}
		}

		return hashes;
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3, 4})
	void everyRecordedPlatformSceneRegeneratesAsRecorded(int level) throws IOException {
		Path dataset = generate("", level);
		Map<String, String> before = hashes(dataset);
		for (SceneRecord record : scenes(dataset)) {
			ReplayScene scene = ReplayScene.load(dataset, record.sceneId());
			assertNull(scene.patchRequest());
			assertEquals(0, scene.focus());
			GeneratedScene generated = scene.regenerate(null);
			assertEquals(record.layoutSha256(), generated.plan().layoutSha256(scene.arena()));
			assertEquals(scene.samples().stream().map(ReplayScene::pose).toList(), scene.poses());

			SampleRecord last = scene.samples().getLast();
			ReplayScene focused = ReplayScene.load(dataset, last.sampleId());
			assertEquals(scene.samples().size() - 1, focused.focus());
			assertEquals(last.sampleId(), focused.samples().get(focused.focus()).sampleId());
		}

		assertEquals(before, hashes(dataset), "loading and regenerating write nothing in the dataset");
	}

	@Test
	void naturalScenesNeedTheirSourcePatchPrepared() throws IOException {
		Path dataset = generate("\"scene\": {\"base\": {\"type\": \"natural_patch\", \"context_padding\": 192, \"scenes_per_patch\": 2}},", 5);
		for (SceneRecord record : scenes(dataset)) {
			ReplayScene scene = ReplayScene.load(dataset, record.sceneId());
			GenerationEnvironment.PatchRequest request = scene.patchRequest();
			assertNotNull(request);
			assertEquals(record.generation().sceneIndex() / 2, request.patchIndex(), "scenes share patches in pairs");
			assertThrows(IllegalArgumentException.class, () -> scene.regenerate(null));
			ScriptedEnvironment environment = new ScriptedEnvironment();
			CompletableFuture<PreparedPatch> patch = environment.preparePatch(request);
			environment.pump();
			assertEquals(record.sourcePatch().patchId(), scene.regenerate(patch.join()).plan().patch().patchId());
		}
	}

	@Test
	void comparesFreshCapturesWithTheStoredImages() throws IOException {
		Path dataset = generate("", 1);
		SceneRecord present = scenes(dataset).stream().filter(scene -> scene.target().present()).findFirst().orElseThrow();
		ReplayScene scene = ReplayScene.load(dataset, present.sceneId());
		SampleRecord sample = scene.samples().getFirst();
		PngImages.Pixels rgb = PngImages.decode(Files.readAllBytes(dataset.resolve(sample.rgb().path())));
		PngImages.Pixels mask = PngImages.decode(Files.readAllBytes(dataset.resolve(sample.mask().path())));
		CapturedImages same = new CapturedImages(rgb.width(), rgb.height(), rgb.data(), mask.data(), mask.data());
		ReplayComparison.Result exact = ReplayComparison.compare(scene, sample, same);
		assertTrue(exact.maskMatches() && exact.rgbMatches(), exact.toString());
		assertEquals(sample.target().visiblePixels(), exact.freshVisiblePixels());

		byte[] shiftedMask = mask.data().clone();
		shiftedMask[0] = (byte) (shiftedMask[0] == 0 ? 255 : 0);
		byte[] tinted = rgb.data().clone();
		tinted[4] = (byte) ((tinted[4] & 0xFF) ^ 3);
		ReplayComparison.Result off = ReplayComparison.compare(scene, sample, new CapturedImages(rgb.width(), rgb.height(), tinted, shiftedMask, shiftedMask));
		assertEquals(1, off.maskDifferingPixels());
		assertEquals(1, off.rgbDifferingPixels());
		assertEquals(3, off.rgbMaxChannelDelta());

		assertThrows(ReplayException.class, () -> ReplayComparison.compare(scene, sample, CapturedImages.withoutTarget(16, 16, new byte[16 * 16 * 3])));
		Files.write(dataset.resolve(sample.mask().path()), PngImages.encodeGray(mask.width(), mask.height(), shiftedMask));
		assertEquals(ReplayException.Reason.INVALID_DATASET, assertThrows(ReplayException.class, () -> ReplayComparison.compare(scene, sample, same)).reason(),
			"a damaged stored mask is reported, not compared");
	}

	@Test
	void reportsUnknownIdsAndDirectories() {
		Path dataset = generate("", 1);
		assertEquals(ReplayException.Reason.NOT_FOUND, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, "s000099")).reason());
		assertEquals(ReplayException.Reason.NOT_FOUND, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, "s000000-v07")).reason());
		assertEquals(ReplayException.Reason.NOT_FOUND, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, "scene-1")).reason());
		assertEquals(ReplayException.Reason.NOT_FOUND, assertThrows(ReplayException.class, () -> ReplayScene.load(this.temp.resolve("missing"), "s000000")).reason());
	}

	@Test
	void refusesTemplatesAndConfigurationsThisBuildDoesNotHave() throws IOException {
		Path dataset = generate("", 1);
		editScene(dataset, scene -> scene.getAsJsonObject("template").addProperty("version", 99));
		assertEquals(ReplayException.Reason.UNAVAILABLE, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, "s000000").regenerate(null)).reason());

		Path other = this.temp.resolve("other");
		Files.createDirectories(other);
		Path copy = generateInto(other);
		JsonObject manifest = JsonFields.parseObject(Files.readString(copy.resolve("dataset.json")));
		// A recorded eye height this build no longer resolves to: the configuration hash cannot match.
		manifest.getAsJsonObject("capture").getAsJsonObject("viewpoints").addProperty("eye_height_blocks", 1.5);
		rehash(manifest);
		Files.write(copy.resolve("dataset.json"), Jsonl.document(manifest));
		ReplayException unavailable = assertThrows(ReplayException.class, () -> ReplayScene.load(copy, "s000000"));
		assertEquals(ReplayException.Reason.UNAVAILABLE, unavailable.reason(), unavailable.getMessage());
	}

	@Test
	void reportsRecordsThatRegenerateDifferently() throws IOException {
		Path dataset = generate("", 3);
		SceneRecord present = scenes(dataset).stream().filter(scene -> scene.target().present()).findFirst().orElseThrow();
		editScene(dataset, scene -> {
			if (scene.get("scene_id").getAsString().equals(present.sceneId())) {
				scene.getAsJsonObject("target").getAsJsonArray("position").set(1, new com.google.gson.JsonPrimitive(present.target().position()[1] + 1));
			}
		});
		assertEquals(ReplayException.Reason.MISMATCH, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, present.sceneId()).regenerate(null)).reason());

		editScene(dataset, scene -> scene.getAsJsonObject("generation").getAsJsonObject("seeds").addProperty("layout", 1));
		assertEquals(ReplayException.Reason.MISMATCH, assertThrows(ReplayException.class, () -> ReplayScene.load(dataset, "s000001")).reason());
	}

	private Path generateInto(Path root) {
		Path previous = this.temp;
		this.temp = root;
		try {
			return generate("", 1);
		} finally {
			this.temp = previous;
		}
	}

	/** Recomputes the manifest's configuration hash and dataset ID after an edit. */
	private static void rehash(JsonObject manifest) {
		JsonObject configuration = new JsonObject();
		for (String member : List.of("difficulty", "target", "generator", "capture", "splits")) {
			configuration.add(member, manifest.get(member).deepCopy());
		}

		configuration.getAsJsonObject("splits").remove("counts");
		String sha = CanonicalJson.sha256(configuration);
		manifest.addProperty("resolved_config_sha256", sha);
		manifest.addProperty("dataset_id", Ids.datasetId(manifest.getAsJsonObject("generator").get("dataset_name").getAsString(), sha));
		DatasetManifest.fromJson(manifest);
	}

	private static void editScene(Path dataset, Consumer<JsonObject> edit) throws IOException {
		Path index = dataset.resolve("scenes.jsonl");
		List<JsonObject> lines = Jsonl.read(Files.readAllBytes(index), "scenes.jsonl");
		lines.forEach(edit);
		Files.write(index, Jsonl.write(lines));
		assertTrue(Files.readString(index, StandardCharsets.UTF_8).endsWith("\n"));
	}
}
