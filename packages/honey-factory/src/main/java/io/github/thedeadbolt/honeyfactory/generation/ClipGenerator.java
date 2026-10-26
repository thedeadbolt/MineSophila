package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.clip.ClipDriver;
import io.github.thedeadbolt.honeyfactory.clip.ClipEnvironment;
import io.github.thedeadbolt.honeyfactory.clip.ClipLabels;
import io.github.thedeadbolt.honeyfactory.clip.ClipStarts;
import io.github.thedeadbolt.honeyfactory.clip.Drift;
import io.github.thedeadbolt.honeyfactory.clip.ClipVideo;
import io.github.thedeadbolt.honeyfactory.clip.ClipWriter;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.ClipSeeds;
import io.github.thedeadbolt.honeyfactory.contract.ClipSettings;
import io.github.thedeadbolt.honeyfactory.contract.ClipType;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.SceneRecord;
import io.github.thedeadbolt.honeyfactory.contract.SchemaVersion;
import io.github.thedeadbolt.honeyfactory.contract.TeacherMode;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.dataset.ClipValidator;
import io.github.thedeadbolt.honeyfactory.dataset.DatasetWriter;
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
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.scene.SolidGrid;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointCandidate;
import io.github.thedeadbolt.honeyfactory.teacher.HoneyMemory;
import io.github.thedeadbolt.honeyfactory.teacher.Perception;
import io.github.thedeadbolt.honeyfactory.teacher.RealizableTeacher;
import io.github.thedeadbolt.honeyfactory.teacher.SeenMap;
import io.github.thedeadbolt.honeyfactory.teacher.Teacher;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherContext;
import io.github.thedeadbolt.honeyfactory.teacher.TeacherInput;
import io.github.thedeadbolt.honeyfactory.teacher.TrackBehaviour;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Generates a motion-clip dataset (M12-2). Scene slots are planned as for stills. Each
 * accepted scene holds up to {@code clips_per_scene} clips; each clip resets the scene at a
 * start drawn as a still viewpoint is, then runs the control loop for its type's ticks. Every
 * tick, the teacher decides from what the previous tick's end frame showed, the clip's driver
 * picks the executed command, and every frame the body's camera captured is labelled from its
 * own pose and masks and streamed into the clip's videos.
 *
 * <p>A clip whose body leaves the arena, falls, or whose capture or encoding fails is removed
 * and redrawn from its next attempt; after {@code attempts_per_clip} the clip is skipped. A
 * scene is kept when any of its clips succeeded.
 */
public final class ClipGenerator implements Generator {
	public static final String CLIP_ATTEMPTS = "clip_attempts";
	/** How far below its start the body may drop before a clip is redrawn. */
	static final double MAX_FALL_BLOCKS = 3.0;

	/** Ends the run deliberately; the message is the recorded failure. */
	private static final class Stop extends RuntimeException {
		Stop(String message) {
			super(message, null, false, false);
		}
	}

	/** Ends one clip attempt; the clip is redrawn. */
	private static final class Redraw extends RuntimeException {
		Redraw(String message) {
			super(message, null, false, false);
		}
	}

	private final ResolvedConfig config;
	private final ClipSettings clips;
	private final ClipEnvironment environment;
	private final Clock clock;
	private final Supplier<String> ffmpeg;
	private final SceneGenerator generator;
	private final ArenaLayout arena;
	private final CaptureProjection projection;
	private final RecordFactory records;
	private final GenerationTally tally;
	private final StorageBudget storage;
	private final GenerationTimings timings = new GenerationTimings();
	private final Map<Integer, CompletableFuture<PreparedPatch>> patches = new TreeMap<>();
	private @Nullable DatasetWriter writer;
	private @Nullable DatasetManifest manifest;
	private @Nullable ClipWriter running;
	private boolean cancelled;
	private @Nullable Instant started;

	public ClipGenerator(ResolvedConfig config, ClipEnvironment environment, Clock clock) {
		this(config, environment, clock, StorageBudget.DiskSpace.SYSTEM);
	}

	public ClipGenerator(ResolvedConfig config, ClipEnvironment environment, Clock clock, StorageBudget.DiskSpace disk) {
		this(config, environment, clock, disk, ClipVideo::version);
	}

