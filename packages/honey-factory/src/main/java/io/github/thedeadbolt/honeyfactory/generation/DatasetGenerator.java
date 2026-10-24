package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import io.github.thedeadbolt.honeyfactory.dataset.SampleDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.Futures;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.NaturalGenerator;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.SceneBase;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerator;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerators;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Generates one dataset through a {@link GenerationEnvironment}: every accepted-scene slot
 * in order, re-sampling viewpoints and retrying scenes until the quotas are met or a limit
 * stops the run.
 *
 * <ul>
 *   <li>Before the dataset directory is created, the output file system must hold the whole
 *       dataset uncompressed plus the free-space reserve ({@link StorageBudget}), and a
 *       natural base's context padding must cover the render distance.</li>
 *   <li>On a natural base, each slot's source patch is prepared when its first slot runs
 *       and serves every attempt of the slots that share it, while the next patch is
 *       prepared ahead in the background; a patch with no acceptable candidate fails the
 *       run.</li>
 *   <li>A scene attempt is accepted once every viewpoint has an accepted capture. A
 *       viewpoint is re-drawn up to the configured attempts; when they run out, or a capture
 *       fails, the attempt is rejected, its images deleted and the slot retried with the
 *       next attempt index.</li>
 *   <li>The run fails when the scene attempt limit is reached, when one more scene would
 *       cross the free-space reserve, when it is cancelled, or on any other environment or
 *       file error. Accepted scenes stay on disk for diagnosis.</li>
 *   <li>At the end the dataset is validated against its completed manifest; only a passing
 *       dataset is marked {@code complete}. A failed run is validated as it stands and
 *       marked {@code failed}, with the validation result in its integrity section.</li>
 * </ul>
 *
 * <p>Cancellation is a request, honoured at the next safe boundary: before the next
 * environment operation (reset, viewpoint change or capture) or scene attempt. An
 * operation already running always finishes, so the world is never left mid-reset and a
 * capture is never cut short. The interrupted attempt is then abandoned: its images are
 * deleted, nothing of it reaches the indexes, and it counts as failed. A scene whose last
 * capture finished before the boundary is still accepted, so cancelling after the final
 * scene completes the dataset normally; otherwise the dataset is marked {@code failed}
 * with the failure {@value #CANCELLED} and never presented as complete.
 *
 * <p>Not thread-safe: run it on the thread the environment completes its futures on. Only
 * image encoding and writing run elsewhere, on its own worker threads.
 */
public final class DatasetGenerator {
	public static final String TOOL = "honey-factory";
	public static final String SCENE_ATTEMPT_LIMIT = "scene_attempt_limit";
	public static final String CANCELLED = "cancelled";
	public static final String CAPTURE_FAILED = "capture_failed";
	public static final String VIEWPOINT_PLACEMENT = "viewpoint_placement";
	public static final String STORAGE_RESERVE = "storage_reserve";

	/** The finished dataset; {@code failure} is null exactly when it is complete. */
	public record Result(DatasetManifest manifest, Path directory, @Nullable String failure, List<String> validationErrors) {
	}

	/** Counts so far; {@code elapsed} runs from {@link #start()}. */
	public record Progress(
		int requestedScenes,
		int acceptedScenes,
		int attemptedScenes,
		int acceptedSamples,
		int rejectedScenes,
		Map<String, Integer> rejectionReasons,
		Duration elapsed
	) {
	}

	/** Ends the run deliberately; the message is the recorded failure. */
	private static final class Stop extends RuntimeException {
		Stop(String message) {
			super(message, null, false, false);
		}
	}

	/** How one scene attempt ended. */
	private sealed interface SceneOutcome {
		record Accepted(SceneRecord scene, List<SampleRecord> samples) implements SceneOutcome {
		}

		record Rejected(String reason) implements SceneOutcome {
		}
	}

	/** A placeable viewpoint and the draw that produced it. */
	private record Draw(int attempt, ViewpointPlan plan) {
	}

	@FunctionalInterface
	private interface IoAction {
		void run() throws IOException;
	}

	private final ResolvedConfig config;
	private final GenerationEnvironment environment;
	private final Clock clock;
	private final SceneGenerator generator;
	private final ArenaLayout arena;
	private final CaptureProjection projection;
	private final RecordFactory records;
	private final GenerationTally tally;
	private final StorageBudget storage;
	private @Nullable DatasetWriter writer;
	private @Nullable DatasetManifest manifest;
	private @Nullable SceneAttempt running;
	private boolean cancelled;
	private @Nullable Instant started;
	private final GenerationTimings timings = new GenerationTimings();
	/** Encodes and writes accepted images off the generator's thread. */
	private final ExecutorService writers = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8), runnable -> {
		Thread thread = new Thread(runnable, "Honey Factory image writer");
		thread.setDaemon(true);
		return thread;
	});
	/** Requested source patches by index: the current one and the next, prepared ahead. */
	private final Map<Integer, CompletableFuture<PreparedPatch>> patches = new TreeMap<>();

	public DatasetGenerator(ResolvedConfig config, GenerationEnvironment environment, Clock clock) {
		this(config, environment, clock, StorageBudget.DiskSpace.SYSTEM);
	}

	public DatasetGenerator(ResolvedConfig config, GenerationEnvironment environment, Clock clock, StorageBudget.DiskSpace disk) {
		this.config = config;
		this.environment = environment;
		this.clock = clock;
		this.generator = SceneGenerators.forConfig(config);
		this.arena = ArenaLayout.of(config.scene().base(), config.arena());
		ResolvedConfig.Capture capture = config.capture();
		this.projection = CaptureProjection.of(capture.mode(), capture.width(), capture.height(), capture.cubeFaceSize());
		this.records = new RecordFactory(config, this.arena, this.projection.mode());
		this.tally = new GenerationTally(config.quotas().acceptedScenes());
		this.storage = new StorageBudget(config, disk);
	}

	/**
	 * Creates the dataset directory and starts generating.
	 *
	 * @throws IOException if the storage preflight fails or the directory cannot be created,
	 *     including when it already exists
	 */
	public CompletableFuture<Result> start() throws IOException {
		if (this.writer != null) {
			throw new IllegalStateException("A generator runs once");
		}

		this.storage.preflight();
		if (this.config.scene().base() instanceof SceneBase.NaturalTerrain natural) {
			int distance = this.environment.renderEnvironment().renderDistanceChunks();
			if (natural.contextPadding() < 16 * distance) {
				throw new IllegalStateException("context_padding " + natural.contextPadding() + " leaves the void visible at render distance " + distance
					+ "; use at least " + 16 * distance + " blocks or lower the render distance");
			}
		}

		this.started = this.clock.instant();
		this.manifest = DatasetManifest.generating(
			this.config.toJson(),
			this.clock.instant(),
			this.environment.software(),
			this.environment.renderEnvironment(),
			new DatasetManifest.Provenance(TOOL, List.of(), List.of())
		);
		this.writer = DatasetWriter.create(this.config.outputRoot(), this.manifest);
		Iterator<SceneSlots.Slot> slots = SceneSlots.plan(this.config).iterator();
		return CompletableFuture.completedFuture(slots).thenCompose(this::runSlots).handle((ignored, error) -> finish(error));
	}

	/** Requests cancellation at the next safe boundary; see the class description. */
	public void cancel() {
		this.cancelled = true;
	}

	public Progress progress() {
		Duration elapsed = this.started == null ? Duration.ZERO : Duration.between(this.started, this.clock.instant());
		return this.tally.progress(elapsed);
	}

	private CompletableFuture<Void> runSlots(Iterator<SceneSlots.Slot> slots) {
		if (!slots.hasNext()) {
			return CompletableFuture.completedFuture(null);
		}

		return runSlot(slots.next(), 0).thenCompose(ignored -> runSlots(slots));
	}

	/** Attempts {@code slot} until one attempt is accepted. */
	private CompletableFuture<Void> runSlot(SceneSlots.Slot slot, int attemptIndex) {
		Stop stop = stopReason();
		if (stop != null) {
			return CompletableFuture.failedFuture(stop);
		}

		this.tally.attempt();
		SceneDescriptor descriptor = SceneDescriptor.of(this.config, slot, attemptIndex);
		return scene(slot, descriptor)
			.exceptionallyCompose(error -> {
				// No attempt is running yet, so finish() would not count this one.
				this.tally.fail();
				return CompletableFuture.failedFuture(error);
			})
			.thenCompose(scene -> runAttempt(slot, attemptIndex, new SceneAttempt(descriptor, scene)));
	}

	/** The attempt's scene; on a natural base, once its slot's source patch is prepared. */
	private CompletableFuture<GeneratedScene> scene(SceneSlots.Slot slot, SceneDescriptor descriptor) {
		if (!(this.generator instanceof NaturalGenerator natural)) {
			return CompletableFuture.completedFuture(this.generator.scene(descriptor.seeds(), slot.targetPresent()));
		}

		int patches = Math.ceilDiv(this.config.quotas().acceptedScenes(), natural.base().scenesPerPatch());
		this.patches.keySet().removeIf(index -> index < slot.group());
		CompletableFuture<PreparedPatch> patch = patch(natural, slot.group());
		if (slot.group() + 1 < patches) {
			// Generate the next patch's terrain while this one's scenes capture.
			patch(natural, slot.group() + 1);
		}

		long waiting = System.nanoTime();
		return patch.thenApply(prepared -> {
			this.timings.add(GenerationTimings.Phase.PATCH_WAIT, System.nanoTime() - waiting);
			return natural.scene(descriptor.seeds(), slot.targetPresent(), prepared);
		});
	}

	/** Patch {@code index}, requested on first use; requests are cancelled with the run. */
	private CompletableFuture<PreparedPatch> patch(NaturalGenerator natural, int index) {
		return this.patches.computeIfAbsent(index, key -> request(GenerationTimings.Phase.PREPARE_PATCH, () -> this.environment.preparePatch(
			new GenerationEnvironment.PatchRequest(this.config.seed(), natural.base(), key, this.config.target().blockId())
		)));
	}

	private CompletableFuture<Void> runAttempt(SceneSlots.Slot slot, int attemptIndex, SceneAttempt attempt) {
		this.running = attempt;
		return attempt.run().thenCompose(outcome -> switch (outcome) {
			case SceneOutcome.Accepted accepted -> {
				io(() -> this.writer.appendScene(accepted.scene(), accepted.samples()));
				this.tally.accept(slot.split(), accepted.samples().size());
				this.running = null;
				yield CompletableFuture.completedFuture(null);
			}
			case SceneOutcome.Rejected rejected -> {
				io(() -> this.writer.deleteImages(attempt.files));
				this.tally.reject(rejected.reason());
				this.running = null;
				HoneyFactory.LOGGER.info("Rejected {} attempt {}: {}", slot.sceneId(), attemptIndex, rejected.reason());
				yield runSlot(slot, attemptIndex + 1);
			}
		});
	}

	private @Nullable Stop stopReason() {
		if (this.cancelled) {
			return new Stop(CANCELLED);
		}

		int limit = this.config.quotas().sceneAttemptLimit();
		if (this.tally.attempted() >= limit) {
			return new Stop(SCENE_ATTEMPT_LIMIT + " of " + limit + " reached");
		}

		try {
			String storage = this.storage.exhausted();
			return storage == null ? null : new Stop(STORAGE_RESERVE + ": " + storage);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Issues an environment operation, unless cancellation was requested since the last one. */
	private <T> CompletableFuture<T> request(GenerationTimings.Phase phase, Supplier<CompletableFuture<T>> operation) {
		if (this.cancelled) {
			return CompletableFuture.failedFuture(new Stop(CANCELLED));
		}

		long started = System.nanoTime();
		return operation.get().whenComplete((value, error) -> this.timings.add(phase, System.nanoTime() - started));
	}

	/** Where the run's time went so far. */
	public GenerationTimings timings() {
		return this.timings;
	}

	private Result finish(@Nullable Throwable error) {
		long validating = System.nanoTime();
		try {
			return finishAndValidate(error);
		} finally {
			this.timings.add(GenerationTimings.Phase.VALIDATE, System.nanoTime() - validating);
			this.writers.shutdown();
			HoneyFactory.LOGGER.info("Dataset timings: {}", this.timings);
		}
	}

	private Result finishAndValidate(@Nullable Throwable error) {
		String failure = failureMessage(error);
		abandonRunningAttempt();
		DatasetManifest.Counts counts = this.tally.counts();
		try {
			if (failure != null) {
				DatasetManifest partial = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts, null);
				return failed(counts, DatasetValidator.validate(this.writer.root(), partial), failure);
			}

			return complete(counts);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static @Nullable String failureMessage(@Nullable Throwable error) {
		if (error == null) {
			return null;
		}

		Throwable cause = Futures.cause(error);
		if (cause instanceof Stop stop) {
			return stop.getMessage();
		}

		HoneyFactory.LOGGER.error("Dataset generation failed", cause);
		return cause instanceof EnvironmentException environmentError ? environmentError.toString() : String.valueOf(cause);
	}

	/** An attempt interrupted by the end of the run is neither accepted nor rejected. */
	private void abandonRunningAttempt() {
		SceneAttempt attempt = this.running;
		if (attempt != null) {
			this.tally.fail();
			attempt.awaitWritesQuietly();
			io(() -> this.writer.deleteImages(attempt.files));
			this.running = null;
		}
	}

	/** Validates against the completed manifest, writes it, and validates what was stored. */
	private Result complete(DatasetManifest.Counts counts) throws IOException {
		DatasetManifest candidate = this.manifest.withState(DatasetManifest.Lifecycle.COMPLETE, this.clock.instant(), this.tally.splitCounts(), counts, null);
		DatasetValidator.Report report = DatasetValidator.validate(this.writer.root(), candidate);
		if (!report.passed()) {
			return failed(counts, report, "validation found " + report.errors().size() + " errors");
		}

		DatasetManifest done = candidate.withState(DatasetManifest.Lifecycle.COMPLETE, candidate.completedAtUtc(), candidate.splitCounts(), counts, integrity(report));
		this.writer.writeManifest(done);
		DatasetValidator.Report stored = DatasetValidator.validate(this.writer.root());
		if (!stored.passed()) {
			return failed(counts, stored, "the stored dataset failed validation");
		}

		return new Result(done, this.writer.root(), null, List.of());
	}

	private Result failed(DatasetManifest.Counts counts, DatasetValidator.Report report, String failure) throws IOException {
		DatasetManifest manifest = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts, integrity(report));
		this.writer.writeManifest(manifest);
		report.errors().forEach(message -> HoneyFactory.LOGGER.warn("Dataset validation: {}", message));
		HoneyFactory.LOGGER.warn("Dataset {} failed: {}", manifest.datasetId(), failure);
		return new Result(manifest, this.writer.root(), failure, report.errors());
	}

	private static DatasetManifest.Integrity integrity(DatasetValidator.Report report) {
		return new DatasetManifest.Integrity(report.scenesDigest(), report.samplesDigest(), report.passed(), report.errors().size());
	}

	private static void io(IoAction action) {
		try {
			action.run();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * One attempt at one scene slot: build it at viewpoint 0's first placeable draw, then
	 * capture every viewpoint's current draw together in one batch, the camera moving on the
	 * client only. Each rejected viewpoint moves on to its next placeable draw and the round
	 * repeats with those, so every viewpoint ends on the same draw as if captured alone.
	 * Accepted images are encoded on worker threads while later rounds capture.
	 */
	private final class SceneAttempt {
		private final SceneDescriptor descriptor;
		private final GeneratedScene scene;
		private final int @Nullable [] target;
		private final @Nullable BlockBox targetCell;
		private final Map<Integer, CompletableFuture<SampleRecord>> samples = new TreeMap<>();
		private final List<String> files = java.util.Collections.synchronizedList(new ArrayList<>());
		private final Map<Integer, Integer> draws = new TreeMap<>();
		private String lastRejection = VIEWPOINT_PLACEMENT;

		SceneAttempt(SceneDescriptor descriptor, GeneratedScene scene) {
			this.descriptor = descriptor;
			this.scene = scene;
			this.target = scene.plan().targetPosition(DatasetGenerator.this.arena);
			this.targetCell = this.target == null ? null : BlockBox.cell(this.target);
		}

		/** Builds the scene at the first placeable viewpoint, then captures every viewpoint. */
		CompletableFuture<SceneOutcome> run() {
			Map<Integer, Draw> pending = new TreeMap<>();
			for (int index = 0; index < DatasetGenerator.this.config.viewpoints().count(); index++) {
				Draw draw = nextPlaceable(index, 0);
				if (draw == null) {
					return CompletableFuture.completedFuture(new SceneOutcome.Rejected(VIEWPOINT_PLACEMENT));
				}

				pending.put(index, draw);
			}

			CameraPose pose = pending.get(0).plan().pose(DatasetGenerator.this.arena);
			return request(GenerationTimings.Phase.RESET, () -> DatasetGenerator.this.environment.reset(DatasetGenerator.this.arena, this.scene.plan(), pose))
				.thenCompose(ready -> round(pending, ready))
				.exceptionallyCompose(this::rejectOnCaptureFailure);
		}

		/** Captures every pending viewpoint at once, then repeats with the re-drawn ones. */
		private CompletableFuture<SceneOutcome> round(Map<Integer, Draw> pending, GenerationEnvironment.SceneReady ready) {
			List<Integer> indices = List.copyOf(pending.keySet());
			List<CameraPose> poses = indices.stream().map(index -> pending.get(index).plan().pose(DatasetGenerator.this.arena)).toList();
			return request(GenerationTimings.Phase.OBSERVE, () -> DatasetGenerator.this.environment.observeAll(DatasetGenerator.this.projection, this.target, poses))
				.thenCompose(observations -> {
					Map<Integer, Draw> next = new TreeMap<>();
					for (int i = 0; i < indices.size(); i++) {
						int index = indices.get(i);
						Draw draw = pending.get(index);
						GenerationEnvironment.ViewReady view = new GenerationEnvironment.ViewReady(observations.get(i).operationId(), poses.get(i), ready.settledTicks());
						if (accept(index, draw, view, observations.get(i))) {
							continue;
						}

						Draw redraw = nextPlaceable(index, draw.attempt() + 1);
						if (redraw == null) {
							awaitWrites();
							return CompletableFuture.completedFuture(new SceneOutcome.Rejected(this.lastRejection));
						}

						next.put(index, redraw);
					}

					return next.isEmpty() ? CompletableFuture.completedFuture(finish(ready)) : round(next, ready);
				});
		}

		/** The accepted scene, once every image is written. */
		private SceneOutcome finish(GenerationEnvironment.SceneReady ready) {
			List<SampleRecord> records = awaitWrites();
			List<Integer> accepted = List.copyOf(this.draws.values());
			SceneRecord scene = DatasetGenerator.this.records.scene(this.descriptor, this.scene, accepted, ready, records.size());
			return new SceneOutcome.Accepted(scene, records);
		}

		/** Waits for every image write so far and returns their samples in viewpoint order. */
		private List<SampleRecord> awaitWrites() {
			long waiting = System.nanoTime();
			try {
				return this.samples.values().stream().map(CompletableFuture::join).toList();
			} catch (java.util.concurrent.CompletionException e) {
				throw e.getCause() instanceof UncheckedIOException io ? io : e;
			} finally {
				DatasetGenerator.this.timings.add(GenerationTimings.Phase.WRITE_WAIT, System.nanoTime() - waiting);
			}
		}

		/** The first placeable draw of viewpoint {@code index} from {@code fromAttempt} on, or null once the attempts run out. */
		private @Nullable Draw nextPlaceable(int index, int fromAttempt) {
			for (int attempt = fromAttempt; attempt < DatasetGenerator.this.config.viewpoints().attemptsPerViewpoint(); attempt++) {
				if (DatasetGenerator.this.generator.viewpoint(this.scene, index, attempt) instanceof ViewpointCandidate.Placeable placeable) {
					return new Draw(attempt, placeable.plan());
				}
			}

			return null;
		}

		/** Starts writing the images and recording the sample when the capture is acceptable. */
		private boolean accept(int index, Draw draw, GenerationEnvironment.ViewReady view, GenerationEnvironment.Observation observation) {
			CameraFrame camera = CameraFrame.of(view.pose(), DatasetGenerator.this.config.viewpoints().eyeHeightBlocks());
			ObservationCheck.Outcome outcome = ObservationCheck.check(DatasetGenerator.this.config.visibility(), DatasetGenerator.this.projection, camera, this.targetCell, observation.images());
			return switch (outcome) {
				case ObservationCheck.Outcome.Rejected rejected -> {
					this.lastRejection = rejected.reason();
					HoneyFactory.LOGGER.debug("Viewpoint {} of {} rejected: {}", index, this.descriptor.sceneId(), rejected.detail());
					yield false;
				}
				case ObservationCheck.Outcome.Accepted accepted -> {
					SampleDescriptor sample = this.descriptor.sample(index);
					ScenePlan plan = this.scene.plan();
					this.samples.put(index, CompletableFuture.supplyAsync(() -> {
						long writing = System.nanoTime();
						try {
							DatasetWriter.SampleFiles written = DatasetGenerator.this.writer.writeSample(sample, observation.images());
							this.files.addAll(written.paths());
							return DatasetGenerator.this.records.sample(sample, written, view, observation, accepted.target(), plan);
						} catch (IOException e) {
							throw new UncheckedIOException(e);
						} finally {
							DatasetGenerator.this.timings.add(GenerationTimings.Phase.WRITE, System.nanoTime() - writing);
						}
					}, DatasetGenerator.this.writers));
					this.draws.put(index, draw.attempt());
					yield true;
				}
			};
		}

		/** A capture failure leaves the coordinator FAILED, so only this attempt is rejected; a new reset continues. */
		private CompletableFuture<SceneOutcome> rejectOnCaptureFailure(Throwable error) {
			Throwable cause = Futures.cause(error);
			if (cause instanceof EnvironmentException environmentError && environmentError.reason() == EnvironmentException.Reason.CAPTURE_FAILED) {
				awaitWritesQuietly();
				HoneyFactory.LOGGER.warn("Capture failed in {}: {}", this.descriptor.sceneId(), environmentError.getMessage());
				return CompletableFuture.completedFuture(new SceneOutcome.Rejected(CAPTURE_FAILED));
			}

			awaitWritesQuietly();
			return CompletableFuture.failedFuture(cause);
		}

		/** Waits for writes in flight before their files are deleted, ignoring their failures. */
		void awaitWritesQuietly() {
			for (CompletableFuture<SampleRecord> write : this.samples.values()) {
				try {
					write.join();
				} catch (RuntimeException ignored) {
					// The attempt is abandoned; its files are deleted whatever their state.
				}
			}
		}
	}
}
