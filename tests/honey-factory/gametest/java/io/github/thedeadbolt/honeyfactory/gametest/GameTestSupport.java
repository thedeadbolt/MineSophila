package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.WorldGuard;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import org.jspecify.annotations.Nullable;

/** Shared steps of the honey-factory client game tests. */
final class GameTestSupport {
	private GameTestSupport() {
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
