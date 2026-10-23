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
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.Futures;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.GeneratedScene;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerator;
import io.github.thedeadbolt.honeyfactory.scene.SceneGenerators;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Generates one dataset through a {@link GenerationEnvironment}: every accepted-scene slot
 * in order, re-sampling viewpoints and retrying scenes until the quotas are met or a limit
 * stops the run.
 *
 * <ul>
 *   <li>A scene attempt is accepted once every viewpoint has an accepted capture. A
 *       viewpoint is re-drawn up to the configured attempts; when they run out, or a capture
 *       fails, the attempt is rejected, its images deleted and the slot retried with the
 *       next attempt index.</li>
 *   <li>The run fails when the scene attempt limit is reached, when it is cancelled, or on
 *       any other environment or file error. Partial data stays for diagnosis.</li>
 *   <li>At the end the dataset is validated against its completed manifest; only a passing
 *       dataset is marked {@code complete}.</li>
 * </ul>
 *
 * <p>Not thread-safe: run it on the thread the environment completes its futures on.
 */
public final class DatasetGenerator {
	public static final String TOOL = "honey-factory";
	public static final String SCENE_ATTEMPT_LIMIT = "scene_attempt_limit";
	public static final String CANCELLED = "cancelled";
	public static final String CAPTURE_FAILED = "capture_failed";
	public static final String VIEWPOINT_PLACEMENT = "viewpoint_placement";

	/** The finished dataset; {@code failure} is null exactly when it is complete. */
	public record Result(DatasetManifest manifest, Path directory, @Nullable String failure, List<String> validationErrors) {
	}

	public record Progress(int requestedScenes, int acceptedScenes, int attemptedScenes, int acceptedSamples, int rejectedScenes) {
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
	private @Nullable DatasetWriter writer;
	private @Nullable DatasetManifest manifest;
	private @Nullable SceneAttempt running;
	private boolean cancelled;

	public DatasetGenerator(ResolvedConfig config, GenerationEnvironment environment, Clock clock) {
		this.config = config;
		this.environment = environment;
		this.clock = clock;
		this.generator = SceneGenerators.forConfig(config);
		this.arena = ArenaLayout.of(config.arena());
		ResolvedConfig.Capture capture = config.capture();
		this.projection = CaptureProjection.of(capture.mode(), capture.width(), capture.height(), capture.cubeFaceSize());
		this.records = new RecordFactory(config, this.arena, this.projection.mode());
		this.tally = new GenerationTally(config.quotas().acceptedScenes());
	}

	/**
	 * Creates the dataset directory and starts generating.
	 *
	 * @throws IOException if the directory cannot be created, including when it already exists
	 */
	public CompletableFuture<Result> start() throws IOException {
		if (this.writer != null) {
			throw new IllegalStateException("A generator runs once");
		}

		this.manifest = DatasetManifest.generating(
			this.config.toJson(),
			this.clock.instant(),
			this.environment.software(),
			this.environment.renderEnvironment(),
			new DatasetManifest.Provenance(TOOL, List.of(), List.of())
		);
		this.writer = DatasetWriter.create(this.config.outputRoot(), this.manifest);
		return runSlots(SceneSlots.plan(this.config).iterator()).handle((ignored, error) -> finish(error));
	}

	/** Stops before the next scene or viewpoint; the dataset is marked failed. */
	public void cancel() {
		this.cancelled = true;
	}

	public Progress progress() {
		return this.tally.progress();
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
		SceneAttempt attempt = new SceneAttempt(descriptor, this.generator.scene(descriptor.seeds(), slot.targetPresent()));
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
		return this.tally.attempted() >= limit ? new Stop(SCENE_ATTEMPT_LIMIT + " of " + limit + " reached") : null;
	}

