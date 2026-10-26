package io.github.thedeadbolt.honeyfactory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.Fixtures;
import io.github.thedeadbolt.honeyfactory.clip.ClipCodec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Clip, frame and clip-manifest records write back exactly what they read. */
class ClipRecordTest {
	@Test
	void clipRoundTrips() {
		JsonObject json = Fixtures.json(Fixtures.contract().resolve("valid/clip-approach.json"));
		ClipRecord clip = ClipRecord.fromJson(json);
		assertEquals(json, clip.toJson());
		assertEquals(ClipType.APPROACH, clip.clipType());
		assertEquals(new ClipCodec.H264Lossless(), clip.codec());
		assertEquals(7, clip.frameCount());
	}

	@ParameterizedTest
	@ValueSource(strings = {"frame-reset", "frame-interpolated", "frame-tick", "frame-no-honey"})
	void framesRoundTrip(String name) {
		JsonObject json = Fixtures.json(Fixtures.contract().resolve("valid/" + name + ".json"));
		assertEquals(json, FrameRecord.fromJson(json).toJson());
	}

	@Test
	void interpolatedFramesCarryNoTickLabels() {
		FrameRecord frame = FrameRecord.fromJson(Fixtures.json(Fixtures.contract().resolve("valid/frame-interpolated.json")));
		assertNull(frame.tickLabels());
		assertEquals(1.0 / 3.0, frame.partialTick(), 1e-12);
	}

	@Test
	void clipManifestRoundTrips() {
		JsonObject json = Fixtures.json(Fixtures.contract().resolve("valid/dataset-clips-complete.json"));
		DatasetManifest manifest = DatasetManifest.fromJson(json);
		assertEquals(DatasetKind.CLIPS, manifest.kind());
		assertEquals(3, manifest.clipSettings().framesPerTick());
		assertEquals(8, manifest.counts().acceptedClips());
		assertEquals(json, manifest.toJson());
	}

	@Test
	void stillManifestHasNoKind() {
		JsonObject json = Fixtures.json(Fixtures.contract().resolve("valid/dataset-complete.json"));
		DatasetManifest manifest = DatasetManifest.fromJson(json);
		assertEquals(DatasetKind.STILLS, manifest.kind());
		assertEquals(json, manifest.toJson());
	}

	@Test
	void defaultSettingsRoundTrip() {
		ClipSettings defaults = ClipSettings.defaults();
		assertEquals(defaults, ClipSettings.fromJson(JsonFields.of(defaults.toJson())));
		assertEquals(50, defaults.teacher().minVisiblePixels());
		assertEquals(100, defaults.ticks(ClipType.DETOUR));
	}

	@Test
	void defaultMixHalvesTheScriptedShare() {
		ClipSettings defaults = ClipSettings.defaults();
		assertEquals(0.35, defaults.mix().get(ClipType.APPROACH));
		assertEquals(0.075, defaults.mix().get(ClipType.PASS_BY));
		assertEquals(0.075, defaults.mix().get(ClipType.SCAN));
		assertEquals(0.15, defaults.mix().get(ClipType.OCCLUSION));
		assertEquals(0.15, defaults.mix().get(ClipType.DETOUR));
		assertEquals(0.3, defaults.drift().hazardFraction());
	}
}
