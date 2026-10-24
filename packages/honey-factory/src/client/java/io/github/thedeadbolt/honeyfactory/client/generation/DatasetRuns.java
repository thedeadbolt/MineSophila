package io.github.thedeadbolt.honeyfactory.client.generation;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.generation.GenerationTimings;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Runs one dataset generation at a time in the running client. */
public final class DatasetRuns {
	private static @Nullable DatasetGenerator running;
	private static DatasetGenerator.@Nullable Result last;
	private static @Nullable GenerationTimings lastTimings;
	private static double @Nullable [] lastCaptureTotals;

	private DatasetRuns() {
	}

	/**
	 * Starts generating {@code config} in the current dataset world.
	 *
	 * @throws IllegalStateException if a generation is running or improved transparency is off
	 * @throws IOException if the dataset directory cannot be created, including when it exists
	 */
	public static CompletableFuture<DatasetGenerator.Result> start(ResolvedConfig config) throws IOException {
		if (running != null) {
			throw new IllegalStateException("a dataset generation is already running");
		}

		if (!Minecraft.getInstance().options.improvedTransparency().get()) {
			throw new IllegalStateException(CaptureService.IMPROVED_TRANSPARENCY_REQUIRED);
		}

		RuntimeGenerationEnvironment environment = new RuntimeGenerationEnvironment(EnvironmentRuntime.get());
		DatasetGenerator generator = new DatasetGenerator(config, environment, Clock.systemUTC());
		CompletableFuture<DatasetGenerator.Result> result = generator.start();
		running = generator;
		return result.whenComplete((value, error) -> {
			running = null;
			last = value;
			lastTimings = generator.timings();
			lastCaptureTotals = environment.captureTotals();
			if (error != null) {
				HoneyFactory.LOGGER.error("Dataset generation ended with an error", error);
			}
		});
	}

	public static @Nullable DatasetGenerator running() {
		return running;
	}

	public static DatasetGenerator.@Nullable Result last() {
		return last;
	}

	/** Where the last run's time went. */
	public static @Nullable GenerationTimings lastTimings() {
		return lastTimings;
	}

	/** The last run's captures, then their render, readback and reprojection seconds. */
	public static double @Nullable [] lastCaptureTotals() {
		return lastCaptureTotals == null ? null : lastCaptureTotals.clone();
	}
}
