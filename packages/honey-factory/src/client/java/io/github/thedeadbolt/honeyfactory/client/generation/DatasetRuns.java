package io.github.thedeadbolt.honeyfactory.client.generation;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.client.runtime.FramePacing;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.generation.GenerationTimings;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Runs one dataset generation at a time in the running client. */
public final class DatasetRuns {
	private static @Nullable DatasetGenerator running;
	private static DatasetGenerator.@Nullable Result last;
	private static @Nullable GenerationTimings lastTimings;
	private static double @Nullable [] lastCaptureTotals;
	private static List<long[]> lastCaptureSamples = List.of();

	private DatasetRuns() {
	}

	/**
	 * Starts generating {@code config} in the current dataset world, with the frame rate
	 * uncapped until the run ends (see {@link FramePacing}).
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
		Minecraft minecraft = Minecraft.getInstance();
		FramePacing.Lease pacing = FramePacing.uncap(minecraft);
		CompletableFuture<DatasetGenerator.Result> result;
		try {
			result = generator.start();
		} catch (IOException | RuntimeException e) {
			pacing.close(minecraft);
			throw e;
		}

		running = generator;
		return result.whenComplete((value, error) -> {
			minecraft.execute(() -> pacing.close(minecraft));
			running = null;
			last = value;
			lastTimings = generator.timings();
			lastCaptureTotals = environment.captureTotals();
			lastCaptureSamples = environment.captureSamples();
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

	/** Per capture of the last run: the pose's wall time, then render, readback and reprojection, in nanoseconds. */
	public static List<long[]> lastCaptureSamples() {
		return lastCaptureSamples;
	}

	/** The last run's captures, then their render, readback and reprojection seconds. */
	public static double @Nullable [] lastCaptureTotals() {
		return lastCaptureTotals == null ? null : lastCaptureTotals.clone();
	}
}