	private Result finish(@Nullable Throwable error) {
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

	/** One attempt at one scene slot: build it, then capture every viewpoint in order. */
	private final class SceneAttempt {
		private final SceneDescriptor descriptor;
		private final GeneratedScene scene;
		private final int @Nullable [] target;
		private final @Nullable BlockBox targetCell;
		private final List<SampleRecord> samples = new ArrayList<>();
		private final List<String> files = new ArrayList<>();
		private GenerationEnvironment.@Nullable ViewReady current;
		private String lastRejection = VIEWPOINT_PLACEMENT;

		SceneAttempt(SceneDescriptor descriptor, GeneratedScene scene) {
			this.descriptor = descriptor;
			this.scene = scene;
			this.target = scene.plan().targetPosition(DatasetGenerator.this.arena);
			this.targetCell = this.target == null ? null : BlockBox.cell(this.target);
		}

		/** Builds the scene at the first placeable viewpoint, then captures every viewpoint. */
		CompletableFuture<SceneOutcome> run() {
			Draw first = nextPlaceable(0, 0);
			if (first == null) {
				return CompletableFuture.completedFuture(new SceneOutcome.Rejected(VIEWPOINT_PLACEMENT));
			}

			return DatasetGenerator.this.environment.reset(DatasetGenerator.this.arena, this.scene.plan(), first.plan().pose())
				.thenCompose(ready -> {
					this.current = new GenerationEnvironment.ViewReady(ready.operationId(), first.plan().pose(), ready.settledTicks());
					return capture(0, first, ready);
				})
				.exceptionallyCompose(this::rejectOnCaptureFailure);
		}

		/** Captures viewpoint {@code index} from {@code draw}, then moves on or re-draws. */
		private CompletableFuture<SceneOutcome> capture(int index, Draw draw, GenerationEnvironment.SceneReady ready) {
			return place(draw.plan())
				.thenCompose(view -> DatasetGenerator.this.environment.observe(DatasetGenerator.this.projection, this.target)
					.thenCompose(observation -> accept(index, view, observation)
						? nextViewpoint(index + 1, 0, ready)
						: nextViewpoint(index, draw.attempt() + 1, ready)));
		}

		private CompletableFuture<SceneOutcome> nextViewpoint(int index, int fromAttempt, GenerationEnvironment.SceneReady ready) {
			if (index == DatasetGenerator.this.config.viewpoints().count()) {
				SceneRecord scene = DatasetGenerator.this.records.scene(this.descriptor, this.scene.plan(), this.target, ready, this.samples.size());
				return CompletableFuture.completedFuture(new SceneOutcome.Accepted(scene, List.copyOf(this.samples)));
			}

			if (DatasetGenerator.this.cancelled) {
				return CompletableFuture.failedFuture(new Stop(CANCELLED));
			}

			Draw draw = nextPlaceable(index, fromAttempt);
			return draw == null
				? CompletableFuture.completedFuture(new SceneOutcome.Rejected(this.lastRejection))
				: capture(index, draw, ready);
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

		/** Moves the camera unless it is already at the plan's pose. */
		private CompletableFuture<GenerationEnvironment.ViewReady> place(ViewpointPlan plan) {
			if (this.current != null && this.current.pose().equals(plan.pose())) {
				return CompletableFuture.completedFuture(this.current);
			}

			return DatasetGenerator.this.environment.setViewpoint(plan.pose()).thenApply(view -> this.current = view);
		}

		/** Writes the images and records the sample when the capture is acceptable. */
		private boolean accept(int index, GenerationEnvironment.ViewReady view, GenerationEnvironment.Observation observation) {
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
					io(() -> {
						DatasetWriter.SampleFiles written = DatasetGenerator.this.writer.writeSample(sample, observation.images());
						this.files.addAll(written.paths());
						this.samples.add(DatasetGenerator.this.records.sample(sample, written, view, observation, accepted.target(), this.scene.plan()));
					});
					yield true;
				}
			};
		}

		/** A capture failure leaves the coordinator FAILED, so only this attempt is rejected; a new reset continues. */
		private CompletableFuture<SceneOutcome> rejectOnCaptureFailure(Throwable error) {
			Throwable cause = Futures.cause(error);
			if (cause instanceof EnvironmentException environmentError && environmentError.reason() == EnvironmentException.Reason.CAPTURE_FAILED) {
				HoneyFactory.LOGGER.warn("Capture failed in {}: {}", this.descriptor.sceneId(), environmentError.getMessage());
				return CompletableFuture.completedFuture(new SceneOutcome.Rejected(CAPTURE_FAILED));
			}

			return CompletableFuture.failedFuture(cause);
		}
	}
}
