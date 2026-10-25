package io.github.thedeadbolt.honeyfactory.client.runtime;

import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Holds the world and the player still, and lets them advance one tick at a time.
 *
 * <p>Vanilla's tick freeze stops the server's world but not players, so while holding, the
 * client level also skips the local player's tick (see {@code ClientLevelMixin}). A step
 * then runs, in order:
 * <ol>
 *   <li>one frozen server tick (the single-tick step of {@code /tick step});</li>
 *   <li>one tick of the local player, with the caller's input applied just before it;</li>
 *   <li>the next rendered frame, which shows that tick.</li>
 * </ol>
 * After the player tick the body's interpolation is settled (old position and rotation set
 * to the new ones), so frames rendered between steps show one still view. Otherwise the
 * camera would swing between the last two positions with the frame's partial tick, and the
 * renderer's section graph would never settle near a section boundary.
 * Everything runs on the client thread except the server's own work.
 */
public final class TickStepper {
	/**
	 * A completed step: the player's level game time after the server tick, the client tick
	 * of the player tick, and how far that tick moved the player, in blocks.
	 */
	public record Tick(long serverTick, long clientTick, double[] displacement) {
	}

	private enum Phase { IDLE, SERVER, PLAYER, FRAME }

	private static final TickStepper INSTANCE = new TickStepper();

	private boolean held;
	private Phase phase = Phase.IDLE;
	private @Nullable CompletableFuture<Tick> step;
	private @Nullable Consumer<LocalPlayer> beforeTick;
	private @Nullable MinecraftServer server;
	/** The game time the server tick reaches; written on the server thread. */
	private volatile long targetGameTime = Long.MAX_VALUE;
	private boolean permit;
	private long clientTicks;
	private long playerTickedAt = -1;
	private double[] before = new double[3];

	private TickStepper() {
	}

	public static TickStepper get() {
		return INSTANCE;
	}

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> INSTANCE.endClientTick());
		LevelRenderEvents.END_MAIN.register(context -> INSTANCE.frameRendered());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> INSTANCE.forget());
	}

	public boolean held() {
		return this.held;
	}

	/** Freezes the server's ticking and stops the local player ticking until {@link #release}. */
	public void hold(MinecraftServer server) {
		this.server = server;
		this.held = true;
		server.execute(() -> server.tickRateManager().setFrozen(true));
	}

	/**
	 * Runs one server tick and one local-player tick, calling {@code beforeTick} just before
	 * the player tick; completes once a frame showing that tick has rendered.
	 *
	 * @throws IllegalStateException if not holding, or a step is still running
	 */
	public CompletableFuture<Tick> advance(Consumer<LocalPlayer> beforeTick) {
		MinecraftServer server = this.server;
		if (!this.held || server == null) {
			throw new IllegalStateException("advance needs hold first");
		}

		if (this.phase != Phase.IDLE) {
			throw new IllegalStateException("a step is still running");
		}

		CompletableFuture<Tick> step = new CompletableFuture<>();
		this.step = step;
		this.beforeTick = beforeTick;
		this.targetGameTime = Long.MAX_VALUE;
		this.phase = Phase.SERVER;
		LocalPlayer local = Minecraft.getInstance().player;
		server.execute(() -> {
			ServerPlayer player = local == null ? null : server.getPlayerList().getPlayer(local.getUUID());
			if (player == null) {
				Minecraft.getInstance().execute(this::cancel);
				step.completeExceptionally(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "no server player to step"));
				return;
			}

			long before = player.level().getGameTime();
			server.tickRateManager().stepGameIfPaused(1);
			this.targetGameTime = before + 1;
		});
		return step;
	}

	/**
	 * Abandons a running step without releasing the hold, so a step that failed cannot apply
	 * its input later, unrecorded. The step's future fails.
	 */
	public void cancel() {
		boolean held = this.held;
		forget();
		this.held = held;
	}

	/** Stops holding: the server ticks normally again and so does the player. A running step fails. */
	public void release(MinecraftServer server) {
		server.execute(() -> server.tickRateManager().setFrozen(false));
		forget();
	}

	/**
	 * Whether the client level must skip {@code entity}'s tick now. While holding, the local
	 * player ticks only for a step's permit, after receiving that step's input.
	 */
	public boolean skipsTick(Entity entity) {
		if (!this.held || !(entity instanceof LocalPlayer player) || player != Minecraft.getInstance().player) {
			return false;
		}

		if (!this.permit) {
			return true;
		}

		this.permit = false;
		Consumer<LocalPlayer> input = this.beforeTick;
		if (input != null) {
			input.accept(player);
		}

		this.playerTickedAt = this.clientTicks;
		this.before = new double[] {player.getX(), player.getY(), player.getZ()};
		return false;
	}

	private void endClientTick() {
		this.clientTicks++;
		if (this.phase == Phase.SERVER) {
			MinecraftServer server = this.server;
			LocalPlayer local = Minecraft.getInstance().player;
			ServerPlayer player = server == null || local == null ? null : server.getPlayerList().getPlayer(local.getUUID());
			if (player != null && player.level().getGameTime() >= this.targetGameTime) {
				// The player ticks during the next client tick's entity ticks.
				this.permit = true;
				this.playerTickedAt = -1;
				this.phase = Phase.PLAYER;
			}
		} else if (this.phase == Phase.PLAYER && this.playerTickedAt >= 0) {
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				this.before = new double[] {player.getX() - this.before[0], player.getY() - this.before[1], player.getZ() - this.before[2]};
				player.setOldPosAndRot();
			}

			this.phase = Phase.FRAME;
		}
	}

	private void frameRendered() {
		if (this.phase == Phase.FRAME) {
			CompletableFuture<Tick> step = this.step;
			Tick tick = new Tick(this.targetGameTime, this.playerTickedAt, this.before);
			this.phase = Phase.IDLE;
			this.step = null;
			this.beforeTick = null;
			if (step != null) {
				step.complete(tick);
			}
		}
	}

	private void forget() {
		CompletableFuture<Tick> step = this.step;
		this.held = false;
		this.phase = Phase.IDLE;
		this.step = null;
		this.beforeTick = null;
		this.permit = false;
		if (step != null) {
			step.completeExceptionally(new EnvironmentException(EnvironmentException.Reason.NO_LOCAL_RUNTIME, "stepping stopped"));
		}
	}
}
