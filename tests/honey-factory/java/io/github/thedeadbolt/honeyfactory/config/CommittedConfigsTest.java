package io.github.thedeadbolt.honeyfactory.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.DatasetKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every configuration committed under packages/honey-factory/configs resolves; the M12-2 pilot's are panorama clip datasets. */
class CommittedConfigsTest {
	@Test
	void committedConfigsResolve() throws IOException {
		Path directory = Path.of(System.getProperty("honeyfactory.repoRoot", "../..")).resolve("packages/honey-factory/configs");
		List<Path> configs;
		try (Stream<Path> files = Files.list(directory)) {
			configs = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
		}

		assertFalse(configs.isEmpty());
		for (Path path : configs) {
			ResolvedConfig config = ConfigResolver.resolve(Files.readString(path, StandardCharsets.UTF_8));
			if (path.getFileName().toString().startsWith("m12-2-pilot")) {
				assertEquals(DatasetKind.CLIPS, config.kind(), path.toString());
				assertEquals(new ResolvedConfig.Capture(CaptureMode.PANORAMA_310, 2480, 1280, 1024), config.capture(), path.toString());
				assertEquals(200, config.quotas().acceptedScenes() * config.clips().clipsPerScene(), path.toString());
			}
		}
	}
}
