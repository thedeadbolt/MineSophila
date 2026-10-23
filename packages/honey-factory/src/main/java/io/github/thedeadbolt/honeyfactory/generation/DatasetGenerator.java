package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SchemaVersion;
import io.github.thedeadbolt.honeyfactory.contract.SceneSeeds;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetValidator;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
import io.github.thedeadbolt.honeyfactory.dataset.PngImages;
import io.github.thedeadbolt.honeyfactory.dataset.SampleDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneDescriptor;
import io.github.thedeadbolt.honeyfactory.dataset.SceneSlots;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

	/** Stops the run; the message names the reason. */
	private static final class Stop extends RuntimeException {
		Stop(String message) {
			super(message, null, false, false);
		}
	}

	private record SceneOutcome(List<SampleRecord> samples, List<String> files, @Nullable SceneRecord scene, @Nullable String rejection) {
	}

	private final ResolvedConfig config;
	private final GenerationEnvironment environment;
	private final Clock clock;
	private final SceneGenerator generator;
	private final ArenaLayout arena;
	private final CaptureProjection projection;
	private final Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
	private final Map<Split, DatasetManifest.SplitCount> splitCounts = new EnumMap<>(Split.class);
	private @Nullable DatasetWriter writer;
	private @Nullable DatasetManifest manifest;
	private @Nullable SceneAttempt running;
	private int attempted;
	private int accepted;
	private int rejected;
	private int failed;
	private int acceptedSamples;
	private boolean cancelled;

	public DatasetGenerator(ResolvedConfig config, GenerationEnvironment environment, Clock clock) {
		this.config = config;
		this.environment = environment;
		this.clock = clock;
		this.generator = SceneGenerators.forConfig(config);
		this.arena = ArenaLayout.of(config.arena());
		ResolvedConfig.Capture capture = config.capture();
		this.projection = CaptureProjection.of(capture.mode(), capture.width(), capture.height(), capture.cubeFaceSize());
		for (Split split : Split.values()) {
			this.splitCounts.put(split, new DatasetManifest.SplitCount(0, 0));
		}
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
		List<SceneSlots.Slot> slots = SceneSlots.plan(this.config);
		return runSlot(slots, 0).handle((ignored, error) -> finish(error));
	}

	/** Stops before the next scene or viewpoint; the dataset is marked failed. */
	public void cancel() {
		this.cancelled = true;
	}

	public Progress progress() {
		return new Progress(this.config.quotas().acceptedScenes(), this.accepted, this.attempted, this.acceptedSamples, this.rejected);
	}

	private CompletableFuture<Void> runSlot(List<SceneSlots.Slot> slots, int index) {
		if (index == slots.size()) {
			return CompletableFuture.completedFuture(null);
		}

		return runAttempt(slots.get(index), 0).thenCompose(ignored -> runSlot(slots, index + 1));
	}

	private CompletableFuture<Void> runAttempt(SceneSlots.Slot slot, int attemptIndex) {
		if (this.cancelled) {
			return CompletableFuture.failedFuture(new Stop(CANCELLED));
		}

		if (this.attempted >= this.config.quotas().sceneAttemptLimit()) {
			return CompletableFuture.failedFuture(new Stop(SCENE_ATTEMPT_LIMIT + " of " + this.config.quotas().sceneAttemptLimit() + " reached"));
		}

		this.attempted++;
		SceneDescriptor descriptor = SceneDescriptor.of(this.config, slot, attemptIndex);
		GeneratedScene scene = this.generator.scene(slot.sceneIndex(), attemptIndex, descriptor.seeds(), slot.targetPresent());
		SceneAttempt sceneAttempt = new SceneAttempt(descriptor, scene);
		this.running = sceneAttempt;
		return sceneAttempt.run().thenCompose(outcome -> {
			try {
				if (outcome.rejection() == null) {
					this.writer.appendScene(outcome.scene(), outcome.samples());
					this.accepted++;
					this.acceptedSamples += outcome.samples().size();
					DatasetManifest.SplitCount count = this.splitCounts.get(slot.split());
					this.splitCounts.put(slot.split(), new DatasetManifest.SplitCount(count.scenes() + 1, count.samples() + outcome.samples().size()));
					this.running = null;
					return CompletableFuture.completedFuture(null);
				}

				this.writer.deleteImages(outcome.files());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			this.running = null;
			this.rejected++;
			this.rejectionReasons.merge(outcome.rejection(), 1, Integer::sum);
			HoneyFactory.LOGGER.info("Rejected {} attempt {}: {}", slot.sceneId(), attemptIndex, outcome.rejection());
			return runAttempt(slot, attemptIndex + 1);
		});
	}

	private Result finish(@Nullable Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		String failure = null;
		if (cause instanceof Stop stop) {
			failure = stop.getMessage();
		} else if (cause != null) {
			failure = cause instanceof EnvironmentException environmentError ? environmentError.toString() : String.valueOf(cause);
			HoneyFactory.LOGGER.error("Dataset generation failed", cause);
		}

		try {
			if (this.running != null) {
				// The attempt interrupted by the failure is neither accepted nor rejected.
				this.failed++;
				this.writer.deleteImages(this.running.files);
				this.running = null;
			}

			DatasetManifest.Counts counts = new DatasetManifest.Counts(
				this.config.quotas().acceptedScenes(), this.attempted, this.accepted, this.rejected, this.failed, this.acceptedSamples, Map.copyOf(this.rejectionReasons)
			);
			if (failure == null) {
				DatasetManifest complete = this.manifest.withState(DatasetManifest.Lifecycle.COMPLETE, this.clock.instant(), Map.copyOf(this.splitCounts), counts, null);
				DatasetValidator.Report report = DatasetValidator.validate(this.writer.root(), complete);
				if (report.passed()) {
					DatasetManifest done = complete.withState(DatasetManifest.Lifecycle.COMPLETE, complete.completedAtUtc(), complete.splitCounts(), counts,
						new DatasetManifest.Integrity(report.scenesDigest(), report.samplesDigest(), true, 0));
					this.writer.writeManifest(done);
					DatasetValidator.Report stored = DatasetValidator.validate(this.writer.root());
					if (stored.passed()) {
						return new Result(done, this.writer.root(), null, List.of());
					}

					return failed(counts, stored, "the stored dataset failed validation");
				}

				return failed(counts, report, "validation found " + report.errors().size() + " errors");
			}

			DatasetManifest partial = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts, null);
			return failed(counts, DatasetValidator.validate(this.writer.root(), partial), failure);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private Result failed(DatasetManifest.Counts counts, DatasetValidator.Report report, String failure) throws IOException {
		DatasetManifest manifest = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts,
			new DatasetManifest.Integrity(report.scenesDigest(), report.samplesDigest(), report.passed(), report.errors().size()));
		this.writer.writeManifest(manifest);
		report.errors().forEach(message -> HoneyFactory.LOGGER.warn("Dataset validation: {}", message));
		HoneyFactory.LOGGER.warn("Dataset {} failed: {}", manifest.datasetId(), failure);
		return new Result(manifest, this.writer.root(), failure, report.errors());
	}

	/** One attempt at one scene slot: build, then capture every viewpoint. */
	private final class SceneAttempt {
		private final SceneDescriptor descriptor;
		private final GeneratedScene scene;
		private final int @Nullable [] target;
		private final List<SampleRecord> samples = new ArrayList<>();
		private final List<String> files = new ArrayList<>();
		private @Nullable CameraPose current;
		private long currentSettledTicks;
		private @Nullable String lastRejection;

		SceneAttempt(SceneDescriptor descriptor, GeneratedScene scene) {
			this.descriptor = descriptor;
			this.scene = scene;
			this.target = scene.plan().targetPosition(DatasetGenerator.this.arena);
		}

		CompletableFuture<SceneOutcome> run() {
			int limit = DatasetGenerator.this.config.viewpoints().attemptsPerViewpoint();
			for (int attempt = 0; attempt < limit; attempt++) {
				ViewpointCandidate candidate = DatasetGenerator.this.generator.viewpoint(this.scene, 0, attempt);
				if (candidate.plan() != null) {
					CameraPose first = pose(candidate.plan());
					int firstAttempt = attempt;
					return DatasetGenerator.this.environment.reset(DatasetGenerator.this.arena, this.scene.plan(), first).thenCompose(ready -> {
						this.current = first;
						this.currentSettledTicks = ready.settledTicks();
						return viewpoint(0, firstAttempt, ready);
					});
				}
			}

			return CompletableFuture.completedFuture(rejected(VIEWPOINT_PLACEMENT));
		}

		private CompletableFuture<SceneOutcome> viewpoint(int index, int attempt, GenerationEnvironment.SceneReady ready) {
			if (index == DatasetGenerator.this.config.viewpoints().count()) {
				return CompletableFuture.completedFuture(new SceneOutcome(List.copyOf(this.samples), List.copyOf(this.files), sceneRecord(ready), null));
			}

			if (DatasetGenerator.this.cancelled) {
				return CompletableFuture.failedFuture(new Stop(CANCELLED));
			}

			if (attempt == DatasetGenerator.this.config.viewpoints().attemptsPerViewpoint()) {
				return CompletableFuture.completedFuture(rejected(this.lastRejection == null ? VIEWPOINT_PLACEMENT : this.lastRejection));
			}

			ViewpointCandidate candidate = DatasetGenerator.this.generator.viewpoint(this.scene, index, attempt);
			if (candidate.plan() == null) {
				this.lastRejection = this.lastRejection == null ? VIEWPOINT_PLACEMENT : this.lastRejection;
				return viewpoint(index, attempt + 1, ready);
			}

			CameraPose pose = pose(candidate.plan());
			CompletableFuture<GenerationEnvironment.ViewReady> placed = pose.equals(this.current)
				? CompletableFuture.completedFuture(new GenerationEnvironment.ViewReady(ready.operationId(), pose, this.currentSettledTicks))
				: DatasetGenerator.this.environment.setViewpoint(pose);
			return placed.thenCompose(view -> {
				this.current = view.pose();
				this.currentSettledTicks = view.settledTicks();
				return DatasetGenerator.this.environment.observe(DatasetGenerator.this.projection, this.target)
					.thenCompose(observation -> accept(index, view, observation)
						? viewpoint(index + 1, 0, ready)
						: viewpoint(index, attempt + 1, ready));
			}).exceptionallyCompose(error -> {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				if (cause instanceof EnvironmentException environmentError && environmentError.reason() == EnvironmentException.Reason.CAPTURE_FAILED) {
					// The coordinator is FAILED after a capture failure; only a new reset can continue.
					HoneyFactory.LOGGER.warn("Capture failed in {}: {}", this.descriptor.sceneId(), environmentError.getMessage());
					return CompletableFuture.completedFuture(rejected(CAPTURE_FAILED));
				}

				return CompletableFuture.failedFuture(cause);
			});
		}

		/** Writes the images and records the sample when the capture is acceptable. */
		private boolean accept(int index, GenerationEnvironment.ViewReady view, GenerationEnvironment.Observation observation) {
			ResolvedConfig config = DatasetGenerator.this.config;
			double eyeHeight = config.viewpoints().eyeHeightBlocks();
			CameraFrame camera = CameraFrame.of(view.pose(), eyeHeight);
			ObservationCheck.Outcome outcome = ObservationCheck.check(config.visibility(), DatasetGenerator.this.projection, camera, this.target, observation.images());
			if (outcome.rejection() != null) {
				this.lastRejection = outcome.rejection();
				HoneyFactory.LOGGER.debug("Viewpoint {} of {} rejected: {}", index, this.descriptor.sceneId(), outcome.detail());
				return false;
			}

			SampleDescriptor sample = this.descriptor.sample(index);
			var images = observation.images();
			try {
				SampleRecord.ImageFile rgb = DatasetGenerator.this.writer.writeImage(sample.rgbPath(), PngImages.encodeRgb(images.width(), images.height(), images.rgb()), images.width(), images.height());
				this.files.add(sample.rgbPath());
				SampleRecord.ImageFile mask = DatasetGenerator.this.writer.writeImage(sample.maskPath(), PngImages.encodeGray(images.width(), images.height(), images.visibleMask()), images.width(), images.height());
				this.files.add(sample.maskPath());
				CameraPose pose = view.pose();
				this.samples.add(new SampleRecord(
					SchemaVersion.CURRENT,
					sample.sampleId(),
					sample.sceneId(),
					sample.split(),
					index,
					rgb,
					mask,
					SampleRecord.Camera.of(new double[] {pose.x(), pose.y(), pose.z()}, eyeHeight, pose.yawDegrees(), pose.pitchDegrees()),
					DatasetGenerator.this.projection.mode(),
					new JsonObject(),
					new SampleRecord.CaptureInfo(observation.operationId(), observation.worldTick(), observation.partialTick(), true, (int) view.settledTicks()),
					outcome.target(),
					new SampleRecord.Conditions(
						this.scene.plan().timeOfDayTicks(),
						this.scene.plan().weather(),
						observation.biome(),
						observation.skyLight(),
						observation.blockLight(),
						images.meanLuminance()
					),
					null
				));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			return true;
		}

		private SceneRecord sceneRecord(GenerationEnvironment.SceneReady ready) {
			ArenaLayout arena = DatasetGenerator.this.arena;
			ResolvedConfig.Arena size = DatasetGenerator.this.config.arena();
			var plan = this.scene.plan();
			var template = plan.template();
			SceneSeeds seeds = this.descriptor.seeds();
			return new SceneRecord(
				SchemaVersion.CURRENT,
				this.descriptor.sceneId(),
				this.descriptor.split(),
				DatasetGenerator.this.config.difficulty(),
				new SceneRecord.Generation(this.descriptor.slot().sceneIndex(), this.descriptor.attemptIndex(), seeds, SceneSeeds.ALGORITHM),
				new SceneRecord.Template(template.id(), template.version(), template.sha256()),
				new SceneRecord.Arena(ArenaLayout.DIMENSION_ID, arena.core().min(), arena.resetVolume().min(), arena.resetVolume().max(), size.width(), size.length(), size.height(), size.margin()),
				null,
				new SceneRecord.Target(this.target != null, DatasetGenerator.this.config.target().blockId(), plan.target() == null ? null : plan.target().state(), this.target),
				new SceneRecord.Environment(plan.timeOfDayTicks(), plan.weather(), ready.gamerules()),
				ready.layoutSha256(),
				this.samples.size()
			);
		}

		private SceneOutcome rejected(String reason) {
			return new SceneOutcome(List.of(), List.copyOf(this.files), null, reason);
		}

		private static CameraPose pose(ViewpointPlan plan) {
			double[] feet = plan.feet();
			return new CameraPose(feet[0], feet[1], feet[2], (float) plan.yawDegrees(), (float) plan.pitchDegrees());
		}
	}
}
