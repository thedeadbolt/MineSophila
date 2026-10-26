package io.github.thedeadbolt.honeyfactory.client.runtime;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.mixin.AtmosphericFogAccessor;
import io.github.thedeadbolt.honeyfactory.client.mixin.ClientLevelAccessor;
import io.github.thedeadbolt.honeyfactory.client.mixin.FogRendererAccessor;
import io.github.thedeadbolt.honeyfactory.client.mixin.LevelExtractorAccessor;
import io.github.thedeadbolt.honeyfactory.client.mixin.SectionOcclusionGraphAccessor;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.control.ActionMacros;
import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorState;
import io.github.thedeadbolt.honeyfactory.control.TrueState;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import io.github.thedeadbolt.honeyfactory.environment.Action;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentSettings;
import io.github.thedeadbolt.honeyfactory.environment.Operation;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.environment.OperationKind;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.environment.ReadinessGate;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import io.github.thedeadbolt.honeyfactory.world.Baseline;
import io.github.thedeadbolt.honeyfactory.world.BlockStates;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client-side environment coordinator: the single implementation of
 * {@code reset}, {@code setViewpoint}, {@code observe}, {@code status} and {@code step}
 * that commands, tests and the dataset runner all call.
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

	/**
	 * The outcome of one step, ordered within its scene by {@code index} from 1. Values are in
	 * the action's unit; {@code completedTicks} counts player ticks the action held inputs
	 * for. {@code startTick} and {@code endTick} are client ticks when the action began and
	 * ended, and {@code serverTick} is when the server confirmed every input released. The
	 * flags describe the player when the action ended; {@code collided} is whether it hit
	 * anything sideways during it. The observation, and so the resulting pose, follows
	 * settling.
	 */
	public record StepResult(
		String operationId,
		String sceneOperationId,
		int index,
		Action action,
		double completed,
		int completedTicks,
		long startTick,
		long endTick,
		long serverTick,
		boolean autoJump,
		int autoJumps,
		boolean collided,
		boolean onGround,
		boolean horizontalCollision,
		CameraPose before,
		Observation observation
	) {
		public double requested() {
			return this.action.requested();
		}

		public CameraPose after() {
			return this.observation.pose();
		}
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

	/** One control step, the capture it returned, and the control session it belongs to. */
	/**
	 * A control step's record and its captures: {@code capture} at the end of the tick, and
	 * {@code frames} at partial ticks {@code k / framesPerTick}, the last being
	 * {@code capture}. A reset's step 0 has the one still frame.
	 */
	public record ControlResult(ControlStep step, CaptureService.Capture capture, long session, List<CaptureService.Capture> frames) {
	}

	/** The most frames a control tick captures. */
	public static final int MAX_FRAMES_PER_TICK = 8;

	private static final EnvironmentRuntime INSTANCE = new EnvironmentRuntime(Timing.DEFAULT);
	private static final double POSE_TOLERANCE = 1e-6;
	/**
	 * Level frames the render conditions must hold for. Block updates only reach the
	 * section compile queue during the next frame's extraction, so an empty queue is
	 * trustworthy only after a frame has run since the change.
	 */
	private static final int STABLE_FRAMES = 2;
	/**
	 * Largest change in the rain fog multiplier between readiness checks at which fog counts
	 * as settled; the fog's end distance is then within a few hundredths of a block of its
	 * final value.
	 */
	private static final float RAIN_FOG_SETTLED = 1e-5F;

	private OperationCoordinator coordinator = new OperationCoordinator();
	private Timing timing;
	private EnvironmentSettings settings = EnvironmentSettings.DEFAULT;
	private long tick;
	private long frames;
	private @Nullable Pending<?, ?> pending;
	private @Nullable ArenaLayout arena;
	private @Nullable ScenePlan scene;
	private @Nullable String layoutSha256;
	private @Nullable String sceneOperationId;
	private int stepsInScene;
	private @Nullable StepDriver stepDriver;
	private ArenaExecutor.@Nullable ResetResult lastReset;
	private @Nullable Runnable deferredCapture;
	private @Nullable PoseSequence poseSequence;
	/** Wall time of each batched pose since the last drain, from moving the camera to the finished capture. */
	private final List<Long> poseNanos = new ArrayList<>();
	private boolean poseStepQueued;
	/** The running control session: its projection and seed, the body's rates and the last step; null when none runs. */
	private @Nullable ControlSession control;

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

		endStep();
		ControlSession session = this.control;
		this.control = null;
		if (session != null) {
			// The user's auto-jump option is saved to disk; give it back even as the world closes.
			session.restore(Minecraft.getInstance());
		}

		FramePacing.restoreAll(Minecraft.getInstance());
		this.coordinator = new OperationCoordinator();
		this.deferredCapture = null;
		this.poseSequence = null;
		this.arena = null;
		this.scene = null;
		this.layoutSha256 = null;
		this.sceneOperationId = null;
	}

	/** The wall time of each batched pose since the last call, from moving the camera to the finished capture, and forgets them. */
	public long[] drainPoseNanos() {
		long[] drained = this.poseNanos.stream().mapToLong(Long::longValue).toArray();
		this.poseNanos.clear();
		return drained;
	}

	public OperationCoordinator.Status status() {
		return this.coordinator.status();
	}

	/** Finished operations in order, oldest first; see {@link OperationCoordinator#history}. */
	public List<OperationCoordinator.Finished> history() {
		return this.coordinator.history();
	}

	public EnvironmentSettings settings() {
		return this.settings;
	}

	/**
	 * Sets action limits and auto-jump for later steps.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#BUSY} while an operation runs
	 */
	public void configure(EnvironmentSettings settings) {
		requireIdle("settings");
		this.settings = Objects.requireNonNull(settings);
	}

	public Timing timing() {
		return this.timing;
	}

	/**
	 * Sets settling minimums and timeouts for later operations.
	 *
	 * @throws EnvironmentException {@link EnvironmentException.Reason#BUSY} while an operation runs
	 */
	public void configureTiming(Timing timing) {
		requireIdle("timing");
		this.timing = Objects.requireNonNull(timing);
	}

	private void requireIdle(String what) {
		Operation running = this.coordinator.status().current();
		if (running != null) {
			throw new EnvironmentException(EnvironmentException.Reason.BUSY, "cannot change " + what + " while " + running.id() + " (" + running.kind() + ") runs");
		}
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
			SceneReadiness readiness = new SceneReadiness(layout, firstViewpoint, plan.weather());
			return new Pending<>(operation, work, this.timing.resetSettleTicks(), this.timing.resetTimeoutTicks(), result -> readiness.ready(Minecraft.getInstance(), result), (result, settled) -> {
				this.scene = plan;
				this.layoutSha256 = result.layoutSha256();
				this.sceneOperationId = operation.id();
				this.stepsInScene = 0;
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
	 * Resets {@code plan}, places the player at {@code start}, then freezes the world, gives
	 * the player its body where it stands and captures step 0 through {@code projection}.
	 * Every later {@link #controlStep} uses the same projection. The user's input and
	 * auto-jump option are borrowed until {@link #controlEnd}.
	 */
	public CompletableFuture<ControlResult> controlReset(ArenaLayout layout, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed) {
		return controlReset(layout, plan, start, projection, seed, 1);
	}

	/**
	 * The same, capturing {@code framesPerTick} frames per later step (1 to
	 * {@value #MAX_FRAMES_PER_TICK}): the tick's end and evenly spaced points the camera
	 * passes during it, for motion clips.
	 */
	public CompletableFuture<ControlResult> controlReset(ArenaLayout layout, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed, int framesPerTick) {
		if (framesPerTick < 1 || framesPerTick > MAX_FRAMES_PER_TICK) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, "frames per tick must be 1 to " + MAX_FRAMES_PER_TICK + ", got " + framesPerTick));
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isPaused()) {
			return CompletableFuture.failedFuture(paused());
		}

		if (this.control != null) {
			this.control.input.set(MotorCommand.IDLE);
		}

		releaseStill(minecraft);
		return reset(layout, plan, start).thenCompose(state -> start(OperationKind.CONTROL_RESET, (server, operation) -> {
			ControlSession session = this.control == null ? new ControlSession(minecraft) : this.control.restart();
			session.install(minecraft, projection, seed, framesPerTick, this.settings.autoJump());
			// Held from here on, so a failure gives the input back (see onFailure).
			this.control = session;
			TickStepper.get().hold(server);
			CompletableFuture<Long> work = server.submit(() -> ArenaExecutor.embody(server, player(server), layout));
			RenderStability stability = new RenderStability();
			Pending<Long, ControlResult> pending = new Pending<>(operation, work, 0, this.timing.viewpointTimeoutTicks(), embodied -> stability.holds(
				minecraft.level.tickRateManager().isFrozen() && minecraft.gameMode.getPlayerMode() == GameType.ADVENTURE && inArena(minecraft) && sectionsCompiled(minecraft)
			), (embodied, settled) -> {
				this.coordinator.advance(operation, OperationState.CAPTURING);
				Captured still = controlStep(minecraft, operation, session, 0, null, null, ControlStep.Executed.STUDENT, MotorState.ZERO, levelTime(server), new double[3]);
				return session.record(minecraft, operation, still, List.of(still.capture()));
			});
			pending.afterWork = () -> this.coordinator.advance(operation, OperationState.SETTLING);
			return pending;
		}));
	}

	/**
	 * Runs one frozen tick with the {@code executed} side's command and captures it. Both
	 * commands are recorded. The command is checked against the motor limits first; a
	 * refused command advances nothing.
	 */
	public CompletableFuture<ControlResult> controlStep(@Nullable MotorCommand student, @Nullable MotorCommand teacher, ControlStep.Executed executed) {
		ControlSession session = this.control;
		if (session == null || session.last == null) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.NOT_RESET, "control steps need a control reset first"));
		}

		if (Minecraft.getInstance().isPaused()) {
			return CompletableFuture.failedFuture(paused());
		}

		MotorCommand command = executed == ControlStep.Executed.STUDENT ? student : teacher;
		try {
			if (command == null) {
				throw new IllegalArgumentException("the executed " + executed.jsonName() + " command is missing");
			}

			for (MotorCommand offered : new MotorCommand[] {student, teacher}) {
				if (offered != null) {
					this.settings.motor().check(offered);
				}
			}
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new EnvironmentException(EnvironmentException.Reason.INVALID_REQUEST, e.getMessage()));
		}

		return start(OperationKind.CONTROL_STEP, (server, operation) -> {
			Minecraft minecraft = Minecraft.getInstance();
			ArenaLayout layout = this.arena;
			MotorState[] achieved = {session.motor};
			CompletableFuture<TickStepper.Tick> work = TickStepper.get().advance(player -> {
				MotorState.Achieved next = session.motor.apply(command, this.settings.motor(), player.getYRot(), player.getXRot());
				player.setYRot(next.yaw());
				player.setXRot(next.pitch());
				player.setYHeadRot(next.yaw());
				session.input.set(command);
				achieved[0] = next.state();
			});
			RenderStability stability = new RenderStability();
			Pending<TickStepper.Tick, ControlResult> pending = new Pending<>(operation, work, 0, this.timing.viewpointTimeoutTicks(), tick -> {
				requireInArena(minecraft, layout);
				return stability.holds(sectionsCompiled(minecraft));
			}, (tick, settled) -> {
				this.coordinator.advance(operation, OperationState.CAPTURING);
				session.motor = achieved[0];
				int index = session.last.step().index() + 1;
				CameraPose from = session.last.step().truth().pose();
				Captured captured = controlStep(minecraft, operation, session, index, student, teacher, executed, achieved[0], tick.serverTick(), tick.displacement());
				return session.record(minecraft, operation, captured, frames(minecraft, session, from, captured));
			});
			pending.afterWork = () -> this.coordinator.advance(operation, OperationState.SETTLING);
			return pending;
		});
	}

	/** A paused game ticks neither the server nor the player, so a step could never finish. */
	private static EnvironmentException paused() {
		return new EnvironmentException(EnvironmentException.Reason.INVALID_STATE, "the game is paused; close the menu, and turn off Pause on Lost Focus to control it from another window");
	}

	/** The last control step, or null when no control session runs. */
	public @Nullable ControlResult lastControlStep() {
		return this.control == null ? null : this.control.last;
	}

	/** Ends control session {@code session} if it is still the running one; a newer session is left alone. */
	public void controlEnd(long session) {
		if (this.control != null && this.control.id == session) {
			controlEnd();
		}
	}

	/** Ends the control session: the world ticks again and the user's input and auto-jump option are back. */
	public void controlEnd() {
		Minecraft minecraft = Minecraft.getInstance();
		releaseStill(minecraft);

		ControlSession session = this.control;
		this.control = null;
		if (session != null) {
			session.restore(minecraft);
		}
	}

	/**
	 * Releases a held stepper with the body at rest. Otherwise the player would resume with
	 * the momentum of its last step, and a spectator keeps drifting with it.
	 */
	private static void releaseStill(Minecraft minecraft) {
		if (TickStepper.get().held() && minecraft.getSingleplayerServer() != null) {
			if (minecraft.player != null) {
				minecraft.player.setDeltaMovement(Vec3.ZERO);
			}

			TickStepper.get().release(minecraft.getSingleplayerServer());
		}
	}

	/** Builds one step's record from the client player after its tick and a fresh capture. */
	private Captured controlStep(Minecraft minecraft, Operation operation, ControlSession session, int index, @Nullable MotorCommand student, @Nullable MotorCommand teacher,
		ControlStep.Executed executed, MotorState achieved, long serverTick, double[] velocity) {
		int[] cell = this.scene.targetPosition(this.arena);
		BlockPos targetPos = cell == null ? null : new BlockPos(cell[0], cell[1], cell[2]);
		Observation observation = capture(minecraft, operation, session.projection, targetPos);
		LocalPlayer player = minecraft.player;
		CameraPose pose = observation.pose();
		CapturedImages images = observation.capture().images();
		BlockBox box = cell == null ? null : BlockBox.cell(cell);
		CameraFrame camera = CameraFrame.of(pose, ConfigResolver.EYE_HEIGHT_BLOCKS);
		SampleRecord.Target target = SampleRecord.Target.of(box != null, box != null && session.projection.intersects(camera, box),
			MaskStatistics.of(images.visibleMask(), images.width(), images.height()), images.unoccludedPixels(), box == null ? null : camera.distanceTo(box.center()));
		BodySenses senses = BodySenses.of(pose, velocity, achieved, player.onGround(), index > 0 && player.horizontalCollision, player.isInWater(), session.lastExecuted);
		TrueState truth = new TrueState(pose, velocity, this.layoutSha256, session.seed, serverTick, target, box != null && images.unoccludedTouchesEdge());
		ControlStep step = new ControlStep(index, student, teacher, executed, achieved, senses, truth);
		session.lastExecuted = step.executedCommand();
		return new Captured(step, observation.capture());
	}

	private record Captured(ControlStep step, CaptureService.Capture capture) {
	}

	/**
	 * A step's frames: the camera's poses at partial ticks {@code k / framesPerTick} between
	 * the last step's pose and this one's, then the end-of-tick capture. The tick only moves
	 * the body a fraction of a block, so the readiness check of the end pose holds for them;
	 * a frame that moves into another section rebuilds the section graph first (see
	 * {@link CaptureService#captureAt}).
	 */
	private List<CaptureService.Capture> frames(Minecraft minecraft, ControlSession session, CameraPose from, Captured captured) {
		int count = session.framesPerTick;
		if (count == 1) {
			return List.of(captured.capture());
		}

		CameraPose to = captured.step().truth().pose();
		int[] cell = this.scene.targetPosition(this.arena);
		BlockPos target = cell == null ? null : new BlockPos(cell[0], cell[1], cell[2]);
		List<CaptureService.Capture> frames = new ArrayList<>(count);
		for (int k = 1; k < count; k++) {
			double t = k / (double) count;
			CameraPose at = new CameraPose(
				from.x() + (to.x() - from.x()) * t,
				from.y() + (to.y() - from.y()) * t,
				from.z() + (to.z() - from.z()) * t,
				// Poses may hold wrapped yaws (181.8 as -178.2), so turn the short way.
				(float) (from.yawDegrees() + Mth.wrapDegrees(to.yawDegrees() - from.yawDegrees()) * t),
				(float) (from.pitchDegrees() + (to.pitchDegrees() - from.pitchDegrees()) * t)
			);
			frames.add(CaptureService.captureAt(minecraft, session.projection, target, at, t));
		}

		frames.add(captured.capture());
		return List.copyOf(frames);
	}

	private static long levelTime(IntegratedServer server) {
		ServerPlayer player = player(server);
		return player.level().getGameTime();
	}

	private static void requireInArena(Minecraft minecraft, ArenaLayout arena) {
		LocalPlayer player = minecraft.player;
		BlockBox volume = arena.resetVolume();
		BlockPos feet = player.blockPosition();
		BlockPos eye = BlockPos.containing(player.getEyePosition());
		if (!inArena(minecraft) || !volume.contains(feet.getX(), feet.getY(), feet.getZ()) || !volume.contains(eye.getX(), eye.getY(), eye.getZ())) {
			throw new EnvironmentException(EnvironmentException.Reason.OUT_OF_ARENA, "a control step took the player to " + clientPose(minecraft) + ", outside the arena reset volume " + volume);
		}
	}

	/** A control session's state; the user's input and auto-jump option are kept to restore. */
	private static final class ControlSession {
		private static long sessions;
		private final long id = ++sessions;
		private final MotorInput input = new MotorInput();
		private @Nullable ClientInput savedInput;
		private @Nullable Boolean savedAutoJump;
		/** The uncapped frame rate, held from the first install until restore. */
		private FramePacing.@Nullable Lease pacing;
		private CaptureProjection projection;
		private long seed;
		private int framesPerTick = 1;
		private MotorState motor = MotorState.ZERO;
		private MotorCommand lastExecuted = MotorCommand.IDLE;
		private @Nullable ControlResult last;

		ControlSession(@Nullable Minecraft minecraft) {
		}

		/** A new session after a new reset; the input borrowed by the old one stays borrowed and is handed on. */
		ControlSession restart() {
			ControlSession next = new ControlSession(null);
			next.savedInput = this.savedInput;
			next.savedAutoJump = this.savedAutoJump;
			next.pacing = this.pacing;
			return next;
		}

		void install(Minecraft minecraft, CaptureProjection projection, long seed, int framesPerTick, boolean autoJump) {
			this.projection = projection;
			this.seed = seed;
			this.framesPerTick = framesPerTick;
			if (this.savedInput == null) {
				this.savedInput = minecraft.player.input;
				this.savedAutoJump = minecraft.options.autoJump().get();
			}

			if (this.pacing == null) {
				this.pacing = FramePacing.uncap(minecraft);
			}

			this.input.set(MotorCommand.IDLE);
			minecraft.player.input = this.input;
			minecraft.options.autoJump().set(autoJump);
		}

		ControlResult record(Minecraft minecraft, Operation operation, Captured captured, List<CaptureService.Capture> frames) {
			this.last = new ControlResult(captured.step(), captured.capture(), this.id, frames);
			return this.last;
		}

		void restore(Minecraft minecraft) {
			if (minecraft.player != null && minecraft.player.input == this.input) {
				minecraft.player.input = this.savedInput != null ? this.savedInput : new KeyboardInput(minecraft.options);
			}

			if (this.savedAutoJump != null) {
				minecraft.options.autoJump().set(this.savedAutoJump);
			}

			if (this.pacing != null) {
				this.pacing.close(minecraft);
				this.pacing = null;
			}
		}
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
				try {
					work.complete(capture(Minecraft.getInstance(), operation, projection, targetPos));
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
	 * game's own frame loop then renders until the player is at the pose, a client tick has
	 * sampled the environment there and every section is compiled for at least
	 * {@link #STABLE_FRAMES} frames, and the capture runs between that
	 * frame and the next. Skipping the server-side placement and its settling, and stepping
	 * per frame rather than per tick, saves several ticks per pose. Poses must lie in the current scene near the player, whose surroundings
	 * the reset readied. Returns one observation per pose, in order.
	 */
	public CompletableFuture<List<Observation>> observeAll(CaptureProjection projection, List<CameraPose> poses) {
		Minecraft minecraft = Minecraft.getInstance();
		FramePacing.Lease pacing = FramePacing.uncap(minecraft);
		return start(OperationKind.OBSERVE, (server, operation) -> {
			int[] target = this.scene.targetPosition(this.arena);
			PoseSequence sequence = new PoseSequence(operation, projection, target == null ? null : new BlockPos(target[0], target[1], target[2]), poses);
			this.poseSequence = sequence;
			return new Pending<>(operation, sequence.result, 0, 0, observations -> true, (observations, settled) -> observations);
		}).whenComplete((observations, error) -> minecraft.execute(() -> pacing.close(minecraft)));
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
		/**
		 * Whether a client tick has run since a frame was drawn at the current pose. The
		 * camera samples sky, fog and light colours from the biomes around it only in its
		 * tick, so until then a frame shows the previous pose's colours.
		 */
		private boolean environmentSampled;
		private long movedAtNanos;

		PoseSequence(Operation operation, CaptureProjection projection, @Nullable BlockPos target, List<CameraPose> poses) {
			this.operation = operation;
			this.projection = projection;
			this.target = target;
			this.poses = List.copyOf(poses);
		}

		/** Moves to the next pose, or captures it once the client renders it; returns true when done. */
		/** Called after each client tick, once the camera has sampled its surroundings. */
		void ticked() {
			if (this.movedAtFrame >= 0 && EnvironmentRuntime.this.frames > this.movedAtFrame) {
				this.environmentSampled = true;
			}
		}

		boolean step(Minecraft minecraft) {
			try {
				CameraPose pose = this.poses.get(this.index);
				if (this.movedAtFrame < 0) {
					LocalPlayer player = minecraft.player;
					player.snapTo(pose.x(), pose.y(), pose.z(), pose.yawDegrees(), pose.pitchDegrees());
					player.setDeltaMovement(Vec3.ZERO);
					this.movedAtFrame = EnvironmentRuntime.this.frames;
					this.movedAtNanos = System.nanoTime();
					this.environmentSampled = false;
					return false;
				}

				if (EnvironmentRuntime.this.frames - this.movedAtFrame < STABLE_FRAMES || !this.environmentSampled || !atPose(minecraft, pose) || !sectionsCompiled(minecraft)) {
					return false;
				}

				this.observations.add(capture(minecraft, this.operation, this.projection, this.target));
				EnvironmentRuntime.this.poseNanos.add(System.nanoTime() - this.movedAtNanos);
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

	/**
	 * Applies {@code action} to the player's body in the current scene, then captures
	 * through {@code projection}. The first step after a reset or viewpoint turns the
	 * spectator camera into an invulnerable adventure-mode body where it stands, so gravity
	 * and collisions apply and blocks cannot change. The step's own input replaces the
	 * keyboard and the configured auto-jump replaces the user's option until the step ends;
	 * both are restored however it ends. Turn, look and stop apply at once; move and wait
	 * run for their ticks. Every input is released when the action ends, and the step
	 * settles until the client has rendered the result and the server has seen the release.
	 *
	 * <p>A request outside the configured limits, or a mine request, is rejected without
	 * changing state. Leaving the arena's reset volume fails the step with
	 * {@link EnvironmentException.Reason#OUT_OF_ARENA}.
	 */
	public CompletableFuture<StepResult> step(Action action, CaptureProjection projection) {
		try {
			this.settings.limits().check(action);
		} catch (EnvironmentException e) {
			return CompletableFuture.failedFuture(e);
		}

		return start(OperationKind.STEP, (server, operation) -> {
			Minecraft minecraft = Minecraft.getInstance();
			ArenaLayout layout = this.arena;
			StepDriver driver = new StepDriver(action, this.settings.autoJump(), layout, clientPose(minecraft));
			this.stepDriver = driver;
			driver.install(minecraft);
			driver.embodied = server.submit(() -> ArenaExecutor.embody(server, player(server), layout));
			RenderStability stability = new RenderStability();
			int[] target = this.scene.targetPosition(layout);
			BlockPos targetPos = target == null ? null : new BlockPos(target[0], target[1], target[2]);
			Pending<StepDriver.End, StepResult> pending = new Pending<>(operation, driver.end, this.timing.viewpointSettleTicks(), this.timing.viewpointTimeoutTicks(), end -> {
				driver.requireInArena(minecraft);
				return stability.holds(inArena(minecraft) && sectionsCompiled(minecraft)) && driver.releaseConfirmed(server);
			}, (end, settled) -> {
				this.coordinator.advance(operation, OperationState.CAPTURING);
				Observation observation = capture(minecraft, operation, projection, targetPos);
				int autoJumps = driver.input.autoJumps();
				endStep();
				return new StepResult(
					operation.id(), this.sceneOperationId, ++this.stepsInScene, action, end.completed(), end.ticks(),
					end.startTick(), end.endTick(), driver.serverTick, driver.autoJump, autoJumps,
					end.collided(), end.onGround(), end.horizontalCollision(), driver.before, observation
				);
			});
			// The work is the action itself: waiting for the body, then its ticks.
			pending.workTimeoutTicks = action.ticks() + this.timing.viewpointTimeoutTicks();
			pending.afterWork = () -> this.coordinator.advance(operation, OperationState.SETTLING);
			return pending;
		});
	}

	/**
	 * One step's action, advanced at the end of each client tick: it waits until the client
	 * shows the body and the configured auto-jump, applies the action, then counts the
	 * player ticks that poll its input until the action is done.
	 */
	private final class StepDriver {
		/** How the action ended; ticks are client ticks. */
		record End(double completed, int ticks, long startTick, long endTick, boolean collided, boolean onGround, boolean horizontalCollision) {
		}

		private final Action action;
		private final boolean autoJump;
		private final ArenaLayout arena;
		private final CameraPose before;
		private final ScriptedInput input = new ScriptedInput();
		private final CompletableFuture<End> end = new CompletableFuture<>();
		private CompletableFuture<Long> embodied = new CompletableFuture<>();
		private @Nullable ClientInput savedInput;
		private @Nullable Boolean savedAutoJump;
		private @Nullable CompletableFuture<Long> release;
		private long serverTick = -1;
		private long startTick = -1;
		private int startTicked;
		private boolean collided;
		/** A turn's or look's rates, one per player tick, from the shared motor limits; null for other actions. */
		private @Nullable List<MotorCommand> rotation;
		private int rotated;
		private float fromYaw;
		private float fromPitch;

		StepDriver(Action action, boolean autoJump, ArenaLayout arena, CameraPose before) {
			this.action = action;
			this.autoJump = autoJump;
			this.arena = arena;
			this.before = before;
		}

		/** Replaces the keyboard with this step's input and the user's auto-jump option with the configured one. */
		void install(Minecraft minecraft) {
			this.savedInput = minecraft.player.input;
			minecraft.player.input = this.input;
			this.savedAutoJump = minecraft.options.autoJump().get();
			minecraft.options.autoJump().set(this.autoJump);
		}

		/** Gives back the keyboard and the user's option. */
		void restore(Minecraft minecraft) {
			this.input.release();
			if (minecraft.player != null && minecraft.player.input == this.input) {
				minecraft.player.input = this.savedInput != null ? this.savedInput : new KeyboardInput(minecraft.options);
			}

			if (this.savedAutoJump != null) {
				minecraft.options.autoJump().set(this.savedAutoJump);
			}
		}

		void tick(Minecraft minecraft) {
			if (this.end.isDone()) {
				return;
			}

			try {
				if (this.startTick < 0) {
					if (this.embodied.isDone() && (this.embodied.isCompletedExceptionally() || bodyShown(minecraft))) {
						this.embodied.join();
						begin(minecraft);
					}
				} else {
					progress(minecraft);
				}
			} catch (CompletionException e) {
				this.input.release();
				this.end.completeExceptionally(e.getCause() != null ? e.getCause() : e);
			} catch (RuntimeException e) {
				this.input.release();
				this.end.completeExceptionally(e);
			}
		}

		/** Whether the client has the body: adventure mode, not flying, and auto-jump as configured. */
		private boolean bodyShown(Minecraft minecraft) {
			LocalPlayer player = minecraft.player;
			return minecraft.gameMode.getPlayerMode() == GameType.ADVENTURE && !player.getAbilities().flying && player.isAutoJumpEnabled() == this.autoJump;
		}

		private void begin(Minecraft minecraft) {
			LocalPlayer player = minecraft.player;
			this.startTick = EnvironmentRuntime.this.tick;
			this.startTicked = this.input.ticked();
			switch (this.action) {
				case Action.Turn turn -> startRotation(player, ActionMacros.expand(turn, EnvironmentRuntime.this.settings.motor()));
				case Action.Look look -> {
					// A look stops at Minecraft's pitch limits, so it ramps over the part it can complete.
					float reachable = look.resultingPitch(player.getXRot()) - player.getXRot();
					startRotation(player, ActionMacros.expand(new Action.Look(reachable), EnvironmentRuntime.this.settings.motor()));
				}
				case Action.MoveForward move -> this.input.hold(move.ticks());
				case Action.Wait wait -> {
				}
				case Action.Stop stop -> {
					this.input.release();
					finish(player, 0.0, 0);
				}
				case Action.Mine mine -> throw new EnvironmentException(EnvironmentException.Reason.UNSUPPORTED, "MINE is not supported");
			}
		}

		/** Starts turning or looking: the first rate now, then one per player tick, as a control step would. */
		private void startRotation(LocalPlayer player, List<MotorCommand> rates) {
			this.rotation = rates;
			this.fromYaw = player.getYRot();
			this.fromPitch = player.getXRot();
			if (rates.isEmpty()) {
				finish(player, 0.0, 0);
				return;
			}

			rotateBy(player, rates.getFirst());
		}

		private void rotateBy(LocalPlayer player, MotorCommand rates) {
			rotate(player, Action.wrapDegrees((float) (player.getYRot() + rates.turnRate())), Math.clamp((float) (player.getXRot() + rates.pitchRate()), -90.0F, 90.0F));
			this.rotated++;
		}

		private void progress(Minecraft minecraft) {
			LocalPlayer player = minecraft.player;
			this.collided |= player.horizontalCollision;
			requireInArena(minecraft);
			List<MotorCommand> rates = this.rotation;
			if (rates != null) {
				int ticked = this.input.ticked() - this.startTicked;
				if (ticked >= this.rotated && this.rotated < rates.size()) {
					rotateBy(player, rates.get(this.rotated));
				} else if (ticked >= rates.size()) {
					finishRotation(player, rates.size());
				}

				return;
			}

			int ticks = this.action instanceof Action.MoveForward ? this.input.held() : this.input.ticked() - this.startTicked;
			if (ticks >= this.action.ticks()) {
				this.input.release();
				finish(player, ticks, ticks);
			}
		}

		private void finishRotation(LocalPlayer player, int ticks) {
			if (this.action instanceof Action.Turn turn) {
				float turned = Action.wrapDegrees(player.getYRot() - this.fromYaw);
				// A half turn wraps to -180 either way; report it with the requested sign.
				finish(player, turned == -180.0F && turn.degrees() > 0 ? 180.0F : turned, ticks);
			} else {
				finish(player, player.getXRot() - this.fromPitch, ticks);
			}
		}

		private void finish(LocalPlayer player, double completed, int ticks) {
			this.end.complete(new End(completed, ticks, this.startTick, EnvironmentRuntime.this.tick, this.collided, player.onGround(), player.horizontalCollision));
		}

		/** Throws once the player's feet or eye leave the arena's reset volume. */
		void requireInArena(Minecraft minecraft) {
			LocalPlayer player = minecraft.player;
			BlockBox volume = this.arena.resetVolume();
			BlockPos feet = player.blockPosition();
			BlockPos eye = BlockPos.containing(player.getEyePosition());
			if (!inArena(minecraft) || !volume.contains(feet.getX(), feet.getY(), feet.getZ()) || !volume.contains(eye.getX(), eye.getY(), eye.getZ())) {
				throw new EnvironmentException(EnvironmentException.Reason.OUT_OF_ARENA, this.action.type() + " took the player to " + clientPose(minecraft) + ", outside the arena reset volume " + volume);
			}
		}

		/** Whether the server has seen every input released; asks it again each tick until it has. */
		boolean releaseConfirmed(IntegratedServer server) {
			if (this.release == null) {
				this.release = server.submit(() -> player(server).getLastClientInput().equals(Input.EMPTY) ? (long) server.getTickCount() : -1L);
				return false;
			}

			if (!this.release.isDone()) {
				return false;
			}

			long tick = this.release.join();
			this.release = null;
			if (tick < 0) {
				return false;
			}

			this.serverTick = tick;
			return true;
		}
	}

	/** Sets the player's view without interpolating from the previous rotation. */
	private static void rotate(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		player.setYHeadRot(yaw);
	}

	/** Ends the running step, if any, giving back the keyboard and the user's auto-jump option. */
	private void endStep() {
		StepDriver driver = this.stepDriver;
		this.stepDriver = null;
		if (driver != null) {
			driver.restore(Minecraft.getInstance());
		}
	}

	/** Captures from the current camera; light levels and biome are read at the eye. */
	private static Observation capture(Minecraft minecraft, Operation operation, CaptureProjection projection, @Nullable BlockPos target) {
		CaptureService.Capture capture = CaptureService.capture(minecraft, projection, target);
		BlockPos eye = BlockPos.containing(minecraft.player.getEyePosition());
		return new Observation(
			operation.id(),
			capture,
			clientPose(minecraft),
			minecraft.level.getBiome(eye).unwrapKey().map(key -> key.identifier().toString()).orElse(null),
			minecraft.level.getBrightness(LightLayer.SKY, eye),
			minecraft.level.getBrightness(LightLayer.BLOCK, eye)
		);
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


		StepDriver driver = this.stepDriver;
		if (driver != null) {
			driver.tick(Minecraft.getInstance());
		}

		PoseSequence sequence = this.poseSequence;
		if (sequence != null) {
			sequence.ticked();
		}

		Pending<?, ?> running = this.pending;
		// Completing an operation may start the caller's next one; keep that one pending.
		if (running != null && running.poll(this.tick) && this.pending == running) {
			this.pending = null;
		}
	}

	private void onFailure(Operation operation, RuntimeException error) {
		endStep();
		if (operation.kind() == OperationKind.CONTROL_STEP) {
			TickStepper.get().cancel();
		} else if ((operation.kind() == OperationKind.CONTROL_RESET || operation.kind() == OperationKind.RESET) && this.control != null) {
			// A reset that fails mid-session, or a control reset that fails after borrowing the input, gives everything back.
			controlEnd();
		}

		this.coordinator.fail(operation, error.getMessage(), this.tick);
		if (operation.kind() == OperationKind.RESET) {
			this.scene = null;
			this.layoutSha256 = null;
			this.sceneOperationId = null;
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

	/**
	 * Whether every section in view is compiled and the occlusion graph that picks the
	 * sections to draw is current for the camera's position. First rebuilds every arena
	 * section whose blocks changed, in view or not, as a panorama looks every way.
	 */
	private boolean sectionsCompiled(Minecraft minecraft) {
		if (this.arena != null) {
			compileArena(minecraft, this.arena.resetVolume());
		}

		if (!minecraft.levelRenderer.hasRenderedAllSections()) {
			return false;
		}

		SectionOcclusionGraphAccessor graph = (SectionOcclusionGraphAccessor) minecraft.levelRenderer.sectionOcclusionGraph();
		Future<?> rebuild = graph.honeyFactory$fullUpdateTask();
		return !graph.honeyFactory$needsFullUpdate() && (rebuild == null || rebuild.isDone()) && !graph.honeyFactory$needsFrustumUpdate().get();
	}

	/**
	 * Rebuilds, on this thread, every section of {@code box} whose blocks changed since its
	 * mesh was built. The game rebuilds only sections in view; a section behind the camera
	 * would otherwise keep its old mesh until a capture's view turned to it, and that view
	 * would draw it stale. Sections the game would not build yet, without their neighbours
	 * loaded, are left as they are.
	 */
	private static void compileArena(Minecraft minecraft, BlockBox box) {
		ViewArea view = minecraft.levelRenderer.viewArea();
		SectionUpdateTracker tracker = ((LevelExtractorAccessor) minecraft.levelExtractor).honeyFactory$sectionUpdateTracker();
		if (view == null || tracker == null) {
			return;
		}

		RenderRegionCache cache = null;
		for (int x = SectionPos.blockToSectionCoord(box.minX()); x <= SectionPos.blockToSectionCoord(box.maxX()); x++) {
			for (int y = SectionPos.blockToSectionCoord(box.minY()); y <= SectionPos.blockToSectionCoord(box.maxY()); y++) {
				for (int z = SectionPos.blockToSectionCoord(box.minZ()); z <= SectionPos.blockToSectionCoord(box.maxZ()); z++) {
					long node = SectionPos.asLong(x, y, z);
					SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(node);
					SectionRenderDispatcher.RenderSection section = view.getRenderSectionAt(SectionPos.of(x, y, z).origin());
					if (dirty == null || !dirty.isDirty() || section == null
						|| section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED && !tracker.hasAllNeighbors(minecraft.level, node)) {
						continue;
					}

					if (cache == null) {
						cache = new RenderRegionCache();
					}

					section.compileSync(cache.createRegion(minecraft.level, node));
					dirty.setNotDirty();
				}
			}
		}
	}

	/** How strongly rain currently thickens the atmospheric fog; see {@link AtmosphericFogAccessor}. */
	private static float rainFog() {
		for (FogEnvironment environment : FogRendererAccessor.honeyFactory$fogEnvironments()) {
			if (environment instanceof AtmosphericFogAccessor atmospheric) {
				return atmospheric.honeyFactory$rainFogMultiplier();
			}
		}

		return 0.0F;
	}

	/** Whether the client shows {@code weather} at its full rain and thunder levels, not fading towards them. */
	private static boolean weatherShown(Minecraft minecraft, Weather weather) {
		float rain = Baseline.rainLevel(weather);
		return minecraft.level.getRainLevel(1.0F) == rain && minecraft.level.getRainLevel(0.0F) == rain
			&& minecraft.level.getThunderLevel(1.0F) == Baseline.thunderLevel(weather) * rain && minecraft.level.getThunderLevel(0.0F) == Baseline.thunderLevel(weather) * rain;
	}

	/**
	 * Whether the client has applied every light update it received. Until then sections
	 * can compile with stale light and be rebuilt once it arrives.
	 */
	private static boolean lightApplied(Minecraft minecraft) {
		return ((ClientLevelAccessor) minecraft.level).honeyFactory$lightUpdateQueue().isEmpty()
			&& !minecraft.level.getChunkSource().getLightEngine().hasLightWork();
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
	 * matching the server's hash, then the scene's weather at full strength with the fog it
	 * thickens no longer easing, every received light update applied and every arena section
	 * rebuilt, stable for {@link #STABLE_FRAMES} frames.
	 */
	private final class SceneReadiness {
		private final ArenaLayout layout;
		private final CameraPose pose;
		private final Weather weather;
		private final RenderStability stability = new RenderStability();
		private boolean refreshed;
		private float lastRainFog = Float.NaN;

		SceneReadiness(ArenaLayout layout, CameraPose pose, Weather weather) {
			this.layout = layout;
			this.pose = pose;
			this.weather = weather;
		}

		boolean ready(Minecraft minecraft, ArenaExecutor.ResetResult result) {
			float fog = rainFog();
			boolean fogSettled = Math.abs(fog - this.lastRainFog) < RAIN_FOG_SETTLED;
			this.lastRainFog = fog;
			boolean shown = inArena(minecraft) && atPose(minecraft, this.pose) && chunksReceived(minecraft, this.layout.resetVolume())
				&& result.readinessSha256().equals(BlockStates.layoutSha256(minecraft.level, result.readinessVolume()));
			if (shown && !this.refreshed) {
				// Change marks for the arena can be dropped while the camera waits on the
				// distant staging platform; rebuild every arena section from the client's blocks.
				this.refreshed = true;
				markSectionsDirty(minecraft, this.layout.resetVolume());
				return this.stability.holds(false);
			}

			return this.stability.holds(shown && this.refreshed && weatherShown(minecraft, this.weather) && fogSettled && lightApplied(minecraft) && sectionsCompiled(minecraft));
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
		private int workTimeoutTicks = EnvironmentRuntime.this.timing.serverTimeoutTicks();
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
				if (tick - this.startTick <= this.workTimeoutTicks) {
					return false;
				}

				return fail(new EnvironmentException(EnvironmentException.Reason.TIMEOUT, this.operation.kind() + " work did not finish within " + this.workTimeoutTicks + " ticks"));
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
						EnvironmentRuntime.this.coordinator.complete(this.operation, tick);
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
