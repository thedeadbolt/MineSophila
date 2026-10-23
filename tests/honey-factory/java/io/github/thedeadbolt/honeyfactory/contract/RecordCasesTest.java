package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import io.github.thedeadbolt.honeyfactory.JsonPatch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs the shared single-record fixtures through the Java contract parser. */
class RecordCasesTest {
	private static Consumer<JsonObject> parser(String kind) {
		return switch (kind) {
			case "dataset" -> DatasetManifest::fromJson;
			case "scene" -> SceneRecord::fromJson;
			case "sample" -> SampleRecord::fromJson;
			default -> throw new IllegalArgumentException(kind);
		};
	}

	@TestFactory
	Stream<DynamicTest> everyValidFixtureParses() throws IOException {
		try (Stream<Path> files = Files.list(Fixtures.contract().resolve("valid"))) {
			List<Path> paths = files.sorted().toList();
			return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
				String kind = path.getFileName().toString().split("-")[0];
				assertDoesNotThrow(() -> parser(kind).accept(Fixtures.json(path)));
			}));
		}
	}

	@TestFactory
	Stream<DynamicTest> sharedCases() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonElement element : Fixtures.json(Fixtures.contract().resolve("record-cases.json")).getAsJsonArray("cases")) {
			JsonObject testCase = element.getAsJsonObject();
			String expect = testCase.get("expect").getAsString();
			tests.add(DynamicTest.dynamicTest(testCase.get("name").getAsString() + " (" + expect + ")", () -> {
				JsonObject record = JsonPatch.apply(Fixtures.json(Fixtures.contract().resolve(testCase.get("base").getAsString())), testCase.getAsJsonArray("patch"));
				Consumer<JsonObject> parse = parser(testCase.get("kind").getAsString());
				if (expect.equals("valid")) {
					assertDoesNotThrow(() -> parse.accept(record));
				} else {
					assertThrows(ContractException.class, () -> parse.accept(record));
				}
			}));
		}

		return tests.stream();
	}
}
