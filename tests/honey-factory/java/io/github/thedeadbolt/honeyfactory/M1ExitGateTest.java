package io.github.thedeadbolt.honeyfactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ContractException;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetConsistency;
import io.github.thedeadbolt.honeyfactory.dataset.SampleDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Roadmap M1 exit gate: a pure-Java test constructs a resolved configuration,
 * deterministically derives a scene and sample descriptor, serializes all record types,
 * reads them back and rejects every specified inconsistency. The specified
 * inconsistencies are the shared fixture cases run by RecordCasesTest and DatasetCasesTest.
 */
class M1ExitGateTest {
	@Test
	void configurationToRecordsAndBack() {
		ResolvedConfig config = ContractExamples.config();
		assertEquals(config.sha256(), ContractExamples.config().sha256());

		SceneSlots.Slot slot = SceneSlots.plan(config).getFirst();
		SceneDescriptor scene = SceneDescriptor.of(config, slot, 0);
		SampleDescriptor sample = scene.sample(1);
		assertEquals(scene, SceneDescriptor.of(ContractExamples.config(), SceneSlots.plan(ContractExamples.config()).getFirst(), 0));
		assertEquals(sample, scene.sample(1));
		assertEquals("s000000-v01", sample.sampleId());

		ContractExamples.Dataset dataset = ContractExamples.completeDataset();
		DatasetManifest manifest = DatasetManifest.fromJson(roundTrip(dataset.manifest().toJson()));
		List<SceneRecord> scenes = Jsonl.read(dataset.scenesJsonl(), "scenes.jsonl").stream().map(SceneRecord::fromJson).toList();
		List<SampleRecord> samples = Jsonl.read(dataset.samplesJsonl(), "samples.jsonl").stream().map(SampleRecord::fromJson).toList();

		assertEquals(dataset.manifest().toJson(), manifest.toJson());
		assertEquals(dataset.scenes().stream().map(SceneRecord::toJson).toList(), scenes.stream().map(SceneRecord::toJson).toList());
		assertEquals(dataset.samples().stream().map(SampleRecord::toJson).toList(), samples.stream().map(SampleRecord::toJson).toList());
		assertEquals(List.of(), DatasetConsistency.check(manifest, scenes, samples));
		assertEquals(config.datasetId(), manifest.datasetId());
	}

	@Test
	void readBackRejectsInconsistentRecords() {
		ContractExamples.Dataset dataset = ContractExamples.completeDataset();
		JsonObject sample = dataset.samples().getFirst().toJson();
		sample.getAsJsonObject("target").addProperty("unoccluded_pixels", 1);
		assertThrows(ContractException.class, () -> SampleRecord.fromJson(sample));

		List<SampleRecord> missingOne = dataset.samples().subList(1, dataset.samples().size());
		assertFalse(DatasetConsistency.check(dataset.manifest(), dataset.scenes(), missingOne).isEmpty());
	}

	private static JsonObject roundTrip(JsonObject json) {
		return io.github.thedeadbolt.honeyfactory.contract.JsonFields.parseObject(new String(Jsonl.document(json), java.nio.charset.StandardCharsets.UTF_8));
	}
}