	/** @param ffmpeg reports ffmpeg's version, or throws when it cannot run */
	public ClipGenerator(ResolvedConfig config, ClipEnvironment environment, Clock clock, StorageBudget.DiskSpace disk, Supplier<String> ffmpeg) {
		if (config.clips() == null) {
			throw new IllegalArgumentException("a clip generator needs a configuration with clip settings");
		}

		this.config = config;
		this.clips = config.clips();
		this.environment = environment;
		this.clock = clock;
		this.ffmpeg = ffmpeg;
		this.generator = SceneGenerators.forConfig(config);
		this.arena = ArenaLayout.of(config.scene().base(), config.arena());
		this.projection = config.projection();
		this.records = new RecordFactory(config, this.arena, this.projection.mode());
		this.tally = new GenerationTally(slots(config).size());
		this.storage = new StorageBudget(config, disk);
	}

	@Override
	public CompletableFuture<DatasetGenerator.Result> start() throws IOException {
		if (this.writer != null) {
			throw new IllegalStateException("A generator runs once");
		}

		String version = this.ffmpeg.get();
		HoneyFactory.LOGGER.info("Clip videos are encoded by {}", version);
		this.storage.preflight();
		if (this.config.scene().base() instanceof SceneBase.NaturalTerrain natural) {
			int distance = this.environment.renderEnvironment().renderDistanceChunks();
			if (natural.contextPadding() < 16 * distance) {
				throw new IllegalStateException("context_padding " + natural.contextPadding() + " leaves the void visible at render distance " + distance
					+ "; use at least " + 16 * distance + " blocks or lower the render distance");
			}
		}

		this.started = this.clock.instant();
		List<SceneSlots.Slot> plan = slots(this.config);
		ResolvedConfig.Slice slice = this.config.output().slice();
		this.manifest = DatasetManifest.generating(
			this.config.toJson(),
			this.clock.instant(),
			this.environment.software().withFfmpeg(version),
			this.environment.renderEnvironment(),
			new DatasetManifest.Provenance(DatasetGenerator.TOOL, List.of(), List.of()),
			slice == null ? null : new DatasetManifest.Slice(slice.index(), slice.count(), slice.world(), plan.stream().map(SceneSlots.Slot::sceneIndex).toList())
		);
		this.writer = DatasetWriter.create(this.config.outputRoot(), this.manifest);
		return runSlots(plan.iterator()).handle((ignored, error) -> finish(error));
	}

	private static List<SceneSlots.Slot> slots(ResolvedConfig config) {
		List<SceneSlots.Slot> plan = SceneSlots.plan(config);
		ResolvedConfig.Slice slice = config.output().slice();
		return slice == null ? plan : plan.stream().filter(slot -> slice.holds(slot.group())).toList();
	}

	@Override
	public void cancel() {
		this.cancelled = true;
	}

	@Override
	public DatasetGenerator.Progress progress() {
		Duration elapsed = this.started == null ? Duration.ZERO : Duration.between(this.started, this.clock.instant());
		return this.tally.progress(elapsed);
	}

	@Override
	public GenerationTimings timings() {
		return this.timings;
	}

	private CompletableFuture<Void> runSlots(Iterator<SceneSlots.Slot> slots) {
		if (!slots.hasNext()) {
			return CompletableFuture.completedFuture(null);
		}

		return runSlot(slots.next(), 0).thenCompose(ignored -> runSlots(slots));
	}

	/** Attempts {@code slot} until a scene keeps at least one clip. */
	private CompletableFuture<Void> runSlot(SceneSlots.Slot slot, int attemptIndex) {
		Stop stop = stopReason();
		if (stop != null) {
			return CompletableFuture.failedFuture(stop);
		}

		this.tally.attempt();
		SceneDescriptor descriptor = SceneDescriptor.of(this.config, slot, attemptIndex);
		return scene(slot, descriptor)
			.exceptionallyCompose(error -> {
				this.tally.fail();
				return CompletableFuture.failedFuture(error);
			})
			.thenCompose(scene -> new SceneClips(descriptor, scene).run())
			.thenCompose(outcome -> {
				if (outcome == null) {
					this.tally.reject(CLIP_ATTEMPTS);
					HoneyFactory.LOGGER.info("Rejected {} attempt {}: no clip succeeded", slot.sceneId(), attemptIndex);
					return runSlot(slot, attemptIndex + 1);
				}

				io(() -> this.writer.appendClipScene(outcome.scene(), outcome.clips()));
				this.tally.acceptClips(slot.split(), outcome.clips().size());
				return CompletableFuture.completedFuture(null);
			});
	}

