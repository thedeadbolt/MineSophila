package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.generation.DatasetRuns;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.WorldGuard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import org.jspecify.annotations.Nullable;

/** Shared steps of the honey-factory client game tests. */
final class GameTestSupport {
	static final int PROGRESS_TICKS = 100;

	private GameTestSupport() {
	}

	/**
	 * Whether {@code test} is left out of this run: {@code -Dhoneyfactory.gametests} lists
	 * class-name prefixes to run, such as {@code M6}, and an empty list runs every test.
	 */
	static boolean skipped(Object test) {
		String selected = System.getProperty("honeyfactory.gametests", "");
		if (selected.isBlank()) {
			return false;
		}

		String name = test.getClass().getSimpleName();
		for (String prefix : selected.split(",")) {
			if (name.startsWith(prefix.trim())) {
				return false;
			}
		}

		HoneyFactory.LOGGER.info("Skipping {}; honeyfactory.gametests={}", name, selected);
		return true;
	}

	/** Creates a world of the Honey Factory Dataset type named {@code name}; it is not claimed yet. */
	static TestSingleplayerContext datasetWorld(ClientGameTestContext context, String name) {
		return context.worldBuilder().adjustSettings(settings -> {
			settings.setName(name);
			settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(
				settings.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(ArenaDimension.PRESET)
			));
		}).create();
	}

	/** Claims a dataset world under its own name. */
	static void claim(TestSingleplayerContext world, String name) {
		world.getServer().runOnServer(server -> WorldGuard.claim(server, name));
	}

	/** Pins the client settings that affect captured pixels in the tests. */
	static void pinCaptureSettings(ClientGameTestContext context) {
		context.runOnClient(minecraft -> {
			minecraft.options.improvedTransparency().set(true);
			minecraft.options.cloudStatus().set(CloudStatus.OFF);
			minecraft.options.cutoutLeaves().set(true);
		});
	}

	/** The repository root, passed by Gradle, for golden data and review output. */
	static Path repoRoot() {
		String root = System.getProperty("honeyfactory.repoRoot");
		if (root == null) {
			throw new IllegalStateException("Run through Gradle's runClientGameTest, which sets honeyfactory.repoRoot");
		}

		return Path.of(root);
	}

	/**
	 * Writes {@code config} to the game directory, starts generating it by command and waits
	 * until the run ends. Once {@code cancelWhen} accepts the progress, the run is cancelled
	 * by command too. Progress is logged every {@value #PROGRESS_TICKS} ticks.
	 */
	static DatasetGenerator.Result generate(ClientGameTestContext context, String name, String config, Predicate<DatasetGenerator.Progress> cancelWhen, int timeoutTicks) {
		String configName = name + ".json";
		Path gameDirectory = context.computeOnClient(minecraft -> minecraft.gameDirectory.toPath());
		try {
			Files.writeString(gameDirectory.resolve(configName), config, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		DatasetGenerator.Result previous = context.computeOnClient(minecraft -> DatasetRuns.last());
		context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("honeyfactory_dataset generate " + configName));
		boolean cancelled = false;
		for (int waited = 0; !context.computeOnClient(minecraft -> DatasetRuns.running() == null && DatasetRuns.last() != previous); waited += PROGRESS_TICKS) {
			if (waited >= timeoutTicks) {
				throw new AssertionError(name + ": generation did not finish within " + timeoutTicks + " ticks");
			}

			context.waitTicks(PROGRESS_TICKS);
			DatasetGenerator.Progress progress = context.computeOnClient(minecraft -> DatasetRuns.running() == null ? null : DatasetRuns.running().progress());
			HoneyFactory.LOGGER.info("{}: {} {}", name, progress, context.computeOnClient(minecraft -> EnvironmentRuntime.get().status()));
			if (!cancelled && progress != null && cancelWhen.test(progress)) {
				context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("honeyfactory_dataset cancel"));
				cancelled = true;
			}
		}

		return context.computeOnClient(minecraft -> DatasetRuns.last());
	}

	static void deleteRecursively(Path path) {
		if (!Files.exists(path)) {
			return;
		}

		try (Stream<Path> files = Files.walk(path)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static <T> T await(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		CompletableFuture<T> future = context.computeOnClient(minecraft -> request.apply(EnvironmentRuntime.get()));
		context.waitFor(minecraft -> future.isDone(), 1200);
		return future.join();
	}

	static <T> EnvironmentException.@Nullable Reason rejection(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		try {
			await(context, request);
			return null;
		} catch (CompletionException e) {
			if (e.getCause() instanceof EnvironmentException environment) {
				return environment.reason();
			}

			throw e;
		}
	}
}
