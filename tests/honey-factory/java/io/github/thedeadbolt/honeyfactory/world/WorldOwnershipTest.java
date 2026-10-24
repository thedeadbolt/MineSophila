package io.github.thedeadbolt.honeyfactory.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.github.thedeadbolt.honeyfactory.scene.ArenaLayout;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorldOwnershipTest {
	@Test
	void recordsRoundTripWithAndWithoutABaselineOrNaturalWrites() {
		WorldOwnership.Record record = new WorldOwnership.Record(
			WorldOwnership.FORMAT_VERSION, "6a1f…", "honey-factory-m2", 1_761_200_000_000L, ArenaLayout.OWNED, ArenaLayout.STAGING_PLATFORM, Optional.empty(), Optional.empty()
		);
		WorldOwnership.WorldSnapshot snapshot = new WorldOwnership.WorldSnapshot(Map.of("random_tick_speed", "3"), 18_000L, true, false, 0, 1200, 0, Map.of())
			.withPlayer("uuid", new WorldOwnership.PlayerSnapshot("creative", "minecraft:overworld", 1.5, 70.0, -3.25, 90.0F, 10.0F));
		for (WorldOwnership.Record value : new WorldOwnership.Record[] {record, record.withBaseline(snapshot), record.withNaturalWritten(ArenaLayout.NATURAL_OWNED)}) {
			JsonElement encoded = WorldOwnership.Record.CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow();
			assertEquals(value, WorldOwnership.Record.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
		}

		JsonElement older = WorldOwnership.Record.CODEC.encodeStart(JsonOps.INSTANCE, record).getOrThrow();
		older.getAsJsonObject().remove("natural_written");
		assertEquals(Optional.empty(), WorldOwnership.Record.CODEC.parse(JsonOps.INSTANCE, older).getOrThrow().naturalWritten(), "records from before natural copies still load");
	}
}