	/** The attempt's scene; on a natural base, once its slot's source patch is prepared. */
	private CompletableFuture<GeneratedScene> scene(SceneSlots.Slot slot, SceneDescriptor descriptor) {
		if (!(this.generator instanceof NaturalGenerator natural)) {
			return CompletableFuture.completedFuture(this.generator.scene(descriptor.seeds(), slot.targetPresent()));
		}

		int patchCount = Math.ceilDiv(this.config.quotas().acceptedScenes(), natural.base().scenesPerPatch());
		this.patches.keySet().removeIf(index -> index < slot.group());
		CompletableFuture<PreparedPatch> patch = patch(natural, slot.group());
		ResolvedConfig.Slice slice = this.config.output().slice();
		int next = slot.group() + (slice == null ? 1 : slice.count());
		if (next < patchCount) {
			patch(natural, next);
		}

		return patch.thenApply(prepared -> natural.scene(descriptor.seeds(), slot.targetPresent(), prepared));
	}

	private CompletableFuture<PreparedPatch> patch(NaturalGenerator natural, int index) {
		return this.patches.computeIfAbsent(index, key -> request(GenerationTimings.Phase.PREPARE_PATCH, () -> this.environment.preparePatch(
			new GenerationEnvironment.PatchRequest(this.config.seed(), natural.base(), key, this.config.target().blockId())
		)));
	}

