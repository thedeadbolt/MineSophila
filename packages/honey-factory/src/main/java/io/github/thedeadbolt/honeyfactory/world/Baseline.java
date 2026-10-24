package io.github.thedeadbolt.honeyfactory.world;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.Vec3;

/**
 * Explicit world and player state for dataset scenes, applied after snapshotting the
 * previous settings into the ownership record and restored by {@link #restore}.
 *
 * <p>World baseline: no daylight or weather cycle, no mob spawning or griefing, no random
 * block ticks, and spectators still load chunks. Player baseline: spectator mode (no
 * collision, held item or rendered hand), zero velocity, placed by the arena executor;
 * movement actions use invulnerable adventure mode instead.
 */
public final class Baseline {
	private static final int WEATHER_DURATION_TICKS = 1_000_000;

	private record RuleValue<T>(GameRule<T> rule, T value, Function<String, T> parser) {
		void apply(GameRules rules, MinecraftServer server) {
			rules.set(this.rule, this.value, server);
		}

		void restore(GameRules rules, MinecraftServer server, String saved) {
			rules.set(this.rule, this.parser.apply(saved), server);
		}
	}

	private static final List<RuleValue<?>> RULES = List.of(
		new RuleValue<>(GameRules.ADVANCE_TIME, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.ADVANCE_WEATHER, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.SPAWN_MOBS, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.SPAWN_MONSTERS, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.SPAWN_PATROLS, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.SPAWN_PHANTOMS, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.SPAWN_WANDERING_TRADERS, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.MOB_GRIEFING, false, Boolean::parseBoolean),
		new RuleValue<>(GameRules.RANDOM_TICK_SPEED, 0, Integer::parseInt),
		new RuleValue<>(GameRules.SPECTATORS_GENERATE_CHUNKS, true, Boolean::parseBoolean)
	);

	/** Rule IDs the baseline controls. */
	public static final Set<String> RULE_IDS = Set.copyOf(RULES.stream().map(value -> value.rule().id()).toList());

	private Baseline() {
	}

	/** The enforced rule values by rule ID, as a scene record's {@code environment.gamerules}. */
	public static JsonObject ruleValues() {
		JsonObject values = new JsonObject();
		for (RuleValue<?> value : RULES) {
			if (value.value() instanceof Boolean flag) {
				values.addProperty(value.rule().id(), flag);
			} else {
				values.addProperty(value.rule().id(), (Number) value.value());
			}
		}

		return values;
	}

	/** Snapshots world settings once per run, then applies the baseline rules. */
	public static void applyWorld(MinecraftServer server) {
		WorldOwnership ownership = WorldOwnership.of(server);
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		if (record.baseline().isEmpty()) {
			GameRules rules = server.getGameRules();
			Map<String, String> saved = new HashMap<>();
			RULES.forEach(value -> saved.put(value.rule().id(), rules.getAsString(value.rule())));
			WeatherData weather = server.getWeatherData();
			ownership.set(record.withBaseline(new WorldOwnership.WorldSnapshot(
				Map.copyOf(saved),
				server.clockManager().getInstance(clock(server)).totalTicks(),
				weather.isRaining(),
				weather.isThundering(),
				weather.getClearWeatherTime(),
				weather.getRainTime(),
				weather.getThunderTime(),
				Map.of()
			)));
		}

		RULES.forEach(value -> value.apply(server.getGameRules(), server));
	}

