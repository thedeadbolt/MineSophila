package io.github.thedeadbolt.honeyfactory.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.capture.PerspectiveProjection;
import io.github.thedeadbolt.honeyfactory.client.runtime.EnvironmentRuntime;
import io.github.thedeadbolt.honeyfactory.control.ActionMacros;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import io.github.thedeadbolt.honeyfactory.environment.Action;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.environment.OperationCoordinator;
import io.github.thedeadbolt.honeyfactory.environment.OperationKind;
import io.github.thedeadbolt.honeyfactory.environment.OperationState;
import io.github.thedeadbolt.honeyfactory.generation.DatasetGenerator;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.scene.ViewpointPlan;
import io.github.thedeadbolt.honeyfactory.world.Baseline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;

/**
 * Roadmap M7 exit gate: every movement action with its requested and completed values and
 * ticks, input release confirmed by the client and the server, vanilla auto-jump over the
 * movement course's lanes with the user's option restored, limits, concurrent-request
 * rejection, timeouts, leaving the arena, ordered results, leaving the world mid-step, and
 * commands and the dataset runner driving the same coordinator.
 */
public class M7EnvironmentTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m7";
	private static final PerspectiveProjection PROJECTION = new PerspectiveProjection(64, 64);
	private static final int WALK_TICKS = 40;
	/** Floor top in the platform arena: feet on the floor stand at this height. */
	private static final double FLOOR = ArenaLayout.ORIGIN[1] + 1.0;
	/** The user's own auto-jump option during the test, opposite to the environment's default. */
	private static final boolean USER_AUTO_JUMP = false;
	private static final double EPSILON = 1e-3;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		Path output = GameTestSupport.repoRoot().resolve("packages/honey-factory/build/m7-environment");
		GameTestSupport.deleteRecursively(output);
		GameTestSupport.pinCaptureSettings(context);
		context.runOnClient(minecraft -> minecraft.options.autoJump().set(USER_AUTO_JUMP));
		List<String> failures = new ArrayList<>();
		JsonObject summary = new JsonObject();
		CompletableFuture<EnvironmentRuntime.StepResult> abandoned;
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			expect(failures, "step before a reset", EnvironmentException.Reason.INVALID_STATE, rejection(context, runtime -> runtime.step(new Action.Wait(1), PROJECTION)));
			expect(failures, "mine", EnvironmentException.Reason.UNSUPPORTED, rejection(context, runtime -> runtime.step(new Action.Mine(5), PROJECTION)));

			EnvironmentRuntime.SceneState course = resetCourse(context);
			String expected = FixturePlans.course().layoutSha256(FixturePlans.ARENA);
			if (!expected.equals(course.layoutSha256())) {
				failures.add("course layout " + course.layoutSha256() + " differs from its plan " + expected);
			}

			rotations(context, world, failures);
			summary.add("in_place_step_ticks", operationTicks(context, 8));
			summary.add("lanes", lanes(context, world, failures));
			waitAndStop(context, world, failures);
			concurrentRequests(context, failures);
			ordering(context, course, failures);
			leavingTheArena(context, world, failures);
			timeouts(context, world, failures);
			commands(context, failures);
			restoresThePlayer(context, world, failures);

			resetCourse(context);
			abandoned = context.computeOnClient(minecraft -> EnvironmentRuntime.get().step(new Action.Wait(200), PROJECTION));
			context.waitTicks(10);
			boolean applied = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
			if (applied != !USER_AUTO_JUMP) {
				failures.add("a running step did not apply the configured auto-jump");
			}
		}

		leftTheWorld(context, abandoned, failures);
		summary.add("dataset_runner", datasetRunner(context, output.resolve("datasets"), failures));
		try {
			Files.createDirectories(output);
			Files.writeString(output.resolve("summary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M7 failures:\n" + String.join("\n", failures));
		}
	}

	/** Turns and looks happen in place, follow the sign conventions, wrap yaw and clamp pitch. */
	private static void rotations(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		await(context, runtime -> runtime.setViewpoint(FixturePlans.courseStart(FixturePlans.Lane.STEP).pose()));
		record Case(Action action, double completed, float yaw, float pitch) {
		}

		for (Case c : List.of(
			new Case(new Action.Turn(90.0F), 90.0, -90.0F, 0.0F),
			new Case(new Action.Turn(-90.0F), -90.0, -180.0F, 0.0F),
			new Case(new Action.Turn(180.0F), 180.0, 0.0F, 0.0F),
			new Case(new Action.Turn(-180.0F), -180.0, -180.0F, 0.0F),
			new Case(new Action.Look(30.0F), 30.0, -180.0F, 30.0F),
			new Case(new Action.Look(80.0F), 60.0, -180.0F, 90.0F),
			new Case(new Action.Look(-180.0F), -180.0, -180.0F, -90.0F),
			new Case(new Action.Look(90.0F), 90.0, -180.0F, 0.0F)
		)) {
			EnvironmentRuntime.StepResult result = await(context, runtime -> runtime.step(c.action(), PROJECTION));
			CameraPose after = result.after();
			String what = c.action().type() + " " + c.action().requested();
			// Turns and looks ramp over the shared motor limits: one rate per player tick.
			Action ramped = c.action() instanceof Action.Look ? new Action.Look((float) c.completed()) : c.action();
			int ticks = ActionMacros.expand(ramped, MotorLimits.DEFAULT).size();
			if (Math.abs(result.completed() - c.completed()) > EPSILON || result.completedTicks() != ticks
				|| Math.abs(Action.wrapDegrees(after.yawDegrees() - c.yaw())) > EPSILON || Math.abs(after.pitchDegrees() - c.pitch()) > EPSILON) {
				failures.add(what + ": completed " + result.completed() + " in " + result.completedTicks() + " ticks, pose " + after);
			}

			if (horizontal(result.before(), after) > EPSILON || Math.abs(after.y() - FLOOR) > EPSILON) {
				failures.add(what + " moved the player from " + result.before() + " to " + after);
			}

			checkReleased(context, world, what, failures);
		}

		expect(failures, "turn beyond the limit", EnvironmentException.Reason.INVALID_REQUEST, rejection(context, runtime -> runtime.step(new Action.Turn(181.0F), PROJECTION)));
		expect(failures, "move of 0 ticks", EnvironmentException.Reason.INVALID_REQUEST, rejection(context, runtime -> runtime.step(new Action.MoveForward(0), PROJECTION)));
		if (state(context) != OperationState.READY) {
			failures.add("a rejected request changed the state to " + state(context));
		}
	}

	/**
	 * Walks every lane with auto-jump on and off. Only the one-block step separates them:
	 * auto-jump climbs it, walking alone is blocked. Slabs and the stair are walked up, the
	 * two-block wall and the fence block both.
	 */
	private static JsonArray lanes(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		JsonArray lanes = new JsonArray();
		double obstacleFace = ArenaLayout.ORIGIN[2] + FixturePlans.COURSE_OBSTACLE_Z + 1.0;
		for (FixturePlans.Lane lane : FixturePlans.Lane.values()) {
			for (boolean autoJump : List.of(true, false)) {
				EnvironmentRuntime.StepResult result = walk(context, lane, autoJump, WALK_TICKS);
				CameraPose after = result.after();
				String what = lane + " with auto-jump " + (autoJump ? "on" : "off");
				JsonObject entry = new JsonObject();
				entry.addProperty("lane", lane.name().toLowerCase(Locale.ROOT));
				entry.addProperty("auto_jump", autoJump);
				entry.addProperty("completed_ticks", result.completedTicks());
				entry.addProperty("auto_jumps", result.autoJumps());
				entry.addProperty("collided", result.collided());
				entry.addProperty("on_ground", result.onGround());
				entry.addProperty("forward_blocks", Math.round((result.before().z() - after.z()) * 1000) / 1000.0);
				entry.addProperty("rise_blocks", Math.round((after.y() - result.before().y()) * 1000) / 1000.0);
				lanes.add(entry);

				if (result.completed() != WALK_TICKS || result.completedTicks() != WALK_TICKS || result.autoJump() != autoJump || !result.onGround()) {
					failures.add(what + ": completed " + result.completed() + " / " + result.completedTicks() + " ticks, auto-jump " + result.autoJump() + ", on ground " + result.onGround());
				}

				double rise = after.y() - FLOOR;
				boolean climbed = after.z() < obstacleFace - 1.0;
				// The fence's collision box stands back from its block face; the wall's and the step's do not.
				boolean blocked = result.collided() && after.z() > obstacleFace - 0.5;
				boolean passed = switch (lane) {
					case STEP -> autoJump
						? climbed && Math.abs(rise - 1.0) < EPSILON && result.autoJumps() >= 1
						: blocked && Math.abs(rise) < EPSILON && result.autoJumps() == 0;
					case WALL, FENCE -> blocked && Math.abs(rise) < EPSILON && result.autoJumps() == 0;
					case SLAB -> climbed && Math.abs(rise - 0.5) < EPSILON && result.autoJumps() == 0;
					case STAIRS -> climbed && Math.abs(rise - 1.0) < EPSILON;
				};
				if (!passed) {
					failures.add(what + ": " + entry);
				}

				if (Math.abs(after.x() - result.before().x()) > EPSILON) {
					failures.add(what + " drifted sideways to " + after);
				}

				checkReleased(context, world, what, failures);
				GameType mode = world.getServer().computeOnServer(server -> player(server).gameMode());
				if (mode != GameType.ADVENTURE) {
					failures.add(what + ": the body is in " + mode + " mode");
				}
			}
		}

		context.runOnClient(minecraft -> EnvironmentRuntime.get().configure(EnvironmentRuntime.get().settings().withAutoJump(true)));
		return lanes;
	}

	/** Wait commands no movement for its ticks; stop releases inputs without advancing. */
	private static void waitAndStop(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		await(context, runtime -> runtime.setViewpoint(new ViewpointPlan(FixturePlans.Lane.WALL.centreX() + 0.5, 1.0, 30.5, 180.0F, 0.0F).pose()));
		EnvironmentRuntime.StepResult moved = await(context, runtime -> runtime.step(new Action.MoveForward(10), PROJECTION));
		EnvironmentRuntime.StepResult waited = await(context, runtime -> runtime.step(new Action.Wait(20), PROJECTION));
		if (waited.completedTicks() != 20 || waited.completed() != 20 || horizontal(waited.before(), waited.after()) > 0.2) {
			failures.add("WAIT 20: completed " + waited.completed() + " / " + waited.completedTicks() + " ticks, drifted " + horizontal(waited.before(), waited.after()) + " after a move of " + horizontal(moved.before(), moved.after()));
		}

		checkReleased(context, world, "WAIT", failures);
		EnvironmentRuntime.StepResult stopped = await(context, runtime -> runtime.step(new Action.Stop(), PROJECTION));
		EnvironmentRuntime.StepResult still = await(context, runtime -> runtime.step(new Action.Wait(10), PROJECTION));
		if (stopped.completedTicks() != 0 || stopped.completed() != 0 || horizontal(still.before(), still.after()) > EPSILON) {
			failures.add("STOP: completed " + stopped.completed() + " in " + stopped.completedTicks() + " ticks, then drifted " + horizontal(still.before(), still.after()));
		}

		checkReleased(context, world, "STOP", failures);
	}

	/** Every request, and a settings change, is rejected while a step runs, which still completes. */
	private static void concurrentRequests(ClientGameTestContext context, List<String> failures) {
		CompletableFuture<EnvironmentRuntime.StepResult> running = context.computeOnClient(minecraft -> EnvironmentRuntime.get().step(new Action.Wait(30), PROJECTION));
		expect(failures, "concurrent step", EnvironmentException.Reason.BUSY, rejection(context, runtime -> runtime.step(new Action.Turn(10.0F), PROJECTION)));
		expect(failures, "concurrent observe", EnvironmentException.Reason.BUSY, rejection(context, runtime -> runtime.observe(PROJECTION)));
		expect(failures, "concurrent viewpoint", EnvironmentException.Reason.BUSY, rejection(context, runtime -> runtime.setViewpoint(FixturePlans.courseStart(FixturePlans.Lane.STEP).pose())));
		expect(failures, "concurrent reset", EnvironmentException.Reason.BUSY, rejection(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.course(), FixturePlans.courseStart(FixturePlans.Lane.STEP).pose())));
		EnvironmentException.Reason settings = context.computeOnClient(minecraft -> {
			try {
				EnvironmentRuntime.get().configure(EnvironmentRuntime.get().settings().withAutoJump(false));
				return null;
			} catch (EnvironmentException e) {
				return e.reason();
			}
		});
		expect(failures, "settings during a step", EnvironmentException.Reason.BUSY, settings);
		context.waitFor(minecraft -> running.isDone(), 400);
		if (running.join().completedTicks() != 30) {
			failures.add("the running step completed " + running.join().completedTicks() + " ticks");
		}
	}

	/** Steps are numbered within their scene and tied to it, the capture and the server's ticks; history keeps the order. */
	private static void ordering(ClientGameTestContext context, EnvironmentRuntime.SceneState course, List<String> failures) {
		EnvironmentRuntime.SceneState scene = resetCourse(context);
		List<EnvironmentRuntime.StepResult> results = new ArrayList<>();
		for (Action action : List.of(new Action.Wait(2), new Action.Turn(15.0F), new Action.Wait(2))) {
			results.add(await(context, runtime -> runtime.step(action, PROJECTION)));
		}

		for (int i = 0; i < results.size(); i++) {
			EnvironmentRuntime.StepResult result = results.get(i);
			if (result.index() != i + 1 || !result.sceneOperationId().equals(scene.operationId()) || !result.observation().operationId().equals(result.operationId())
				|| result.endTick() < result.startTick() || (i > 0 && (result.serverTick() < results.get(i - 1).serverTick() || result.startTick() <= results.get(i - 1).endTick()))) {
				failures.add("step " + i + " is out of order: " + describe(result));
			}
		}

		if (scene.operationId().equals(course.operationId())) {
			failures.add("a new reset kept the old scene operation ID");
		}

		List<OperationCoordinator.Finished> history = context.computeOnClient(minecraft -> EnvironmentRuntime.get().history());
		List<String> lastIds = history.subList(history.size() - 4, history.size()).stream().map(finished -> finished.operation().id()).toList();
		List<String> expectedIds = List.of(scene.operationId(), results.get(0).operationId(), results.get(1).operationId(), results.get(2).operationId());
		if (!lastIds.equals(expectedIds)) {
			failures.add("history ends with " + lastIds + ", not " + expectedIds);
		}

		for (int i = 1; i < history.size(); i++) {
			if (history.get(i).operation().id().compareTo(history.get(i - 1).operation().id()) <= 0 || history.get(i).endTick() < history.get(i - 1).endTick()) {
				failures.add("history is out of order at " + history.get(i).operation().id());
				break;
			}
		}
	}

	/** Walking off the platform fails the step once the player leaves the reset volume, and restores the inputs. */
	private static void leavingTheArena(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		await(context, runtime -> runtime.setViewpoint(new ViewpointPlan(0.5, 1.0, 26.5, 90.0F, 0.0F).pose()));
		expect(failures, "walking off the platform", EnvironmentException.Reason.OUT_OF_ARENA, rejection(context, runtime -> runtime.step(new Action.MoveForward(60), PROJECTION)));
		if (state(context) != OperationState.FAILED) {
			failures.add("leaving the arena left the state " + state(context));
		}

		// A failed step releases its inputs on the client at once; the server hears it on the next tick.
		context.waitTicks(2);
		checkReleased(context, world, "leaving the arena", failures);
		resetCourse(context);
	}

	/** A readiness timeout fails the operation with TIMEOUT, for a reset and for a step. */
	private static void timeouts(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		EnvironmentRuntime.Timing defaults = context.computeOnClient(minecraft -> EnvironmentRuntime.get().timing());
		context.runOnClient(minecraft -> EnvironmentRuntime.get().configureTiming(new EnvironmentRuntime.Timing(defaults.serverTimeoutTicks(), 0, 0, defaults.viewpointSettleTicks(), defaults.viewpointTimeoutTicks())));
		expect(failures, "reset with no time to settle", EnvironmentException.Reason.TIMEOUT, rejection(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.course(), FixturePlans.courseStart(FixturePlans.Lane.STEP).pose())));
		if (state(context) != OperationState.FAILED) {
			failures.add("a timed-out reset left the state " + state(context));
		}

		expect(failures, "step after a timed-out reset", EnvironmentException.Reason.INVALID_STATE, rejection(context, runtime -> runtime.step(new Action.Wait(1), PROJECTION)));
		context.runOnClient(minecraft -> EnvironmentRuntime.get().configureTiming(defaults));
		resetCourse(context);

		context.runOnClient(minecraft -> EnvironmentRuntime.get().configureTiming(new EnvironmentRuntime.Timing(defaults.serverTimeoutTicks(), defaults.resetSettleTicks(), defaults.resetTimeoutTicks(), 0, 0)));
		expect(failures, "step with no time to settle", EnvironmentException.Reason.TIMEOUT, rejection(context, runtime -> runtime.step(new Action.Wait(1), PROJECTION)));
		context.waitTicks(2);
		checkReleased(context, world, "a timed-out step", failures);
		context.runOnClient(minecraft -> EnvironmentRuntime.get().configureTiming(defaults));
		resetCourse(context);
	}

	/** Commands start operations on the same coordinator; a rejected mine command starts none. */
	private static void commands(ClientGameTestContext context, List<String> failures) {
		long before = context.computeOnClient(minecraft -> EnvironmentRuntime.get().status().operationsStarted());
		context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("honeyfactory_env step mine 5"));
		context.waitTicks(5);
		context.runOnClient(minecraft -> minecraft.player.connection.sendCommand("honeyfactory_env step wait 3"));
		context.waitFor(minecraft -> EnvironmentRuntime.get().status().operationsStarted() == before + 1 && EnvironmentRuntime.get().status().current() == null, 400);
		OperationCoordinator.Finished last = context.computeOnClient(minecraft -> EnvironmentRuntime.get().history().getLast());
		if (last.operation().kind() != OperationKind.STEP || !last.succeeded()) {
			failures.add("the step command finished as " + last);
		}
	}

	/** Releasing the world baseline returns the player to their own game mode after being embodied. */
	private static void restoresThePlayer(ClientGameTestContext context, TestSingleplayerContext world, List<String> failures) {
		await(context, runtime -> runtime.step(new Action.Wait(1), PROJECTION));
		boolean restored = world.getServer().computeOnServer(Baseline::restore);
		GameType mode = world.getServer().computeOnServer(server -> player(server).gameMode());
		boolean invulnerable = world.getServer().computeOnServer(server -> player(server).getAbilities().invulnerable);
		if (!restored || mode == GameType.ADVENTURE || mode == GameType.SPECTATOR || (mode != GameType.CREATIVE && invulnerable)) {
			failures.add("release after steps left restored=" + restored + ", mode " + mode + ", invulnerable " + invulnerable);
		}
	}

	/** Leaving the world mid-step fails the step, forgets the scene and restores the user's option. */
	private static void leftTheWorld(ClientGameTestContext context, CompletableFuture<EnvironmentRuntime.StepResult> abandoned, List<String> failures) {
		EnvironmentException.Reason reason = null;
		try {
			abandoned.join();
		} catch (CompletionException e) {
			reason = e.getCause() instanceof EnvironmentException environment ? environment.reason() : null;
		}

		expect(failures, "step running when the world closed", EnvironmentException.Reason.NO_LOCAL_RUNTIME, reason);
		boolean autoJump = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
		OperationState state = state(context);
		if (autoJump != USER_AUTO_JUMP || state != OperationState.IDLE) {
			failures.add("after leaving the world mid-step: auto-jump " + autoJump + ", state " + state);
		}
	}

	/** A small level-1 dataset: the runner's resets and captures appear in the same coordinator's history. */
	private static JsonObject datasetRunner(ClientGameTestContext context, Path root, List<String> failures) {
		String name = "honey-factory-m7-runner";
		String config = """
			{
			  "dataset": {"name": "m7-runner", "seed": 2027, "difficulty": 1},
			  "viewpoints": {"count": 2},
			  "capture": {"width": 320, "height": 240},
			  "quotas": {"accepted_scenes": 2},
			  "target": {"present_fraction": 0.5},
			  "output": {"root": "%s", "free_space_reserve_bytes": 1073741824}
			}
			""".formatted(root.toString().replace("\\", "\\\\"));
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, name)) {
			GameTestSupport.claim(world, name);
			DatasetGenerator.Result result = GameTestSupport.generate(context, name, config, progress -> false, 6000);
			List<OperationCoordinator.Finished> history = context.computeOnClient(minecraft -> EnvironmentRuntime.get().history());
			long resets = history.stream().filter(finished -> finished.operation().kind() == OperationKind.RESET && finished.succeeded()).count();
			long observations = history.stream().filter(finished -> finished.operation().kind() == OperationKind.OBSERVE && finished.succeeded()).count();
			if (result.failure() != null || resets < 2 || observations < 2) {
				failures.add(name + ": failure " + result.failure() + ", " + resets + " resets and " + observations + " observations in the coordinator's history");
			}

			JsonObject summary = new JsonObject();
			summary.addProperty("lifecycle", result.manifest().lifecycle().jsonName());
			summary.addProperty("coordinator_resets", resets);
			summary.addProperty("coordinator_observations", observations);
			return summary;
		}
	}

	/** Client ticks from start to end of the last {@code count} operations. */
	private static JsonArray operationTicks(ClientGameTestContext context, int count) {
		List<OperationCoordinator.Finished> history = context.computeOnClient(minecraft -> EnvironmentRuntime.get().history());
		JsonArray ticks = new JsonArray();
		for (OperationCoordinator.Finished finished : history.subList(history.size() - count, history.size())) {
			ticks.add(finished.endTick() - finished.operation().startTick());
		}

		return ticks;
	}

	private static EnvironmentRuntime.SceneState resetCourse(ClientGameTestContext context) {
		return await(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.course(), FixturePlans.courseStart(FixturePlans.Lane.STEP).pose()));
	}

	private static EnvironmentRuntime.StepResult walk(ClientGameTestContext context, FixturePlans.Lane lane, boolean autoJump, int ticks) {
		context.runOnClient(minecraft -> EnvironmentRuntime.get().configure(EnvironmentRuntime.get().settings().withAutoJump(autoJump)));
		await(context, runtime -> runtime.setViewpoint(FixturePlans.courseStart(lane).pose()));
		return await(context, runtime -> runtime.step(new Action.MoveForward(ticks), PROJECTION));
	}

	/** After a step, however it ended: the keyboard and the user's option are back, and the server last saw no input. */
	private static void checkReleased(ClientGameTestContext context, TestSingleplayerContext world, String what, List<String> failures) {
		boolean keyboard = context.computeOnClient(minecraft -> minecraft.player.input instanceof KeyboardInput);
		boolean autoJump = context.computeOnClient(minecraft -> minecraft.options.autoJump().get());
		Input serverInput = world.getServer().computeOnServer(server -> player(server).getLastClientInput());
		if (!keyboard || autoJump != USER_AUTO_JUMP || !serverInput.equals(Input.EMPTY)) {
			failures.add(what + ": keyboard input " + keyboard + ", user auto-jump " + autoJump + ", server input " + serverInput);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static double horizontal(CameraPose from, CameraPose to) {
		return Math.hypot(to.x() - from.x(), to.z() - from.z());
	}

	private static String describe(EnvironmentRuntime.StepResult result) {
		return result.operationId() + " #" + result.index() + " scene " + result.sceneOperationId() + " ticks " + result.startTick() + "-" + result.endTick() + " server " + result.serverTick();
	}

	private static OperationState state(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> EnvironmentRuntime.get().status().state());
	}

	private static <T> T await(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		return GameTestSupport.await(context, request);
	}

	private static <T> EnvironmentException.Reason rejection(ClientGameTestContext context, Function<EnvironmentRuntime, CompletableFuture<T>> request) {
		return GameTestSupport.rejection(context, request);
	}

	private static void expect(List<String> failures, String what, EnvironmentException.Reason expected, EnvironmentException.Reason actual) {
		if (expected != actual) {
			failures.add(what + ": expected " + expected + " but got " + actual);
		}
	}
}
