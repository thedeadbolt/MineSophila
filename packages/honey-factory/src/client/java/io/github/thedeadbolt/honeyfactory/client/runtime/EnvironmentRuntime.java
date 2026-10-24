package io.github.thedeadbolt.honeyfactory.client.runtime;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.Operation;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.environment.OperationKind;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.environment.ReadinessGate;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import io.github.thedeadbolt.honeyfactory.world.BlockStates;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import org.jspecify.annotations.Nullable;

/**
 * The client-side environment coordinator: the single implementation of
 * {@code reset}, {@code setViewpoint}, {@code observe}, {@code status} and {@code step}
 * that commands, tests and later the dataset runner all call.
 *
 * <p>Requests return futures completed on the client thread. Server work runs on the
 * integrated server thread. Once it finishes, readiness is polled every client tick
 * against the client's own view of the world, so a completed reset or viewpoint is one
 * the client can render. All mutable state is confined to the client thread.
 */
public final class EnvironmentRuntime {
	/** Settling minimums and timeouts, in client ticks. */
	public record Timing(int serverTimeoutTicks, int resetSettleTicks, int resetTimeoutTicks, int viewpointSettleTicks, int viewpointTimeoutTicks) {
		/** A source-patch copy generates terrain and relights hundreds of chunks, so server work may take minutes. */
		public static final Timing DEFAULT = new Timing(6000, 5, 1200, 3, 200);
	}

	public record SceneState(String operationId, String layoutSha256, long serverTick, long settledTicks, CameraPose pose) {
	}

	public record ViewpointState(String operationId, CameraPose pose, long settledTicks) {
	}

	/** One capture and the camera it was taken from; light levels and biome are at the eye. */
	public record Observation(
		String operationId,
		CaptureService.Capture capture,
		CameraPose pose,
		@Nullable String biome,
		@Nullable Integer skyLight,
		@Nullable Integer blockLight
	) {
	}

	private static final EnvironmentRuntime INSTANCE = new EnvironmentRuntime(Timing.DEFAULT);
	private static final double POSE_TOLERANCE = 1e-6;
	/**
	 * Level frames the render conditions must hold for. Block updates only reach the
	 * section compile queue during the next frame's extraction, so an empty queue is
	 * trustworthy only after a frame has run since the change.
	 */
	private static final int STABLE_FRAMES = 2;

	private OperationCoordinator coordinator = new OperationCoordinator();
	private final Timing timing;
	private long tick;
	private long frames;
	private @Nullable Pending<?, ?> pending;
	private @Nullable ArenaLayout arena;
	private @Nullable ScenePlan scene;
	private @Nullable String layoutSha256;
	private ArenaExecutor.@Nullable ResetResult lastReset;
	private @Nullable Runnable deferredCapture;
	private @Nullable PoseSequence poseSequence;
	private boolean poseStepQueued;

	private EnvironmentRuntime(Timing timing) {
		this.timing = timing;
	}

