package io.github.thedeadbolt.honeyfactory.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import io.github.thedeadbolt.honeyfactory.JsonPatch;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Runs the shared cross-record fixtures through {@link DatasetConsistency}. */
class DatasetCasesTest {
	private static Map<String, List<JsonObject>> load() {
		Map<String, List<JsonObject>> files = new HashMap<>();
		for (String name : List.of("scenes.jsonl", "samples.jsonl")) {
			files.put(name, new ArrayList<>(Jsonl.read(Fixtures.bytes(Fixtures.contract().resolve("dataset").resolve(name)), name)));
		}

		return files;
	}

	private static List<String> check(Map<String, List<JsonObject>> files) {
		DatasetManifest manifest = DatasetManifest.fromJson(Fixtures.json(Fixtures.contract().resolve("dataset/dataset.json")));
		List<SceneRecord> scenes = files.get("scenes.jsonl").stream().map(SceneRecord::fromJson).toList();
		List<SampleRecord> samples = files.get("samples.jsonl").stream().map(SampleRecord::fromJson).toList();
		return DatasetConsistency.check(manifest, scenes, samples);
	}

	@Test
	void sharedDatasetIsConsistent() {
		assertEquals(List.of(), check(load()));
	}

	@TestFactory
	Stream<DynamicTest> sharedCases() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonElement element : Fixtures.json(Fixtures.contract().resolve("dataset-cases.json")).getAsJsonArray("cases")) {
			JsonObject testCase = element.getAsJsonObject();
			tests.add(DynamicTest.dynamicTest(testCase.get("name").getAsString(), () -> {
				Map<String, List<JsonObject>> files = load();
				for (JsonElement editElement : testCase.getAsJsonArray("edits")) {
					JsonObject edit = editElement.getAsJsonObject();
					List<JsonObject> lines = files.get(edit.get("file").getAsString());
					if (edit.has("append_copy_of")) {
						lines.add(lines.get(edit.get("append_copy_of").getAsInt()).deepCopy());
					} else if (edit.has("remove_line")) {
						lines.remove(edit.get("remove_line").getAsInt());
					} else {
						int line = edit.get("line").getAsInt();
						lines.set(line, JsonPatch.apply(lines.get(line), edit.getAsJsonArray("patch")));
					}
				}

				List<String> errors = check(files);
				String expected = testCase.get("expect_error").getAsString();
				assertTrue(errors.stream().anyMatch(error -> error.contains(expected)), "expected an error containing '" + expected + "', got " + errors);
			}));
		}

		return tests.stream();
	}
}
