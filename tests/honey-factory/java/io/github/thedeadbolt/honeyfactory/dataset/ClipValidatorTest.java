package io.github.thedeadbolt.honeyfactory.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import io.github.thedeadbolt.honeyfactory.JsonPatch;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/** Runs the shared mini clip dataset and its cross-record cases through {@link ClipValidator}. */
class ClipValidatorTest {
	@TempDir
	Path temp;

	private Path copy() {
		Path source = Fixtures.contract().resolve("clips");
		Path target;
		try {
			// Dynamic cases share one temporary folder, so each copy gets its own.
			target = Files.createTempDirectory(this.temp, "clips");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		try (Stream<Path> files = Files.walk(source)) {
			for (Path file : files.toList()) {
				Path destination = target.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(destination);
				} else {
					Files.copy(file, destination);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		return target;
	}

	@Test
	void sharedClipDatasetIsValid() throws IOException {
		ClipValidator.Report report = ClipValidator.validate(copy());
		assertEquals(List.of(), report.errors());
		assertEquals(4, report.clips().size());
	}

	@TestFactory
	Stream<DynamicTest> sharedCases() {
		List<DynamicTest> tests = new ArrayList<>();
		for (JsonElement element : Fixtures.json(Fixtures.contract().resolve("clip-cases.json")).getAsJsonArray("cases")) {
			JsonObject testCase = element.getAsJsonObject();
			tests.add(DynamicTest.dynamicTest(testCase.get("name").getAsString(), () -> {
				Path root = copy();
				Map<String, List<JsonObject>> files = new HashMap<>();
				for (JsonElement editElement : testCase.getAsJsonArray("edits")) {
					JsonObject edit = editElement.getAsJsonObject();
					String name = edit.get("file").getAsString();
					List<JsonObject> lines = files.computeIfAbsent(name, key -> new ArrayList<>(Jsonl.read(Fixtures.bytes(root.resolve(key)), key)));
					if (edit.has("append_copy_of")) {
						lines.add(lines.get(edit.get("append_copy_of").getAsInt()).deepCopy());
					} else if (edit.has("remove_line")) {
						lines.remove(edit.get("remove_line").getAsInt());
					} else {
						int line = edit.get("line").getAsInt();
						lines.set(line, JsonPatch.apply(lines.get(line), edit.getAsJsonArray("patch")));
					}
				}

				for (Map.Entry<String, List<JsonObject>> file : files.entrySet()) {
					StringBuilder text = new StringBuilder();
					file.getValue().forEach(line -> text.append(line).append('\n'));
					Files.writeString(root.resolve(file.getKey()), text, StandardCharsets.UTF_8);
				}

				List<String> errors = ClipValidator.validate(root).errors();
				String expected = testCase.get("expect_error").getAsString();
				assertTrue(errors.stream().anyMatch(error -> error.contains(expected)), "expected an error containing '" + expected + "', got " + errors);
			}));
		}

		return tests.stream();
	}

	@Test
	void videosMustHoldEveryFrame() throws IOException, InterruptedException {
		Path root = copy();
		String rgb = Files.readAllLines(root.resolve(DatasetWriter.CLIPS)).getFirst().replaceAll(".*\"rgb\":\\{\"path\":\"([^\"]+)\".*", "$1");
		Path video = root.resolve(rgb);
		Path shorter = video.resolveSibling("short.mkv");
		Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-i", video.toString(), "-frames:v", "12", "-c:v", "libx264rgb", "-qp", "0", shorter.toString())
			.redirectErrorStream(true).start();
		process.getInputStream().readAllBytes();
		assertEquals(0, process.waitFor());
		Files.move(shorter, video, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		List<String> errors = ClipValidator.validate(root).errors();
		assertTrue(errors.stream().anyMatch(error -> error.contains("video holds 12 frames")), errors.toString());
	}

	@Test
	void unreferencedClipFoldersAreReported() throws IOException {
		Path root = copy();
		Path stray = root.resolve("clips/train/00/s000009-c00");
		Files.createDirectories(stray);
		Files.writeString(stray.resolve("frames.jsonl"), "");
		List<String> errors = ClipValidator.validate(root).errors();
		assertTrue(errors.stream().anyMatch(error -> error.contains("no clip references")), errors.toString());
	}
}
