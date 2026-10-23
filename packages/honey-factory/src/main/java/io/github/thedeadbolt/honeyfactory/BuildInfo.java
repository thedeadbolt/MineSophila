package io.github.thedeadbolt.honeyfactory;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The Git state the mod was built from, generated at build time into
 * {@code honey_factory/build-info.json}. {@code gitCommit} is null outside a Git checkout.
 */
public record BuildInfo(@Nullable String gitCommit, boolean gitDirty) {
	public static final String RESOURCE = "/honey_factory/build-info.json";

	public static BuildInfo load() {
		try (InputStream input = BuildInfo.class.getResourceAsStream(RESOURCE)) {
			if (input == null) {
				return new BuildInfo(null, true);
			}

			JsonObject json = JsonFields.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
			String commit = json.get("git_commit").isJsonNull() ? null : json.get("git_commit").getAsString();
			return new BuildInfo(commit != null && commit.matches("[0-9a-f]{40}") ? commit : null, json.get("git_dirty").getAsBoolean());
		} catch (IOException e) {
			return new BuildInfo(null, true);
		}
	}
}
