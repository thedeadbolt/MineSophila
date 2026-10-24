package io.github.thedeadbolt.honeyfactory.world;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.environment.EnvironmentException;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The only way to obtain permission to change a world. A world is owned when it was
 * created with the dataset-world preset, an operator claimed it by typing its exact name,
 * and the stored claim still matches this world and the code's owned bounds.
 */
public final class WorldGuard {
	private WorldGuard() {
	}

	/** Claims the current world, or refreshes the claim after the world was renamed. */
	public static WorldOwnership.Record claim(MinecraftServer server, String confirmation) {
		String levelName = server.getWorldData().getLevelName();
		arena(server);
		if (!levelName.equals(confirmation)) {
			throw refuse("type the world's exact name to claim it: '" + levelName + "'");
		}

		WorldOwnership ownership = WorldOwnership.of(server);
		WorldOwnership.Record previous = ownership.record();
		WorldOwnership.Record record = new WorldOwnership.Record(
			WorldOwnership.FORMAT_VERSION,
			previous == null ? UUID.randomUUID().toString() : previous.worldId(),
			levelName,
			previous == null ? System.currentTimeMillis() : previous.claimedAtEpochMillis(),
			ArenaLayout.OWNED,
			ArenaLayout.STAGING_PLATFORM,
			previous == null ? Optional.empty() : previous.baseline(),
			previous == null ? Optional.empty() : previous.naturalWritten()
		);
		ownership.set(record);
		return record;
	}

	/** @throws EnvironmentException {@link EnvironmentException.Reason#NOT_OWNED} unless this world is a verified dataset world */
	public static WorldOwnership.Record requireOwned(MinecraftServer server) {
		WorldOwnership.Record record = WorldOwnership.of(server).record();
		if (record == null) {
			throw refuse("this world is not a claimed honey-factory dataset world; create it with the Honey Factory Dataset world type and run /honeyfactory world claim");
		}

		if (record.formatVersion() != WorldOwnership.FORMAT_VERSION) {
			throw refuse("unsupported ownership record version " + record.formatVersion());
		}

		String levelName = server.getWorldData().getLevelName();
		if (!record.levelName().equals(levelName)) {
			throw refuse("the ownership record names world '" + record.levelName() + "' but this world is '" + levelName + "'; claim it again if it was renamed");
		}

		if (!record.ownedArena().equals(ArenaLayout.OWNED) || !record.stagingPlatform().equals(ArenaLayout.STAGING_PLATFORM)) {
			throw refuse("the ownership record's bounds do not match this mod version");
		}

		arena(server);
		return record;
	}

	/** @throws EnvironmentException unless {@code box} lies inside the claimed arena, the natural region or the staging platform */
	public static void requireWritable(WorldOwnership.Record record, BlockBox box) {
		if (!record.ownedArena().contains(box) && !ArenaLayout.NATURAL_OWNED.contains(box) && !record.stagingPlatform().contains(box)) {
			throw refuse("refusing to change " + box + " outside the owned arena " + record.ownedArena());
		}
	}

	public static ServerLevel arena(MinecraftServer server) {
		ServerLevel level = server.getLevel(ArenaDimension.LEVEL);
		if (level == null) {
			throw refuse("this world has no " + ArenaDimension.LEVEL.identifier() + " dimension; only worlds created with the Honey Factory Dataset world type can be used");
		}

		return level;
	}

	private static EnvironmentException refuse(String message) {
		return new EnvironmentException(EnvironmentException.Reason.NOT_OWNED, message);
	}
}
