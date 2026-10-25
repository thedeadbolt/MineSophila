package io.github.thedeadbolt.honeyfactory.gametest;

import io.github.thedeadbolt.honeyfactory.client.runtime.TickStepper;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.FixturePlans;
import io.github.thedeadbolt.honeyfactory.world.ArenaExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * M11 task 1: with the stepper holding, the world and the player stay exactly still between
 * steps, and each step advances exactly one server tick and one player tick.
 */
public class M11TickTest implements FabricClientGameTest {
	private static final String WORLD_NAME = "honey-factory-m11-tick";
	/** Vanilla's first falling tick from rest: gravity 0.08, then the float air drag 0.98F. */
	private static final double FIRST_FALL_VELOCITY = -0.08 * 0.98F;
	private static final int TIMEOUT_TICKS = 200;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (GameTestSupport.skipped(this)) {
			return;
		}

		GameTestSupport.pinCaptureSettings(context);
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext world = GameTestSupport.datasetWorld(context, WORLD_NAME)) {
			GameTestSupport.claim(world, WORLD_NAME);
			GameTestSupport.await(context, runtime -> runtime.reset(FixturePlans.ARENA, FixturePlans.course(), FixturePlans.courseStart(FixturePlans.Lane.STEP).pose()));
			CameraPose start = FixturePlans.courseStart(FixturePlans.Lane.STEP).pose();
			CameraPose air = new CameraPose(start.x(), start.y() + 3.0, start.z(), start.yawDegrees(), start.pitchDegrees());
			GameTestSupport.await(context, runtime -> runtime.setViewpoint(air));

			context.runOnClient(minecraft -> TickStepper.get().hold(minecraft.getSingleplayerServer()));
			world.getServer().runOnServer(server -> ArenaExecutor.embody(server, server.getPlayerList().getPlayers().getFirst(), FixturePlans.ARENA));
			context.waitFor(minecraft -> minecraft.level.tickRateManager().isFrozen() && minecraft.gameMode.getPlayerMode() == GameType.ADVENTURE, TIMEOUT_TICKS);

			Vec3 held = position(context);
			context.waitTicks(40);
			if (!position(context).equals(held)) {
				failures.add("the held player moved from " + held + " to " + position(context) + " in 40 ticks");
			}

			Consumer<LocalPlayer> idle = player -> player.input = new FixedInput(false);
			TickStepper.Tick first = advance(context, idle);
			Vec3 afterFirst = position(context);
			TickStepper.Tick second = advance(context, idle);
			Vec3 afterSecond = position(context);
			if (second.serverTick() != first.serverTick() + 1 || second.clientTick() <= first.clientTick()) {
				failures.add("two steps advanced the server from " + first.serverTick() + " to " + second.serverTick());
			}

			if (afterFirst.y != held.y || Math.abs(afterSecond.y - afterFirst.y - FIRST_FALL_VELOCITY) > 1e-9) {
				failures.add("falling from rest moved " + (afterFirst.y - held.y) + " then " + (afterSecond.y - afterFirst.y) + ", expected 0 then " + FIRST_FALL_VELOCITY);
			}

			context.waitTicks(20);
			if (!position(context).equals(afterSecond)) {
				failures.add("the player moved between steps while falling");
			}

			for (int i = 0; i < 60 && !context.computeOnClient(minecraft -> minecraft.player.onGround()); i++) {
				advance(context, idle);
			}

			Vec3 landed = position(context);
			long previous = advance(context, idle).serverTick();
			for (int i = 0; i < 10; i++) {
				long tick = advance(context, player -> player.input = new FixedInput(true)).serverTick();
				if (tick != previous + 1) {
					failures.add("forward step " + i + " advanced the server from " + previous + " to " + tick);
				}

				previous = tick;
			}

			// The course start faces north, toward -Z.
			double walked = landed.z - position(context).z;
			if (walked < 0.5) {
				failures.add("ten forward steps moved only " + walked + " blocks north");
			}

			context.runOnClient(minecraft -> TickStepper.get().release(minecraft.getSingleplayerServer()));
			context.waitFor(minecraft -> !minecraft.level.tickRateManager().isFrozen(), TIMEOUT_TICKS);
		}

		if (!failures.isEmpty()) {
			throw new AssertionError("M11 tick failures:\n" + String.join("\n", failures));
		}
	}

	private static Vec3 position(ClientGameTestContext context) {
		return context.computeOnClient(minecraft -> minecraft.player.position());
	}

	private static TickStepper.Tick advance(ClientGameTestContext context, Consumer<LocalPlayer> beforeTick) {
		CompletableFuture<TickStepper.Tick> tick = context.computeOnClient(minecraft -> TickStepper.get().advance(beforeTick));
		context.waitFor(minecraft -> tick.isDone(), TIMEOUT_TICKS);
		return tick.join();
	}

	/** Holds forward, or nothing, on every poll. */
	private static final class FixedInput extends ClientInput {
		private final boolean forward;

		FixedInput(boolean forward) {
			this.forward = forward;
		}

		@Override
		public void tick() {
			this.keyPresses = this.forward ? new Input(true, false, false, false, false, false, false) : Input.EMPTY;
			this.moveVector = this.forward ? new Vec2(0.0F, 1.0F) : Vec2.ZERO;
		}
	}
}
