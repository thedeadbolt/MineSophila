package io.github.thedeadbolt.honeyfactory.clip;

import com.google.gson.JsonObject;
import java.util.List;

/** The known clip codecs by name, and the lossless codec a clip's masks use. */
public final class ClipCodecs {
	/** Names in the order error messages list them. */
	public static final List<String> NAMES = List.of(ClipCodec.H264Lossless.NAME, ClipCodec.Ffv1.NAME, ClipCodec.H264Lossy.NAME);
	public static final int DEFAULT_CRF = 18;

	private ClipCodecs() {
	}

	/**
	 * Reads {@code {name, crf?}}; {@code crf} applies to {@code h264-lossy} only.
	 *
	 * @throws IllegalArgumentException for an unknown name or a bad {@code crf}
	 */
	public static ClipCodec fromJson(JsonObject json) {
		if (!json.has("name") || !json.get("name").isJsonPrimitive() || !json.getAsJsonPrimitive("name").isString()) {
			throw new IllegalArgumentException("a clip codec needs a name: one of " + String.join(", ", NAMES));
		}

		String name = json.get("name").getAsString();
		return switch (name) {
			case ClipCodec.H264Lossless.NAME -> new ClipCodec.H264Lossless();
			case ClipCodec.Ffv1.NAME -> new ClipCodec.Ffv1();
			case ClipCodec.H264Lossy.NAME -> new ClipCodec.H264Lossy(json.has("crf") ? json.get("crf").getAsInt() : DEFAULT_CRF);
			default -> throw new IllegalArgumentException("unknown clip codec " + name + "; known codecs are " + String.join(", ", NAMES));
		};
	}

	/** The codec for a clip's masks: FFV1 beside FFV1, lossless H.264 otherwise, so masks stay exact. */
	public static ClipCodec maskCodecFor(ClipCodec rgb) {
		return rgb instanceof ClipCodec.Ffv1 ? rgb : new ClipCodec.H264Lossless();
	}
}
