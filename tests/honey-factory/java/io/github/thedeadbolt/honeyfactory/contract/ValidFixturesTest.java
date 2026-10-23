package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.thedeadbolt.honeyfactory.ContractExamples;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keeps the committed valid fixtures equal to what the Java writer produces. Regenerate
 * them deliberately with {@code ./gradlew test -Dhoneyfactory.regenerateFixtures=true}.
 */
class ValidFixturesTest {
	static Map<String, byte[]> expected() {
		ContractExamples.Dataset dataset = ContractExamples.completeDataset();
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("dataset/dataset.json", Jsonl.document(dataset.manifest().toJson()));
		files.put("dataset/scenes.jsonl", dataset.scenesJsonl());
		files.put("dataset/samples.jsonl", dataset.samplesJsonl());
		dataset.images().forEach((path, png) -> files.put("dataset/" + path, png));

		DatasetManifest generating = DatasetManifest.generating(
			ContractExamples.config().toJson(), ContractExamples.CREATED, ContractExamples.software(), ContractExamples.render(), ContractExamples.provenance()
		);
		files.put("valid/dataset-generating.json", Jsonl.document(generating.toJson()));
		files.put("valid/dataset-complete.json", Jsonl.document(dataset.manifest().toJson()));

		SceneRecord present = dataset.scenes().stream().filter(scene -> scene.target().present()).findFirst().orElseThrow();
		SceneRecord absent = dataset.scenes().stream().filter(scene -> !scene.target().present()).findFirst().orElseThrow();
		files.put("valid/scene-present.json", Jsonl.document(present.toJson()));
		files.put("valid/scene-absent.json", Jsonl.document(absent.toJson()));

		SampleRecord presentSample = dataset.samples().stream().filter(sample -> sample.sceneId().equals(present.sceneId())).findFirst().orElseThrow();
		SampleRecord absentSample = dataset.samples().stream().filter(sample -> sample.sceneId().equals(absent.sceneId())).findFirst().orElseThrow();
		Map<String, SampleRecord.Target> statuses = new LinkedHashMap<>();
		statuses.put("fully-visible", ContractExamples.fullyVisible());
		statuses.put("partially-visible", ContractExamples.partiallyVisible());
		statuses.put("fully-occluded", ContractExamples.fullyOccluded());
		statuses.put("subpixel", ContractExamples.subpixel());
		statuses.put("out-of-view", ContractExamples.outOfView());
		statuses.forEach((name, target) -> files.put("valid/sample-" + name + ".json", Jsonl.document(withTarget(presentSample, target).toJson())));
		files.put("valid/sample-absent.json", Jsonl.document(absentSample.toJson()));
		return files;
	}

	private static SampleRecord withTarget(SampleRecord sample, SampleRecord.Target target) {
		return new SampleRecord(
			sample.schemaVersion(), sample.sampleId(), sample.sceneId(), sample.split(), sample.viewpointIndex(), sample.rgb(), sample.mask(),
			sample.camera(), sample.mode(), sample.projectionOverrides(), sample.capture(), target, sample.conditions(), sample.augmentation()
		);
	}

	@Test
	void committedValidFixturesMatchTheJavaWriter() throws IOException {
		boolean regenerate = Boolean.getBoolean("honeyfactory.regenerateFixtures");
		for (Map.Entry<String, byte[]> file : expected().entrySet()) {
			Path path = Fixtures.contract().resolve(file.getKey());
			if (regenerate) {
				Files.createDirectories(path.getParent());
				Files.write(path, file.getValue());
			}

			if (file.getKey().endsWith(".png")) {
				assertArrayEquals(file.getValue(), Files.readAllBytes(path), file.getKey() + " is stale; regenerate the fixtures");
			} else {
				assertEquals(new String(file.getValue(), StandardCharsets.UTF_8), Files.readString(path), file.getKey() + " is stale; regenerate the fixtures");
			}
		}
	}

	@Test
	void committedDatasetPassesFullValidation() throws IOException {
		DatasetValidator.Report report = DatasetValidator.validate(Fixtures.contract().resolve("dataset"));
		assertEquals(List.of(), report.errors());
	}
}
