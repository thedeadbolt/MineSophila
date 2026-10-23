package io.github.thedeadbolt.honeyfactory;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the language-neutral schemas and shared contract fixtures. */
public final class Fixtures {
	private Fixtures() {
	}

	public static Path repoRoot() {
		String root = System.getProperty("honeyfactory.repoRoot");
		if (root == null) {
			throw new IllegalStateException("Run through Gradle, which sets honeyfactory.repoRoot");
		}

		return Path.of(root);
	}

	public static Path contract() {
		return repoRoot().resolve("tests/integration/fixtures/honey-factory/1.0");
	}

	public static Path vectors() {
		return repoRoot().resolve("tests/integration/fixtures/honey-factory/vectors");
	}

	public static JsonObject json(Path path) {
		return JsonFields.parseObject(text(path));
	}

	public static String text(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static byte[] bytes(Path path) {
		try {
			return Files.readAllBytes(path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
