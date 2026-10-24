package io.github.thedeadbolt.honeyfactory.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.thedeadbolt.honeyfactory.HoneyFactory;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Persistent proof that a world belongs to honey-factory, stored in the world's
 * {@code data/} folder. It also carries the baseline snapshot taken before the mod
 * changed world or player settings, so they can be restored even after a crash.
 */
public final class WorldOwnership extends SavedData {
	public static final int FORMAT_VERSION = 1;

	/** A player's state before the baseline replaced it. */
	public record PlayerSnapshot(String gameMode, String dimension, double x, double y, double z, float yaw, float pitch) {
		static final Codec<PlayerSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("game_mode").forGetter(PlayerSnapshot::gameMode),
			Codec.STRING.fieldOf("dimension").forGetter(PlayerSnapshot::dimension),
			Codec.DOUBLE.fieldOf("x").forGetter(PlayerSnapshot::x),
			Codec.DOUBLE.fieldOf("y").forGetter(PlayerSnapshot::y),
			Codec.DOUBLE.fieldOf("z").forGetter(PlayerSnapshot::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(PlayerSnapshot::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(PlayerSnapshot::pitch)
		).apply(instance, PlayerSnapshot::new));
	}

	/** World settings before the baseline: game rules by ID, the default clock and weather. */
	public record WorldSnapshot(
		Map<String, String> gameRules,
		long clockTicks,
		boolean raining,
		boolean thundering,
		int clearWeatherTime,
		int rainTime,
		int thunderTime,
		Map<String, PlayerSnapshot> players
	) {
		static final Codec<WorldSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("game_rules").forGetter(WorldSnapshot::gameRules),
			Codec.LONG.fieldOf("clock_ticks").forGetter(WorldSnapshot::clockTicks),
			Codec.BOOL.fieldOf("raining").forGetter(WorldSnapshot::raining),
			Codec.BOOL.fieldOf("thundering").forGetter(WorldSnapshot::thundering),
			Codec.INT.fieldOf("clear_weather_time").forGetter(WorldSnapshot::clearWeatherTime),
			Codec.INT.fieldOf("rain_time").forGetter(WorldSnapshot::rainTime),
			Codec.INT.fieldOf("thunder_time").forGetter(WorldSnapshot::thunderTime),
			Codec.unboundedMap(Codec.STRING, PlayerSnapshot.CODEC).fieldOf("players").forGetter(WorldSnapshot::players)
		).apply(instance, WorldSnapshot::new));

		public WorldSnapshot withPlayer(String uuid, PlayerSnapshot player) {
			Map<String, PlayerSnapshot> copy = new HashMap<>(this.players);
			copy.put(uuid, player);
			return new WorldSnapshot(this.gameRules, this.clockTicks, this.raining, this.thundering, this.clearWeatherTime, this.rainTime, this.thunderTime, Map.copyOf(copy));
		}
	}

	/**
	 * The claim itself: which world, which bounds, any pending baseline snapshot, and the
	 * box of the natural region the last source-patch copy wrote, so the next copy can clear
	 * what lies outside its own. Records from before natural copies simply lack it.
	 */
	public record Record(
		int formatVersion,
		String worldId,
		String levelName,
		long claimedAtEpochMillis,
		BlockBox ownedArena,
		BlockBox stagingPlatform,
		Optional<WorldSnapshot> baseline,
		Optional<BlockBox> naturalWritten
	) {
		// Kept inside Record so static initialization never depends on the enclosing class.
		private static final Codec<BlockBox> BOX_CODEC = Codec.INT.listOf(6, 6).xmap(
			values -> new BlockBox(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5)),
			box -> List.of(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
		);

		static final Codec<Record> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("format_version").forGetter(Record::formatVersion),
			Codec.STRING.fieldOf("world_id").forGetter(Record::worldId),
			Codec.STRING.fieldOf("level_name").forGetter(Record::levelName),
			Codec.LONG.fieldOf("claimed_at_epoch_millis").forGetter(Record::claimedAtEpochMillis),
			BOX_CODEC.fieldOf("owned_arena").forGetter(Record::ownedArena),
			BOX_CODEC.fieldOf("staging_platform").forGetter(Record::stagingPlatform),
			WorldSnapshot.CODEC.optionalFieldOf("baseline").forGetter(Record::baseline),
			BOX_CODEC.optionalFieldOf("natural_written").forGetter(Record::naturalWritten)
		).apply(instance, Record::new));

		public Record withBaseline(@Nullable WorldSnapshot snapshot) {
			return new Record(this.formatVersion, this.worldId, this.levelName, this.claimedAtEpochMillis, this.ownedArena, this.stagingPlatform, Optional.ofNullable(snapshot), this.naturalWritten);
		}

		public Record withNaturalWritten(BlockBox written) {
			return new Record(this.formatVersion, this.worldId, this.levelName, this.claimedAtEpochMillis, this.ownedArena, this.stagingPlatform, this.baseline, Optional.of(written));
		}
	}

	private static final Codec<WorldOwnership> CODEC = Record.CODEC.optionalFieldOf("record").codec()
		.xmap(record -> new WorldOwnership(record.orElse(null)), ownership -> Optional.ofNullable(ownership.record));

	// Fabric API tolerates a null DataFixTypes for mod saved data.
	public static final SavedDataType<WorldOwnership> TYPE = new SavedDataType<>(HoneyFactory.id("ownership"), WorldOwnership::new, CODEC, null);

	private @Nullable Record record;

	public WorldOwnership() {
		this(null);
	}

	private WorldOwnership(@Nullable Record record) {
		this.record = record;
	}

	public static WorldOwnership of(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public @Nullable Record record() {
		return this.record;
	}

	public void set(Record record) {
		this.record = record;
		setDirty();
	}
}
