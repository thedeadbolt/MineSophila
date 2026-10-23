package io.github.thedeadbolt.honeyfactory.client.runtime;

import io.github.thedeadbolt.honeyfactory.client.m0.M0Capture;
import io.github.thedeadbolt.honeyfactory.config.ConfigResolver;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.Operation;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.environment.OperationKind;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.environment.ReadinessGate;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import io.github.thedeadbolt.honeyfactory.world.BlockStates;
import java.nio.file.Path;
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
import net.minecraft.server.level.ServerPlayer;
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
		public static final Timing DEFAULT = new Timing(600, 5, 600, 3, 200);
	}

	public record SceneState(String operationId, String layoutSha256, long serverTick, long settledTicks, CameraPose pose) {
	}

	public record ViewpointState(String operationId, CameraPose pose, long settledTicks) {
	}

	public record Observation(String operationId, M0Capture.Result capture) {
		public Path directory() {
			return this.capture.directory();
		}
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

	private EnvironmentRuntime(Timing timing) {
		this.timing = timing;
	}

	public static EnvironmentRuntime get() {
		return INSTANCE;
	}

	/** The absolute camera pose of an arena-relative viewpoint plan. */
	public static CameraPose pose(ViewpointPlan viewpoint) {
		double[] feet = viewpoint.feet();
		return new CameraPose(feet[0], feet[1], feet[2], (float) viewpoint.yawDegrees(), (float) viewpoint.pitchDegrees());
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> INSTANCE.tick());
		LevelRenderEvents.END_MAIN.register(context -> INSTANCE.frames++);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> INSTANCE.disconnect());
	}

	/** Leaving a world fails any running operation and forgets the scene; the next world starts IDLE. */
	private void disconnect() {
		Pending<?, ?> running = this.pending;
		this.pending = null;
		if (running != null) {
			running.fail(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "disconnected from the world"));
		}

		this.coordinator = new OperationCoordinator();
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
			String expected = plan.layoutSha256(layout);
			this.arena = layout;
			this.scene = null;
			this.layoutSha256 = null;
			CompletableFuture<ArenaExecutor.ResetResult> work = server.submit(() -> {
				ArenaExecutor.ResetResult result = ArenaExecutor.reset(server, layout, plan);
				ArenaExecutor.placeViewpoint(server, player(server), layout, firstViewpoint, ConfigResolver.EYE_HEIGHT_BLOCKS);
				return result;
			});
			RenderStability stability = new RenderStability();
			return new Pending<>(operation, work, this.timing.resetSettleTicks(), this.timing.resetTimeoutTicks(), result -> {
				Minecraft minecraft = Minecraft.getInstance();
				return stability.holds(inArena(minecraft) && atPose(minecraft, firstViewpoint) && sectionsCompiled(minecraft)
					&& expected.equals(BlockStates.layoutSha256(minecraft.level, layout.resetVolume())));
			}, (result, settled) -> {
				this.scene = plan;
				this.layoutSha256 = result.layoutSha256();
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
	 * Captures RGB and target masks from the current viewpoint at {@code size} square pixels.
	 * Delegates to the M0 capture fixture until the M3 capture service replaces it.
	 */
	public CompletableFuture<Observation> observe(String label, int size) {
		return start(OperationKind.OBSERVE, (server, operation) -> {
			int[] target = this.scene.targetPosition(this.arena);
			// A target-absent scene renders air, which produces empty masks.
			BlockPos targetPos = target == null
				? new BlockPos(this.arena.core().minX(), this.arena.core().maxY(), this.arena.core().minZ())
				: new BlockPos(target[0], target[1], target[2]);
			CompletableFuture<M0Capture.Result> work = M0Capture.submit(new M0Capture.Request(label, size, size, 1, targetPos));
			return new Pending<>(operation, work, 0, 0, capture -> true, (capture, settled) -> {
				if (capture.failure() != null) {
					throw new EnvironmentException(EnvironmentException.Reason.CAPTURE_FAILED, capture.failure());
				}

				return new Observation(operation.id(), capture);
			});
		});
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
		if (this.pending != null && this.pending.poll(this.tick)) {
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
