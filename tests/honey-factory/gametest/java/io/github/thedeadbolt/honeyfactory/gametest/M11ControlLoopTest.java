package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.control.ControlStep;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.KeyboardInput;

/**
 * M11 task 4: the lock-step control loop in the runtime. Turning ramps by the motor limits,
 * walking builds up through Minecraft's physics, teacher steps run the teacher's command
 * while recording both, contact and the last command reach the senses, and leaving the
 * arena or stepping before a reset fails cleanly.
 */
public class M11ControlLoopTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m11-control";
	private static final PerspectiveProjection PROJECTION = new PerspectiveProjection(64, 48);
	private static final long SEED = 7L;
	private static final boolean USER_AUTO_JUMP = false;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		context.runOnClient(minecraft -> minecraft.options.autoJump().set(USER_AUTO_JUMP));
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			expect(failures, "step before a reset", EnvironmentException.Reason.NOT_RESET,
				GameTestSupport.rejection(context, runtime -> runtime.controlStep(MotorCommand.IDLE, null, ControlStep.Executed.STUDENT)));

			EnvironmentRuntime.ControlResult first = reset(context, FixturePlans.Lane.STEP);
			if (first.step().index() != 0 || first.capture().images().width() != 64 || first.capture().images().height() != 48) {
				failures.add("reset gave step " + first.step().index() + " with a " + first.capture().images().width() + "x" + first.capture().images().height() + " frame");
			}

			turning(context, failures);
			walking(context, failures);
			teacher(context, failures);
			bumping(context, failures);
			invalidCommand(context, failures);
			leavingTheArena(context, failures);
			mouseHeld(context, failures);
			paused(context, failures);
			staleEnd(context, failures);
			failedReset(context, failures);

			reset(context, FixturePlans.Lane.STEP);
			context.runOnClient(minecraft -> EnvironmentRuntime.get().controlEnd());
			context.waitFor(minecraft -> !minecraft.level.tickRateManager().isFrozen(), 200);
			checkRestored(context, failures, "controlEnd");
			// Leave the world in the middle of a session: the user's option must come back.
			reset(context, FixturePlans.Lane.STEP);
		}

		boolean autoJump = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
		if (autoJump != USER_AUTO_JUMP) {
			failures.add("leaving the world mid-session left auto-jump " + autoJump);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M11 control loop failures:\n" + String.join("\n", failures));
		}
	}

	private static EnvironmentRuntime.ControlResult reset(ClientGameTestContext context, FixturePlans.Lane lane) {
		return reset(context, FixturePlans.courseStart(lane).pose());
	}

	private static EnvironmentRuntime.ControlResult reset(ClientGameTestContext context, CameraPose start) {
		return GameTestSupport.await(context, runtime -> runtime.controlReset(FixturePlans.ARENA, FixturePlans.course(), start, PROJECTION, SEED));
	}

	private static EnvironmentRuntime.ControlResult step(ClientGameTestContext context, MotorCommand command) {
		return GameTestSupport.await(context, runtime -> runtime.controlStep(command, null, ControlStep.Executed.STUDENT));
	}

	/** Turning ramps by 1.8 degrees per tick to 9, the yaw follows exactly, and the body stays put. */
	private static void turning(ClientGameTestContext context, List<String> failures) {
		EnvironmentRuntime.ControlResult previous = reset(context, FixturePlans.Lane.STEP);
		double[] rates = {1.8, 3.6, 5.4, 7.2, 9.0, 9.0, 9.0};
		MotorCommand turn = new MotorCommand(0.0, 0.0, 9.0, 0.0, false, 0);
		for (int i = 0; i < 20; i++) {
			EnvironmentRuntime.ControlResult result = step(context, turn);
			double expected = rates[Math.min(i, rates.length - 1)];
			double turned = wrap(result.step().truth().pose().yawDegrees() - previous.step().truth().pose().yawDegrees());
			if (Math.abs(result.step().achieved().yawRate() - expected) > 1e-6 || Math.abs(turned - expected) > 1e-3) {
				failures.add("turn step " + i + ": rate " + result.step().achieved().yawRate() + ", turned " + turned + ", expected " + expected);
			}

			if (i > 0 && !result.step().senses().lastCommand().equals(turn)) {
				failures.add("turn step " + i + " sensed last command " + result.step().senses().lastCommand());
			}

			if (horizontal(previous.step().truth().pose(), result.step().truth().pose()) > 1e-6) {
				failures.add("turning in place moved the body at step " + i);
			}

			previous = result;
		}
	}

	/** Walking forward builds up speed over the first ticks and moves the body along its facing, north here. */
	private static void walking(ClientGameTestContext context, List<String> failures) {
		EnvironmentRuntime.ControlResult start = reset(context, FixturePlans.Lane.STEP);
		MotorCommand forward = new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0);
		double[] speeds = new double[20];
		EnvironmentRuntime.ControlResult last = start;
		for (int i = 0; i < speeds.length; i++) {
			last = step(context, forward);
			speeds[i] = last.step().senses().forwardSpeed();
		}

		if (!(speeds[0] > 0.0 && speeds[0] < speeds[2] && speeds[2] < speeds[5])) {
			failures.add("forward speed did not build up: " + speeds[0] + ", " + speeds[2] + ", " + speeds[5]);
		}

		double north = start.step().truth().pose().z() - last.step().truth().pose().z();
		if (north < 1.0) {
			failures.add("20 forward steps moved only " + north + " blocks north");
		}
	}

	/** A teacher step runs the teacher's command and records both. */
	private static void teacher(ClientGameTestContext context, List<String> failures) {
		reset(context, FixturePlans.Lane.STEP);
		MotorCommand student = new MotorCommand(0.0, 0.0, -9.0, 0.0, false, 0);
		MotorCommand teacher = new MotorCommand(0.0, 0.0, 9.0, 0.0, false, 0);
		ControlStep step = GameTestSupport.await(context, runtime -> runtime.controlStep(student, teacher, ControlStep.Executed.TEACHER)).step();
		if (Math.abs(step.achieved().yawRate() - 1.8) > 1e-6 || !student.equals(step.student()) || !teacher.equals(step.teacher()) || !teacher.equals(step.executedCommand())) {
			failures.add("teacher step: rate " + step.achieved().yawRate() + ", commands " + step.commandsJson());
		}
	}

	/** Walking into the two-block wall sets bumped. */
	private static void bumping(ClientGameTestContext context, List<String> failures) {
		reset(context, FixturePlans.Lane.WALL);
		MotorCommand forward = new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0);
		for (int i = 0; i < 80; i++) {
			if (step(context, forward).step().senses().bumped()) {
				return;
			}
		}

		failures.add("80 forward steps toward the wall never sensed a bump");
	}

	/** A command outside the limits is refused without advancing. */
	private static void invalidCommand(ClientGameTestContext context, List<String> failures) {
		int before = reset(context, FixturePlans.Lane.STEP).step().index();
		expect(failures, "forward 1.5", EnvironmentException.Reason.INVALID_REQUEST,
			GameTestSupport.rejection(context, runtime -> runtime.controlStep(new MotorCommand(1.5, 0.0, 0.0, 0.0, false, 0), null, ControlStep.Executed.STUDENT)));
		expect(failures, "a teacher step without a teacher command", EnvironmentException.Reason.INVALID_REQUEST,
			GameTestSupport.rejection(context, runtime -> runtime.controlStep(MotorCommand.IDLE, null, ControlStep.Executed.TEACHER)));
		int after = context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastControlStep().step().index());
		if (after != before) {
			failures.add("refused commands advanced the loop from step " + before + " to " + after);
		}
	}

	/** Walking off the platform fails the step with OUT_OF_ARENA. */
	private static void leavingTheArena(ClientGameTestContext context, List<String> failures) {
		reset(context, new ViewpointPlan(0.5, 1.0, 26.5, 90.0F, 0.0F).pose());
		MotorCommand forward = new MotorCommand(1.0, 0.0, 0.0, 0.0, false, 0);
		for (int i = 0; i < 200; i++) {
			EnvironmentException.Reason reason = GameTestSupport.rejection(context, runtime -> runtime.controlStep(forward, null, ControlStep.Executed.STUDENT));
			if (reason == EnvironmentException.Reason.OUT_OF_ARENA) {
				return;
			}

			if (reason != null) {
				failures.add("walking off the platform failed with " + reason + " at step " + i);
				return;
			}
		}

		failures.add("200 forward steps off the platform never left the arena");
	}

	private static void checkRestored(ClientGameTestContext context, List<String> failures, String what) {
		boolean keyboard = context.computeOnClient(minecraft -> minecraft.player.input instanceof KeyboardInput);
		boolean autoJump = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
		if (!keyboard || autoJump != USER_AUTO_JUMP) {
			failures.add(what + " left keyboard input " + keyboard + " and auto-jump " + autoJump);
		}
	}

	/** Mouse turns, which arrive every frame through LocalPlayer.turn, do not move a held body. */
	private static void mouseHeld(ClientGameTestContext context, List<String> failures) {
		CameraPose before = reset(context, FixturePlans.Lane.STEP).step().truth().pose();
		context.runOnClient(minecraft -> minecraft.player.turn(200.0, 80.0));
		context.waitTicks(2);
		float yaw = context.computeOnClient(minecraft -> minecraft.player.getYRot());
		float pitch = context.computeOnClient(minecraft -> minecraft.player.getXRot());
		if (yaw != before.yawDegrees() || pitch != before.pitchDegrees()) {
			failures.add("a mouse turn moved the held body from " + before.yawDegrees() + "/" + before.pitchDegrees() + " to " + yaw + "/" + pitch);
		}
	}

	/** A paused game refuses steps at once instead of timing out and applying the tick later. */
	private static void paused(ClientGameTestContext context, List<String> failures) {
		reset(context, FixturePlans.Lane.STEP);
		context.runOnClient(minecraft -> minecraft.pauseGame(false));
		context.waitFor(minecraft -> minecraft.isPaused(), 200);
		EnvironmentException.Reason reason = GameTestSupport.rejection(context, runtime -> runtime.controlStep(MotorCommand.IDLE, null, ControlStep.Executed.STUDENT));
		context.runOnClient(minecraft -> minecraft.gui.setScreen(null));
		context.waitFor(minecraft -> !minecraft.isPaused(), 200);
		expect(failures, "a step while paused", EnvironmentException.Reason.INVALID_STATE, reason);
	}

	/** Ending a session that a newer reset has replaced leaves the newer one running. */
	private static void staleEnd(ClientGameTestContext context, List<String> failures) {
		long first = reset(context, FixturePlans.Lane.STEP).session();
		long second = reset(context, FixturePlans.Lane.STEP).session();
		context.runOnClient(minecraft -> EnvironmentRuntime.get().controlEnd(first));
		boolean running = context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastControlStep() != null);
		if (first == second || !running) {
			failures.add("ending stale session " + first + " also ended session " + second);
		}
	}

	/**
	 * A reset that fails in the middle of a session, here because the start lies outside the
	 * arena, ends the session and gives the input back instead of leaving it borrowed.
	 */
	private static void failedReset(ClientGameTestContext context, List<String> failures) {
		reset(context, FixturePlans.Lane.STEP);
		EnvironmentException.Reason reason = GameTestSupport.rejection(context, runtime -> runtime.controlReset(FixturePlans.ARENA, FixturePlans.course(),
			new CameraPose(0.0, 400.0, 0.0, 0.0F, 0.0F), PROJECTION, SEED));
		expect(failures, "a control reset outside the arena", EnvironmentException.Reason.INVALID_REQUEST, reason);
		context.waitFor(minecraft -> !minecraft.level.tickRateManager().isFrozen(), 200);
		checkRestored(context, failures, "a failed control reset");
		if (context.computeOnClient(minecraft -> EnvironmentRuntime.get().lastControlStep()) != null) {
			failures.add("a failed control reset left its session running");
		}
	}

	private static void expect(List<String> failures, String what, EnvironmentException.Reason expected, EnvironmentException.Reason actual) {
		if (actual != expected) {
			failures.add(what + ": expected " + expected + ", got " + actual);
		}
	}

	private static double horizontal(CameraPose a, CameraPose b) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z());
	}

	private static double wrap(double degrees) {
		double wrapped = (degrees + 180.0) % 360.0;
		return (wrapped < 0 ? wrapped + 360.0 : wrapped) - 180.0;
	}
}
