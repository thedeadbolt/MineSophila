package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.ContractExamples;
import org.junit.jupiter.api.Test;

/** M12-1: a slice's and a merged dataset's manifest members. */
class DatasetManifestSlicesTest {
	private static JsonObject world(String name, int... slots) {
		JsonObject world = new JsonObject();
		world.addProperty("world", name);
		JsonArray list = new JsonArray();
		for (int slot : slots) {
			list.add(slot);
		}

		world.add("slots", list);
		return world;
	}

	@Test
	void aMergedManifestNamesEachScenesWorld() {
		JsonObject json = ContractExamples.completeDataset().manifest().toJson();
		JsonArray worlds = new JsonArray();
		worlds.add(world("world-0", 0, 2));
		worlds.add(world("world-1", 1, 3));
		json.add("worlds", worlds);
		DatasetManifest manifest = DatasetManifest.fromJson(json);
		assertEquals("world-0", manifest.worldOf(2));
		assertEquals("world-1", manifest.worldOf(1));
		assertNull(manifest.worldOf(7), "a slot no world lists has no known world");
		assertEquals(worlds, manifest.toJson().get("worlds"));
		assertNull(ContractExamples.completeDataset().manifest().worldOf(0), "a single run lists no worlds");
	}

	@Test
	void aSliceRoundTripsAndItsRequestedScenesAreItsSlots() {
		DatasetManifest whole = ContractExamples.completeDataset().manifest();
		JsonObject json = whole.toJson();
		JsonObject slice = new JsonObject();
		slice.addProperty("index", 1);
		slice.addProperty("count", 3);
		slice.addProperty("world", "world-1");
		JsonArray slots = new JsonArray();
		for (int i = 0; i < whole.counts().requestedScenes(); i++) {
			slots.add(i * 3 + 1);
		}

		slice.add("slots", slots);
		json.add("slice", slice);
		DatasetManifest parsed = DatasetManifest.fromJson(json);
		assertEquals(new DatasetManifest.Slice(1, 3, "world-1", parsed.slice().slots()), parsed.slice());
		assertEquals(whole.datasetId() + ".slice-1-of-3", parsed.directoryName());
		assertEquals(json, parsed.toJson());

		slots.add(1000);
		assertThrows(ContractException.class, () -> DatasetManifest.fromJson(json), "requested scenes must equal the slice's slots");
	}
}