	private @Nullable Stop stopReason() {
		if (this.cancelled) {
			return new Stop(DatasetGenerator.CANCELLED);
		}

		int limit = this.config.quotas().sceneAttemptLimit();
		if (this.tally.attempted() >= limit) {
			return new Stop(DatasetGenerator.SCENE_ATTEMPT_LIMIT + " of " + limit + " reached");
		}

		try {
			String exhausted = this.storage.exhausted();
			return exhausted == null ? null : new Stop(DatasetGenerator.STORAGE_RESERVE + ": " + exhausted);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private <T> CompletableFuture<T> request(GenerationTimings.Phase phase, Supplier<CompletableFuture<T>> operation) {
		if (this.cancelled) {
			return CompletableFuture.failedFuture(new Stop(DatasetGenerator.CANCELLED));
		}

		long begun = System.nanoTime();
		return operation.get().whenComplete((value, error) -> this.timings.add(phase, System.nanoTime() - begun));
	}

	private DatasetGenerator.Result finish(@Nullable Throwable error) {
		long validating = System.nanoTime();
		try {
			this.environment.end();
			abortRunning();
			String failure = failureMessage(error);
			DatasetManifest.Counts counts = this.tally.counts();
			if (failure != null) {
				DatasetManifest partial = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts, null);
				return failed(counts, ClipValidator.validate(this.writer.root(), partial), failure);
			}

			DatasetManifest candidate = this.manifest.withState(DatasetManifest.Lifecycle.COMPLETE, this.clock.instant(), this.tally.splitCounts(), counts, null);
			ClipValidator.Report report = ClipValidator.validate(this.writer.root(), candidate);
			if (!report.passed()) {
				return failed(counts, report, "validation found " + report.errors().size() + " errors");
			}

			DatasetManifest done = candidate.withState(DatasetManifest.Lifecycle.COMPLETE, candidate.completedAtUtc(), candidate.splitCounts(), counts, integrity(report));
			this.writer.writeManifest(done);
			ClipValidator.Report stored = ClipValidator.validate(this.writer.root());
			if (!stored.passed()) {
				return failed(counts, stored, "the stored dataset failed validation");
			}

			return new DatasetGenerator.Result(done, this.writer.root(), null, List.of());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			this.timings.add(GenerationTimings.Phase.VALIDATE, System.nanoTime() - validating);
			HoneyFactory.LOGGER.info("Clip timings: {}", this.timings);
		}
	}

	private DatasetGenerator.Result failed(DatasetManifest.Counts counts, ClipValidator.Report report, String failure) throws IOException {
		DatasetManifest failedManifest = this.manifest.withState(DatasetManifest.Lifecycle.FAILED, this.clock.instant(), null, counts, integrity(report));
		this.writer.writeManifest(failedManifest);
		report.errors().forEach(message -> HoneyFactory.LOGGER.warn("Clip dataset validation: {}", message));
		HoneyFactory.LOGGER.warn("Clip dataset {} failed: {}", failedManifest.datasetId(), failure);
		return new DatasetGenerator.Result(failedManifest, this.writer.root(), failure, report.errors());
	}

	private static DatasetManifest.Integrity integrity(ClipValidator.Report report) {
		return new DatasetManifest.Integrity(report.scenesDigest(), report.clipsDigest(), report.passed(), report.errors().size());
	}

	private static @Nullable String failureMessage(@Nullable Throwable error) {
		if (error == null) {
			return null;
		}

		Throwable cause = Futures.cause(error);
		if (cause instanceof Stop stop) {
			return stop.getMessage();
		}

		HoneyFactory.LOGGER.error("Clip generation failed", cause);
		return cause instanceof EnvironmentException environmentError ? environmentError.toString() : String.valueOf(cause);
	}

	/** A clip interrupted by the end of the run leaves no files. */
	private void abortRunning() {
		ClipWriter clip = this.running;
		this.running = null;
		if (clip != null && !clip.closed()) {
			io(clip::abort);
		}
	}

	@FunctionalInterface
	private interface IoAction {
		void run() throws IOException;
	}

	private static void io(IoAction action) {
		try {
			action.run();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** A scene's accepted clips, and the start draw of each. */
	private record Kept(SceneRecord scene, List<ClipRecord> clips) {
	}

	/** One scene attempt: its clips in order, each redrawn until it succeeds or runs out of attempts. */
	private final class SceneClips {
		private final SceneDescriptor descriptor;
		private final GeneratedScene scene;
		private final int @Nullable [] target;
		private final SightGrid grid;
		private final List<ClipRecord> kept = new ArrayList<>();
		private final List<Integer> draws = new ArrayList<>();
		private GenerationEnvironment.@Nullable SceneReady ready;

		SceneClips(SceneDescriptor descriptor, GeneratedScene scene) {
			this.descriptor = descriptor;
			this.scene = scene;
			this.target = scene.plan().targetPosition(ClipGenerator.this.arena);
			this.grid = scene.terrain() != null ? scene.terrain() : SolidGrid.of(scene.plan());
		}

		/** The kept scene, or null when no clip succeeded. */
		CompletableFuture<@Nullable Kept> run() {
			return clip(0).thenApply(ignored -> {
				if (this.kept.isEmpty()) {
					return null;
				}

				SceneRecord record = ClipGenerator.this.records.scene(this.descriptor, this.scene, this.draws, this.ready, 0, this.kept.size());
				return new Kept(record, List.copyOf(this.kept));
			});
		}

		private CompletableFuture<Void> clip(int index) {
			if (index >= ClipGenerator.this.clips.clipsPerScene()) {
				return CompletableFuture.completedFuture(null);
			}

			return attempt(index, 0).thenCompose(ignored -> clip(index + 1));
		}

		private CompletableFuture<Void> attempt(int index, int attempt) {
			if (attempt >= ClipGenerator.this.clips.attemptsPerClip()) {
				HoneyFactory.LOGGER.warn("Clip {} of {} skipped after {} attempts", index, this.descriptor.sceneId(), attempt);
				return CompletableFuture.completedFuture(null);
			}

			Stop stop = ClipGenerator.this.cancelled ? new Stop(DatasetGenerator.CANCELLED) : null;
			if (stop != null) {
				return CompletableFuture.failedFuture(stop);
			}

			ClipSeeds seeds = ClipSeeds.derive(ClipGenerator.this.config.seed(), this.descriptor.slot().sceneIndex(), index, attempt);
			ClipType type = type(index);
			ClipStarts.Start start = start(index, attempt, type, seeds);
			if (start == null) {
				HoneyFactory.LOGGER.warn("Clip {} of {} has no placeable start", index, this.descriptor.sceneId());
				return CompletableFuture.completedFuture(null);
			}

			return new ClipRun(this, index, attempt, type, seeds, start).run().handle((record, error) -> {
				if (error == null) {
					this.kept.add(record);
					this.draws.add(start.draw());
					return CompletableFuture.<Void>completedFuture(null);
				}

				Throwable cause = Futures.cause(error);
				if (cause instanceof Redraw || cause instanceof EnvironmentException environmentError
					&& (environmentError.reason() == EnvironmentException.Reason.OUT_OF_ARENA || environmentError.reason() == EnvironmentException.Reason.CAPTURE_FAILED)
					|| cause instanceof UncheckedIOException) {
					HoneyFactory.LOGGER.info("Redrawing clip {} of {} after attempt {}: {}", index, this.descriptor.sceneId(), attempt, cause.getMessage());
					return attempt(index, attempt + 1);
				}

				return CompletableFuture.<Void>failedFuture(cause);
			}).thenCompose(next -> next);
		}

		/**
		 * The clip's requested type: no honey in a target-absent scene, otherwise drawn from the
		 * mix with attempt 0's drive seed, so every attempt of a clip keeps its type.
		 */
		private ClipType type(int index) {
			if (this.target == null) {
				return ClipType.NO_HONEY;
			}

			Map<ClipType, Double> mix = mix(ClipGenerator.this.clips, ClipGenerator.this.config.difficulty());
			long seed = ClipSeeds.derive(ClipGenerator.this.config.seed(), this.descriptor.slot().sceneIndex(), index, 0).drive();
			double total = 0.0;
			for (ClipType type : ClipSettings.PRESENT_TYPES) {
				total += mix.get(type);
			}

			double draw = new SplittableRandom(seed).nextDouble() * total;
			for (ClipType type : ClipSettings.PRESENT_TYPES) {
				draw -= mix.get(type);
				if (draw < 0.0) {
					return type;
				}
			}

			return ClipSettings.PRESENT_TYPES.reversed().stream().filter(type -> mix.get(type) > 0.0).findFirst().orElseThrow();
		}

		/** The clip's start: see {@link ClipStarts}; draws run from {@code attempt} on. */
		private ClipStarts.@Nullable Start start(int index, int attempt, ClipType type, ClipSeeds seeds) {
			return ClipStarts.choose(type, draw -> ClipGenerator.this.generator.viewpoint(this.scene, index, draw) instanceof ViewpointCandidate.Placeable placeable
					? placeable.plan().pose(ClipGenerator.this.arena) : null,
				attempt, ClipGenerator.this.config.viewpoints().attemptsPerViewpoint(), this.grid, ClipGenerator.this.arena.core(), this.target, seeds.start(),
				ClipGenerator.this.config.viewpoints().eyeHeightBlocks());
		}
	}

	/**
	 * The mix a scene's clips are drawn from. A level-1 platform has no blocks to hide behind,
	 * so its occlusion and detour weights go to approach and pass-by, in proportion to theirs.
	 */
	static Map<ClipType, Double> mix(ClipSettings clips, int difficulty) {
		Map<ClipType, Double> mix = new EnumMap<>(clips.mix());
		if (difficulty != 1) {
			return mix;
		}

		double moved = mix.get(ClipType.OCCLUSION) + mix.get(ClipType.DETOUR);
		double approach = mix.get(ClipType.APPROACH);
		double passBy = mix.get(ClipType.PASS_BY);
		double share = approach + passBy > 0.0 ? approach / (approach + passBy) : 1.0;
		mix.put(ClipType.APPROACH, approach + moved * share);
		mix.put(ClipType.PASS_BY, passBy + moved * (1.0 - share));
		mix.put(ClipType.OCCLUSION, 0.0);
		mix.put(ClipType.DETOUR, 0.0);
		return mix;
	}

	/** One attempt at one clip. */
	private final class ClipRun {
		private final SceneClips scene;
		private final int index;
		private final int attempt;
		private final ClipType requested;
		private final ClipType type;
		private final ClipSeeds seeds;
		private final ClipStarts.Start start;
		private final String clipId;
		private final int ticks;
		private final int framesPerTick;
		private final Teacher teacher = new RealizableTeacher(ClipGenerator.this.clips.teacher().reacquireTicks());
		private final SeenMap seen = new SeenMap(ClipGenerator.this.arena.core());
		private final HoneyMemory memory = new HoneyMemory();
		private @Nullable TeacherContext context;
		private final Map<TeacherMode, Integer> modes = new EnumMap<>(TeacherMode.class);
		private final List<MotorCommand> executed = new ArrayList<>();
		private @Nullable ClipWriter writer;
		private @Nullable ClipDriver driver;
		private @Nullable ControlStep last;
		private double coverage;
		private int driftTicks;
		/** The tick an approach reached the honey, or -1. */
		private int reachedTick = -1;
		/** Ticks run so far. */
		private int ran;
		/** Cells the body could reach from its start, by the true scene: the measure of search coverage, never the teacher's knowledge. */
		private java.util.Set<Long> reachable = java.util.Set.of();
		private int frame;

		ClipRun(SceneClips scene, int index, int attempt, ClipType requested, ClipSeeds seeds, ClipStarts.Start start) {
			this.scene = scene;
			this.index = index;
			this.attempt = attempt;
			this.requested = requested;
			this.type = start.type();
			this.seeds = seeds;
			this.start = start;
			this.clipId = Ids.clipId(scene.descriptor.sceneId(), index);
			this.ticks = ClipGenerator.this.clips.ticks(this.type);
			this.framesPerTick = ClipGenerator.this.clips.framesPerTick();
		}

		CompletableFuture<ClipRecord> run() {
			this.driver = ClipDriver.of(this.start, this.seeds.drive(), this.scene.target, ClipGenerator.this.clips.motor(), ClipGenerator.this.config.viewpoints().eyeHeightBlocks(),
				new Drift(this.seeds.drift(), ClipGenerator.this.clips.drift(), ClipGenerator.this.clips.motor()));
			this.context = new TeacherContext(this.scene.grid, ClipGenerator.this.arena.core(), ClipGenerator.this.clips.motor(), ClipGenerator.this.clips.teacher().minVisiblePixels(),
				ClipGenerator.this.config.viewpoints().eyeHeightBlocks(), ClipGenerator.this.projection, ClipGenerator.this.clips.teacher().reachDistanceBlocks());
			this.teacher.reset(this.context);
			int[] origin = ClipGenerator.this.arena.core().min();
			CameraPose start = this.start.pose();
			this.reachable = SeenMap.reachable(this.scene.grid, ClipGenerator.this.arena.core(),
				new int[] {(int) Math.floor(start.x()) - origin[0], (int) Math.floor(start.y()) - origin[1], (int) Math.floor(start.z()) - origin[2]});
			return request(GenerationTimings.Phase.RESET, () -> ClipGenerator.this.environment.reset(ClipGenerator.this.arena, this.scene.scene.plan(), this.start.pose(),
				ClipGenerator.this.projection, this.seeds.drive(), this.framesPerTick))
				.thenCompose(reset -> {
					if (this.scene.ready == null) {
						this.scene.ready = reset.ready();
					}

					open();
					ControlStep step = reset.step().step();
					record(0, 0, reset.step().frames().getFirst(), labels(step, null, null, false));
					this.last = step;
					return tick(1);
				})
				.whenComplete((record, error) -> {
					if (error != null && this.writer != null && !this.writer.closed()) {
						io(this.writer::abort);
					}

					ClipGenerator.this.running = null;
				});
		}

		private void open() {
			try {
				this.writer = ClipWriter.open(ClipGenerator.this.writer.root(), this.scene.descriptor.split(), this.clipId, ClipGenerator.this.projection.width(),
					ClipGenerator.this.projection.height(), ClipGenerator.this.clips.codec());
				ClipGenerator.this.running = this.writer;
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private CompletableFuture<ClipRecord> tick(int tick) {
			if (tick > this.ticks || this.reachedTick >= 0 && tick > this.reachedTick + ClipGenerator.this.clips.approachLingerTicks()) {
				return CompletableFuture.supplyAsync(this::finish, ClipGenerator.this.environment.executor());
			}

			if (ClipGenerator.this.cancelled) {
				return CompletableFuture.failedFuture(new Stop(DatasetGenerator.CANCELLED));
			}

			TeacherInput input = perceive(tick);
			this.coverage = this.seen.coverage(this.reachable);
			Teacher.Decision decision = this.teacher.decide(input);
			MotorCommand command = this.driver.next(tick, input, decision);
			return request(GenerationTimings.Phase.OBSERVE, () -> ClipGenerator.this.environment.step(command, decision.command())).thenCompose(step -> {
				ControlStep control = step.step();
				if (control.truth().pose().y() < this.start.pose().y() - MAX_FALL_BLOCKS) {
					throw new Redraw("the body fell to " + control.truth().pose());
				}

				List<ClipEnvironment.Frame> frames = step.frames();
				if (frames.size() != this.framesPerTick) {
					throw new IllegalStateException("a step captured " + frames.size() + " frames, not " + this.framesPerTick);
				}

				boolean drifting = this.driver.drifting();
				for (int k = 1; k <= this.framesPerTick; k++) {
					record(tick, k, frames.get(k - 1), k == this.framesPerTick ? labels(control, command, decision, drifting) : null);
				}

				this.driftTicks += drifting ? 1 : 0;
				if (this.type == ClipType.APPROACH && this.reachedTick < 0 && TrackBehaviour.reached(control.truth().pose(), this.scene.target, this.context)) {
					this.reachedTick = tick;
				}

				this.ran = tick;
				this.executed.add(command);
				this.modes.merge(decision.mode(), 1, Integer::sum);
				this.last = control;
				return tick(tick + 1);
			});
		}

		/** What the teacher may know at {@code tick}, from the previous tick's end frame (see {@link Perception}). */
		private TeacherInput perceive(int tick) {
			ControlStep previous = this.last;
			return Perception.perceive(tick, previous.truth().pose(), previous.senses(), this.scene.target, previous.truth().target().visiblePixels(), this.context, this.seen,
				this.memory);
		}

		private FrameRecord.TickLabels labels(ControlStep step, @Nullable MotorCommand executed, Teacher.@Nullable Decision decision, boolean drifting) {
			return new FrameRecord.TickLabels(step.truth().serverTick(), step.truth().velocity(), step.senses(), executed, decision == null ? null : decision.command(),
				decision == null ? null : decision.mode(), decision == null ? null : decision.belief(), drifting, this.coverage);
		}

		private void record(int tick, int subFrame, ClipEnvironment.Frame captured, FrameRecord.@Nullable TickLabels labels) {
			FrameRecord record = ClipLabels.frame(this.clipId, this.frame++, tick, subFrame, this.framesPerTick, captured, this.scene.target, ClipGenerator.this.projection,
				ClipGenerator.this.config.viewpoints().eyeHeightBlocks(), labels);
			try {
				this.writer.frame(captured.images(), record);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private ClipRecord finish() {
			long writing = System.nanoTime();
			ClipWriter.Written files;
			try {
				files = this.writer.finish();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} finally {
				ClipGenerator.this.timings.add(GenerationTimings.Phase.WRITE_WAIT, System.nanoTime() - writing);
			}

			Map<TeacherMode, Integer> modes = new EnumMap<>(TeacherMode.class);
			for (TeacherMode mode : TeacherMode.values()) {
				modes.put(mode, this.modes.getOrDefault(mode, 0));
			}

			boolean reached = this.reachedTick >= 0;
			ClipRecord.EndReason end = reached && this.ran < this.ticks ? ClipRecord.EndReason.REACHED : ClipRecord.EndReason.LENGTH;
			return new ClipRecord(SchemaVersion.CURRENT, this.clipId, this.scene.descriptor.sceneId(), this.scene.descriptor.split(), this.index, this.requested, this.type, this.attempt,
				this.seeds, this.start.pose(), this.framesPerTick, this.ran, ClipRecord.frameCount(this.ran, this.framesPerTick), end,
				ClipGenerator.this.clips.codec(), files.rgb(), files.visibleMask(), files.frames(), this.executed,
				new ClipRecord.Summary(this.driftTicks, this.type == ClipType.APPROACH ? reached : null, this.coverage, modes));
		}
	}
}
