package io.github.thedeadbolt.honeyfactory.generation;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.clip.ClipEnvironment;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.contract.DatasetManifest;
import io.github.thedeadbolt.honeyfactory.contract.MaskStatistics;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.control.MotorState;
import io.github.thedeadbolt.honeyfactory.control.TrueState;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.PreparedPatch;
import io.github.thedeadbolt.honeyfactory.scene.ScenePlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A scripted body for clip generation: no gravity, a flat floor, M11's motor rules for
 * turning and a fixed walking speed. Frames are ray cast as in {@link ScriptedEnvironment}.
 * Requests queue and run when the test {@link #pump pumps}, as the real runtime completes
 * them on later client ticks.
 */
public final class ScriptedClipEnvironment implements ClipEnvironment {
	/** Blocks per tick at full forward, roughly Minecraft's walking speed. */
	static final double WALK = 0.2158;

	private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
	private int resets;
	private long serverTick = 1000;
	private int operations;
	private @Nullable ArenaLayout arena;
	private @Nullable CaptureProjection projection;
	private int framesPerTick;
	private int @Nullable [] target;
	private List<int[]> occluders = List.of();
	private String layout = "";
	private long seed;
	private int tick;
	private CameraPose pose = new CameraPose(0, 0, 0, 0f, 0f);
	private MotorState motor = MotorState.ZERO;
	private MotorCommand last = MotorCommand.IDLE;
	/** Steps (reset number counted from 1, tick) that fail as if the body left the arena. */
	BiPredicate<Integer, Integer> leaveArena = (reset, tick) -> false;
	/** Executed commands, in order, across every clip. */
	final List<MotorCommand> executed = new ArrayList<>();
	boolean ended;

	/** Runs queued requests, including any they queue, until none remain. */
	public void pump() {
		Runnable next;
		while ((next = this.queue.pollFirst()) != null) {
			next.run();
		}
	}

	/**
	 * Runs queued requests until {@code done} completes; work finished on other threads
	 * (validation) queues its hand-back here, as the game's executor would receive it.
	 */
	public void pumpUntil(CompletableFuture<?> done) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (!done.isDone()) {
			pump();
			if (System.nanoTime() > deadline) {
				throw new AssertionError("generation did not finish within 60 s");
			}

			try {
				Runnable next = this.queue.pollFirst(10, TimeUnit.MILLISECONDS);
				if (next != null) {
					next.run();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}

	int resets() {
		return this.resets;
	}

	@Override
	public CompletableFuture<Reset> reset(ArenaLayout arena, ScenePlan plan, CameraPose start, CaptureProjection projection, long seed, int framesPerTick) {
		return later(() -> {
			this.resets++;
			this.arena = arena;
			this.projection = projection;
			this.framesPerTick = framesPerTick;
			this.target = plan.targetPosition(arena);
			this.occluders = plan.patch() == null ? ScriptedEnvironment.occluders(arena, plan) : List.of();
			this.layout = plan.layoutSha256(arena);
			this.seed = seed;
			this.tick = 0;
			this.pose = start;
			this.motor = MotorState.ZERO;
			this.last = MotorCommand.IDLE;
			CapturedImages images = render(start);
			ControlStep step = record(0, null, null, MotorState.ZERO, new double[3], images);
			JsonObject rules = new JsonObject();
			rules.addProperty("advance_time", false);
			return new Reset(new Step(step, List.of(new Frame(start, 1.0, images))), new GenerationEnvironment.SceneReady("op-" + ++this.operations, this.layout, 5, rules));
		});
	}

	@Override
	public CompletableFuture<Step> step(MotorCommand executed, MotorCommand teacher) {
		return later(() -> {
			MotorLimits.DEFAULT.check(executed);
			MotorLimits.DEFAULT.check(teacher);
			this.tick++;
			if (this.leaveArena.test(this.resets, this.tick)) {
				throw new EnvironmentException(EnvironmentException.Reason.OUT_OF_ARENA, "scripted: left the arena at tick " + this.tick);
			}

			MotorState.Achieved achieved = this.motor.apply(executed, MotorLimits.DEFAULT, this.pose.yawDegrees(), this.pose.pitchDegrees());
			double yaw = Math.toRadians(achieved.yaw());
			double[] velocity = {-Math.sin(yaw) * executed.forward() * WALK, 0.0, Math.cos(yaw) * executed.forward() * WALK};
			CameraPose from = this.pose;
			CameraPose to = new CameraPose(from.x() + velocity[0], from.y(), from.z() + velocity[2], achieved.yaw(), achieved.pitch());
			BlockBox volume = this.arena.resetVolume();
			if (!volume.contains((int) Math.floor(to.x()), (int) Math.floor(to.y()), (int) Math.floor(to.z()))) {
				throw new EnvironmentException(EnvironmentException.Reason.OUT_OF_ARENA, "scripted: walked out of the arena to " + to);
			}

			this.motor = achieved.state();
			this.pose = to;
			this.serverTick++;
			this.executed.add(executed);
			List<Frame> frames = new ArrayList<>();
			for (int k = 1; k <= this.framesPerTick; k++) {
				double t = k / (double) this.framesPerTick;
				float yawStep = (float) (from.yawDegrees() + wrap(to.yawDegrees() - from.yawDegrees()) * t);
				CameraPose at = k == this.framesPerTick ? to : new CameraPose(from.x() + velocity[0] * t, from.y(), from.z() + velocity[2] * t, yawStep,
					(float) (from.pitchDegrees() + (to.pitchDegrees() - from.pitchDegrees()) * t));
				frames.add(new Frame(at, t, render(at)));
			}

			ControlStep step = record(this.tick, executed, teacher, achieved.state(), velocity, frames.getLast().images());
			this.last = executed;
			return new Step(step, List.copyOf(frames));
		});
	}

	private ControlStep record(int index, @Nullable MotorCommand student, @Nullable MotorCommand teacher, MotorState achieved, double[] velocity, CapturedImages images) {
		BlockBox box = this.target == null ? null : BlockBox.cell(this.target);
		CameraFrame camera = CameraFrame.of(this.pose, ScriptedEnvironment.EYE_HEIGHT);
		SampleRecord.Target target = SampleRecord.Target.of(box != null, box != null && this.projection.intersects(camera, box),
			MaskStatistics.of(images.visibleMask(), images.width(), images.height()), images.unoccludedPixels(), box == null ? null : camera.distanceTo(box.center()));
		BodySenses senses = BodySenses.of(this.pose, velocity, achieved, true, false, false, this.last);
		TrueState truth = new TrueState(this.pose, velocity, this.layout, this.seed, this.serverTick, target, box != null && images.unoccludedTouchesEdge());
		return new ControlStep(index, student, teacher, ControlStep.Executed.STUDENT, achieved, senses, truth);
	}

	private CapturedImages render(CameraPose pose) {
		return ScriptedEnvironment.render(this.projection, this.target, this.occluders, pose);
	}

	private static double wrap(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}

	@Override
	public void end() {
		this.ended = true;
	}

	@Override
	public CompletableFuture<PreparedPatch> preparePatch(GenerationEnvironment.PatchRequest request) {
		ScriptedEnvironment patches = new ScriptedEnvironment();
		CompletableFuture<PreparedPatch> patch = patches.preparePatch(request);
		patches.pump();
		return patch;
	}

	@Override
	public DatasetManifest.Software software() {
		return ScriptedEnvironment.scriptedSoftware();
	}

	@Override
	public DatasetManifest.RenderEnvironment renderEnvironment() {
		return ScriptedEnvironment.scriptedRenderEnvironment();
	}

	@Override
	public Executor executor() {
		return this.queue::addLast;
	}

	private <T> CompletableFuture<T> later(Supplier<T> work) {
		CompletableFuture<T> future = new CompletableFuture<>();
		this.queue.addLast(() -> {
			try {
				future.complete(work.get());
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
