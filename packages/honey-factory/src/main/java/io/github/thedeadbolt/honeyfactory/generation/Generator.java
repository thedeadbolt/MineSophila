package io.github.thedeadbolt.honeyfactory.generation;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** One dataset generation run, of still samples ({@link DatasetGenerator}) or of motion clips ({@link ClipGenerator}). */
public interface Generator {
	/**
	 * Creates the dataset directory and starts generating.
	 *
	 * @throws IOException if the storage preflight fails or the directory cannot be created
	 */
	CompletableFuture<DatasetGenerator.Result> start() throws IOException;

	/** Requests cancellation at the next safe boundary. */
	void cancel();

	/** Counts so far; for clips, {@code acceptedSamples} counts accepted clips. */
	DatasetGenerator.Progress progress();

	/** Where the run's time went so far. */
	GenerationTimings timings();
}