	/**
	 * Sets the scene's time of day and weather; the baseline keeps them from changing.
	 * Minecraft fades rain and thunder in and out over about 100 ticks, so their levels are
	 * set at once in every dimension and sent to its players; {@link #rainLevel} and
	 * {@link #thunderLevel} are what clients then show.
	 */
	public static void applyEnvironment(MinecraftServer server, int timeOfDayTicks, Weather weather) {
		server.clockManager().setTotalTicks(clock(server), timeOfDayTicks);
		switch (weather) {
			case CLEAR -> server.setWeatherParameters(WEATHER_DURATION_TICKS, 0, false, false);
			case RAIN -> server.setWeatherParameters(0, WEATHER_DURATION_TICKS, true, false);
			case THUNDER -> server.setWeatherParameters(0, WEATHER_DURATION_TICKS, true, true);
		}

		for (ServerLevel level : server.getAllLevels()) {
			level.setRainLevel(rainLevel(weather));
			level.setThunderLevel(thunderLevel(weather));
			server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rainLevel(weather)), level.dimension());
			server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunderLevel(weather)), level.dimension());
		}
	}

	/** The rain level of {@code weather}, from 0 to 1. */
	public static float rainLevel(Weather weather) {
		return weather == Weather.CLEAR ? 0.0F : 1.0F;
	}

	/** The thunder level of {@code weather}, from 0 to 1, before Minecraft scales it by the rain level. */
	public static float thunderLevel(Weather weather) {
		return weather == Weather.THUNDER ? 1.0F : 0.0F;
	}

	/** Snapshots a player once per run and puts them in spectator mode without velocity. */
	public static void preparePlayer(MinecraftServer server, ServerPlayer player) {
		snapshotPlayer(server, player);
		player.setGameMode(GameType.SPECTATOR);
		player.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * Snapshots a player once per run and gives them a body for movement actions: adventure
	 * mode, so they collide and fall but cannot break or place blocks, and invulnerable, so a
	 * fall or a fluid cannot end the episode. Velocity is kept.
	 */
	public static void prepareBody(MinecraftServer server, ServerPlayer player) {
		snapshotPlayer(server, player);
		player.setGameMode(GameType.ADVENTURE);
		player.getAbilities().invulnerable = true;
		player.onUpdateAbilities();
	}

	private static void snapshotPlayer(MinecraftServer server, ServerPlayer player) {
		WorldOwnership ownership = WorldOwnership.of(server);
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		WorldOwnership.WorldSnapshot snapshot = record.baseline().orElseThrow(() -> new IllegalStateException("Apply the world baseline first"));
		String uuid = player.getStringUUID();
		if (!snapshot.players().containsKey(uuid)) {
			ownership.set(record.withBaseline(snapshot.withPlayer(uuid, new WorldOwnership.PlayerSnapshot(
				player.gameMode().getName(),
				player.level().dimension().identifier().toString(),
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getYRot(),
				player.getXRot()
			))));
		}
	}

	/**
	 * Restores every snapshotted setting and online player, then clears the snapshot.
	 * Offline players keep their snapshot until they are restored by a later release.
	 *
	 * @return whether a snapshot existed
	 */
	public static boolean restore(MinecraftServer server) {
		WorldOwnership ownership = WorldOwnership.of(server);
		WorldOwnership.Record record = WorldGuard.requireOwned(server);
		if (record.baseline().isEmpty()) {
			return false;
		}

		WorldOwnership.WorldSnapshot snapshot = record.baseline().get();
		// An empty rule map marks world settings restored while offline players are pending.
		if (!snapshot.gameRules().isEmpty()) {
			GameRules rules = server.getGameRules();
			for (RuleValue<?> value : RULES) {
				String saved = snapshot.gameRules().get(value.rule().id());
				if (saved != null) {
					value.restore(rules, server, saved);
				}
			}

			server.clockManager().setTotalTicks(clock(server), snapshot.clockTicks());
			server.setWeatherParameters(snapshot.clearWeatherTime(), snapshot.raining() ? snapshot.rainTime() : 0, snapshot.raining(), snapshot.thundering());
		}
		Map<String, WorldOwnership.PlayerSnapshot> pending = new HashMap<>(snapshot.players());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			WorldOwnership.PlayerSnapshot saved = pending.remove(player.getStringUUID());
			if (saved != null) {
				restorePlayer(server, player, saved);
			}
		}

		ownership.set(record.withBaseline(pending.isEmpty() ? null : new WorldOwnership.WorldSnapshot(
			Map.of(), snapshot.clockTicks(), snapshot.raining(), snapshot.thundering(), snapshot.clearWeatherTime(), snapshot.rainTime(), snapshot.thunderTime(), Map.copyOf(pending)
		)));
		return true;
	}

	private static void restorePlayer(MinecraftServer server, ServerPlayer player, WorldOwnership.PlayerSnapshot saved) {
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(saved.dimension())));
		if (level == null) {
			level = server.overworld();
		}

		player.setGameMode(GameType.byName(saved.gameMode(), GameType.SURVIVAL));
		player.teleportTo(level, saved.x(), saved.y(), saved.z(), Set.of(), saved.yaw(), saved.pitch(), true);
		player.setDeltaMovement(Vec3.ZERO);
	}

	private static Holder<WorldClock> clock(MinecraftServer server) {
		return server.overworld().dimensionType().defaultClock().orElseThrow(() -> new IllegalStateException("The overworld has no default clock"));
	}
}