	public static EnvironmentRuntime get() {
		return INSTANCE;
	}


	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> INSTANCE.tick());
		LevelRenderEvents.END_MAIN.register(context -> INSTANCE.frameRendered());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> INSTANCE.disconnect());
	}

	/**
	 * Counts the frame and, during {@link #observeAll}, queues the pose sequence's next step
	 * to run between this frame and the next, so poses advance per frame rather than per tick.
	 */
	private void frameRendered() {
		this.frames++;
		if (this.poseSequence != null && !this.poseStepQueued) {
			this.poseStepQueued = true;
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.schedule(minecraft.wrapRunnable(() -> {
				this.poseStepQueued = false;
				PoseSequence sequence = this.poseSequence;
				if (sequence != null && sequence.step(minecraft)) {
					this.poseSequence = null;
				}
			}));
		}
	}

	/** Leaving a world fails any running operation and forgets the scene; the next world starts IDLE. */
	private void disconnect() {
		Pending<?, ?> running = this.pending;
		this.pending = null;
		if (running != null) {
			running.fail(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "disconnected from the world"));
		}

		this.coordinator = new OperationCoordinator();
		this.deferredCapture = null;
		this.poseSequence = null;
		this.arena = null;
		this.scene = null;
		this.layoutSha256 = null;
	}

	public OperationCoordinator.Status status() {
		return this.coordinator.status();
	}

	/** The scene of the last successful reset, or null. */
	public @Nullable ScenePlan scene() {
		return this.scene;
	}

	/** The arena of the last reset, or null. */
	public @Nullable ArenaLayout arena() {
		return this.arena;
	}

	/** The server's report of the last successful reset, including any patch copy statistics, or null. */
	public ArenaExecutor.@Nullable ResetResult lastReset() {
		return this.lastReset;
	}

	/** The verified layout hash of the current scene, or null. */
	public @Nullable String layoutSha256() {
		return this.layoutSha256;
	}

	/**
	 * Builds {@code plan} in the owned arena, places the player at {@code firstViewpoint}
	 * and completes once the client shows the expected layout and has compiled it.
	 */
	public CompletableFuture<SceneState> reset(ArenaLayout layout, ScenePlan plan, CameraPose firstViewpoint) {
		return start(OperationKind.RESET, (server, operation) -> {
			this.arena = layout;
			this.scene = null;
			this.layoutSha256 = null;
			CompletableFuture<ArenaExecutor.ResetResult> work = server.submit(() -> {
				ArenaExecutor.ResetResult result = ArenaExecutor.reset(server, layout, plan);
				ArenaExecutor.placeViewpoint(server, player(server), layout, firstViewpoint, ConfigResolver.EYE_HEIGHT_BLOCKS);
				return result;
			});
			SceneReadiness readiness = new SceneReadiness(layout, firstViewpoint);
			return new Pending<>(operation, work, this.timing.resetSettleTicks(), this.timing.resetTimeoutTicks(), result -> readiness.ready(Minecraft.getInstance(), result), (result, settled) -> {
				this.scene = plan;
				this.layoutSha256 = result.layoutSha256();
				this.lastReset = result;
				return new SceneState(operation.id(), result.layoutSha256(), result.serverTick(), settled, clientPose(Minecraft.getInstance()));
			});
		});
	}

	/** Moves the camera to {@code pose} in the current scene and waits until the client renders it. */
	public CompletableFuture<ViewpointState> setViewpoint(CameraPose pose) {
		return start(OperationKind.SET_VIEWPOINT, (server, operation) -> {
			ArenaLayout layout = this.arena;
			CompletableFuture<CameraPose> work = server.submit(() -> ArenaExecutor.placeViewpoint(server, player(server), layout, pose, ConfigResolver.EYE_HEIGHT_BLOCKS));
			// ACTING while the server moves the player, SETTLING while the client catches up.
			RenderStability stability = new RenderStability();
			Pending<CameraPose, ViewpointState> viewpoint = new Pending<>(operation, work, this.timing.viewpointSettleTicks(), this.timing.viewpointTimeoutTicks(), resolved -> {
				Minecraft minecraft = Minecraft.getInstance();
				return stability.holds(inArena(minecraft) && atPose(minecraft, pose) && sectionsCompiled(minecraft));
			}, (resolved, settled) -> new ViewpointState(operation.id(), resolved, settled));
			viewpoint.afterWork = () -> this.coordinator.advance(operation, OperationState.SETTLING);
			return viewpoint;
		});
	}

	/**
	 * Captures RGB and target masks from the current viewpoint through {@code projection}.
	 * The capture runs at the end of the next client tick, between frames.
	 */
	public CompletableFuture<Observation> observe(CaptureProjection projection) {
		return start(OperationKind.OBSERVE, (server, operation) -> {
			int[] target = this.scene.targetPosition(this.arena);
			BlockPos targetPos = target == null ? null : new BlockPos(target[0], target[1], target[2]);
			CompletableFuture<Observation> work = new CompletableFuture<>();
			this.deferredCapture = () -> {
				Minecraft minecraft = Minecraft.getInstance();
				try {
					CaptureService.Capture capture = CaptureService.capture(minecraft, projection, targetPos);
					LocalPlayer player = minecraft.player;
					BlockPos eye = BlockPos.containing(player.getEyePosition());
					work.complete(new Observation(
						operation.id(),
						capture,
						clientPose(minecraft),
						minecraft.level.getBiome(eye).unwrapKey().map(key -> key.identifier().toString()).orElse(null),
						minecraft.level.getBrightness(LightLayer.SKY, eye),
						minecraft.level.getBrightness(LightLayer.BLOCK, eye)
					));
				} catch (RuntimeException e) {
					work.completeExceptionally(e);
				}
			};
			return new Pending<>(operation, work, 0, 0, observation -> true, (observation, settled) -> observation);
		});
	}

	/**
	 * Captures from each of {@code poses} in turn, in one operation. For each pose the client
	 * moves its player there, the server following as it would any spectator's movement; the
	 * game's own frame loop then renders until the player is at the pose and every section is
	 * compiled for at least {@link #STABLE_FRAMES} frames, and the capture runs between that
	 * frame and the next. Skipping the server-side placement and its settling, and stepping
	 * per frame rather than per tick, saves several ticks per pose. Poses must lie in the current scene near the player, whose surroundings
	 * the reset readied. Returns one observation per pose, in order.
	 */
	public CompletableFuture<List<Observation>> observeAll(CaptureProjection projection, List<CameraPose> poses) {
		return start(OperationKind.OBSERVE, (server, operation) -> {
			int[] target = this.scene.targetPosition(this.arena);
			PoseSequence sequence = new PoseSequence(operation, projection, target == null ? null : new BlockPos(target[0], target[1], target[2]), poses);
			this.poseSequence = sequence;
			return new Pending<>(operation, sequence.result, 0, 0, observations -> true, (observations, settled) -> observations);
		});
	}

	/** The poses of one {@link #observeAll} operation, advanced between frames. */
	private final class PoseSequence {
		private final Operation operation;
		private final CaptureProjection projection;
		private final @Nullable BlockPos target;
		private final List<CameraPose> poses;
		private final List<Observation> observations = new ArrayList<>();
		private final CompletableFuture<List<Observation>> result = new CompletableFuture<>();
		private int index;
		private long movedAtFrame = -1;

		PoseSequence(Operation operation, CaptureProjection projection, @Nullable BlockPos target, List<CameraPose> poses) {
			this.operation = operation;
			this.projection = projection;
			this.target = target;
			this.poses = List.copyOf(poses);
		}

		/** Moves to the next pose, or captures it once the client renders it; returns true when done. */
		boolean step(Minecraft minecraft) {
			try {
				CameraPose pose = this.poses.get(this.index);
				if (this.movedAtFrame < 0) {
					LocalPlayer player = minecraft.player;
					player.snapTo(pose.x(), pose.y(), pose.z(), pose.yawDegrees(), pose.pitchDegrees());
					player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
					this.movedAtFrame = EnvironmentRuntime.this.frames;
					return false;
				}

				if (EnvironmentRuntime.this.frames - this.movedAtFrame < STABLE_FRAMES || !atPose(minecraft, pose) || !sectionsCompiled(minecraft)) {
					return false;
				}

				CaptureService.Capture capture = CaptureService.capture(minecraft, this.projection, this.target);
				BlockPos eye = BlockPos.containing(minecraft.player.getEyePosition());
				this.observations.add(new Observation(
					this.operation.id(),
					capture,
					clientPose(minecraft),
					minecraft.level.getBiome(eye).unwrapKey().map(key -> key.identifier().toString()).orElse(null),
					minecraft.level.getBrightness(LightLayer.SKY, eye),
					minecraft.level.getBrightness(LightLayer.BLOCK, eye)
				));
				this.index++;
				this.movedAtFrame = -1;
				if (this.index == this.poses.size()) {
					this.result.complete(List.copyOf(this.observations));
					return true;
				}

				return false;
			} catch (RuntimeException e) {
				this.result.completeExceptionally(e);
				return true;
			}
		}
	}

	/** Movement actions arrive in M7; until then every step is rejected without changing state. */
	public CompletableFuture<Void> step(String action) {
		return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.UNSUPPORTED, "step actions are not implemented until M7: " + action));
	}

	private <R> CompletableFuture<R> start(OperationKind kind, BiFunction<IntegratedServer, Operation, Pending<?, R>> launcher) {
		Minecraft minecraft = Minecraft.getInstance();
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, kind + " needs a local client with an integrated server"));
		}

		Operation operation;
		try {
			operation = this.coordinator.begin(kind, this.tick);
		} catch (EnvironmentException e) {
			return CompletableFuture.failedFuture(e);
		}

		try {
			Pending<?, R> started = launcher.apply(server, operation);
			this.pending = started;
			return started.result;
		} catch (RuntimeException e) {
			onFailure(operation, e);
			return CompletableFuture.failedFuture(e);
		}
	}

	private void tick() {
		this.tick++;
		Runnable capture = this.deferredCapture;
		this.deferredCapture = null;
		if (capture != null) {
			capture.run();
		}


		Pending<?, ?> running = this.pending;
		// Completing an operation may start the caller's next one; keep that one pending.
		if (running != null && running.poll(this.tick) && this.pending == running) {
			this.pending = null;
		}
	}

	private void onFailure(Operation operation, RuntimeException error) {
		this.coordinator.fail(operation, error.getMessage());
		if (operation.kind() == OperationKind.RESET) {
			this.scene = null;
			this.layoutSha256 = null;
		}
	}

	private static ServerPlayer player(IntegratedServer server) {
		ServerPlayer player = server.getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID());
		if (player == null) {
			throw new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "the local player is not on the integrated server");
		}

		return player;
	}

	private static boolean inArena(Minecraft minecraft) {
		return minecraft.level != null && minecraft.level.dimension().equals(ArenaDimension.LEVEL);
	}

	private static void markSectionsDirty(Minecraft minecraft, BlockBox box) {
		minecraft.level.setSectionRangeDirty(
			SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minY()), SectionPos.blockToSectionCoord(box.minZ()),
			SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxY()), SectionPos.blockToSectionCoord(box.maxZ())
		);
	}

	private static boolean sectionsCompiled(Minecraft minecraft) {
		return minecraft.levelRenderer.hasRenderedAllSections();
	}

	private static boolean atPose(Minecraft minecraft, CameraPose pose) {
		LocalPlayer player = minecraft.player;
		return player != null
			&& Math.abs(player.getX() - pose.x()) < POSE_TOLERANCE
			&& Math.abs(player.getY() - pose.y()) < POSE_TOLERANCE
			&& Math.abs(player.getZ() - pose.z()) < POSE_TOLERANCE
			&& Math.abs(player.getYRot() - pose.yawDegrees()) < POSE_TOLERANCE
			&& Math.abs(player.getXRot() - pose.pitchDegrees()) < POSE_TOLERANCE;
	}

	private static CameraPose clientPose(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		return new CameraPose(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	/**
	 * Whether the client shows a freshly built scene: in the arena at the first viewpoint,
	 * every chunk of the reset volume within render distance received, the readiness volume
	 * matching the server's hash, then every arena section rebuilt and stable for
	 * {@link #STABLE_FRAMES} frames.
	 */
	private final class SceneReadiness {
		private final ArenaLayout layout;
		private final CameraPose pose;
		private final RenderStability stability = new RenderStability();
		private boolean refreshed;

		SceneReadiness(ArenaLayout layout, CameraPose pose) {
			this.layout = layout;
			this.pose = pose;
		}

		boolean ready(Minecraft minecraft, ArenaExecutor.ResetResult result) {
			boolean shown = inArena(minecraft) && atPose(minecraft, this.pose) && chunksReceived(minecraft, this.layout.resetVolume())
				&& result.readinessSha256().equals(BlockStates.layoutSha256(minecraft.level, result.readinessVolume()));
			if (shown && !this.refreshed) {
				// Change marks for the arena can be dropped while the camera waits on the
				// distant staging platform; rebuild every arena section from the client's blocks.
				this.refreshed = true;
				markSectionsDirty(minecraft, this.layout.resetVolume());
				return this.stability.holds(false);
			}

			return this.stability.holds(shown && this.refreshed && sectionsCompiled(minecraft));
		}
	}

	/** Whether the client holds every chunk of {@code box} the server sends within render distance of the camera. */
	private static boolean chunksReceived(Minecraft minecraft, BlockBox box) {
		int distance = minecraft.options.getEffectiveRenderDistance();
		int centreX = SectionPos.blockToSectionCoord(minecraft.player.getBlockX());
		int centreZ = SectionPos.blockToSectionCoord(minecraft.player.getBlockZ());
		int[] chunks = box.chunkRange();
		for (int x = Math.max(chunks[0], centreX - distance); x <= Math.min(chunks[2], centreX + distance); x++) {
			for (int z = Math.max(chunks[1], centreZ - distance); z <= Math.min(chunks[3], centreZ + distance); z++) {
				if (ChunkTrackingView.isInViewDistance(centreX, centreZ, distance, x, z) && !minecraft.level.getChunkSource().hasChunk(x, z)) {
					return false;
				}
			}
		}

		return true;
	}

	/** Whether render conditions have held over at least {@link #STABLE_FRAMES} complete level frames. */
	private final class RenderStability {
		private long since = EnvironmentRuntime.this.frames;

		boolean holds(boolean conditions) {
			if (!conditions) {
				this.since = EnvironmentRuntime.this.frames;
				return false;
			}

			return EnvironmentRuntime.this.frames - this.since >= STABLE_FRAMES;
		}
	}

	/**
	 * One running operation: off-thread work, then a readiness gate that starts when the
	 * work finishes, then a result built on the client thread.
	 */
	private final class Pending<V, R> {
		private final Operation operation;
		private final CompletableFuture<V> work;
		private final int settleTicks;
		private final int timeoutTicks;
		private final Predicate<V> ready;
		private final BiFunction<V, Long, R> complete;
		private final CompletableFuture<R> result = new CompletableFuture<>();
		private final long startTick = EnvironmentRuntime.this.tick;
		private @Nullable ReadinessGate gate;
		private @Nullable Runnable afterWork;

		Pending(Operation operation, CompletableFuture<V> work, int settleTicks, int timeoutTicks, Predicate<V> ready, BiFunction<V, Long, R> complete) {
			this.operation = operation;
			this.work = work;
			this.settleTicks = settleTicks;
			this.timeoutTicks = timeoutTicks;
			this.ready = ready;
			this.complete = complete;
		}

		/** Advances the operation; returns true once it has succeeded or failed. */
		boolean poll(long tick) {
			if (!this.work.isDone()) {
				if (tick - this.startTick <= EnvironmentRuntime.this.timing.serverTimeoutTicks()) {
					return false;
				}

				return fail(new EnvironmentException(EnvironmentException.Reason.TIMEOUT, this.operation.kind() + " work did not finish within " + EnvironmentRuntime.this.timing.serverTimeoutTicks() + " ticks"));
			}

			try {
				V value = this.work.join();
				if (this.gate == null) {
					if (this.afterWork != null) {
						this.afterWork.run();
					}

					this.gate = new ReadinessGate(this.settleTicks, Math.max(this.settleTicks, this.timeoutTicks), tick);
				}

				return switch (this.gate.poll(tick, this.ready.test(value))) {
					case WAITING -> false;
					case TIMED_OUT -> fail(new EnvironmentException(EnvironmentException.Reason.TIMEOUT, this.operation.kind() + " was not ready within " + this.timeoutTicks + " ticks"));
					case READY -> {
						R built = this.complete.apply(value, this.gate.waited(tick));
						EnvironmentRuntime.this.coordinator.complete(this.operation);
						this.result.complete(built);
						yield true;
					}
				};
			} catch (CompletionException | CancellationException e) {
				return fail(e.getCause() instanceof RuntimeException cause ? cause : new EnvironmentException(EnvironmentException.Reason.WORLD_MISMATCH, String.valueOf(e.getCause())));
			} catch (RuntimeException e) {
				return fail(e);
			}
		}

		private boolean fail(RuntimeException error) {
			onFailure(this.operation, error);
			this.result.completeExceptionally(error);
			return true;
		}
	}
}
