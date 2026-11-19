package io.github.thedeadbolt.honeyfactory.clip;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * How a clip's frames are compressed: the ffmpeg encoder arguments for RGB and for a gray
 * mask. Every codec writes Matroska. A new compression is one more implementation here and
 * one entry in {@link ClipCodecs}. Masks are labels and stay exact, so a lossy codec
 * refuses them ({@link ClipCodecs#maskCodecFor}).
 */
public sealed interface ClipCodec permits ClipCodec.H264Lossless, ClipCodec.Ffv1, ClipCodec.H264Lossy {
	/** The codec's name in configurations and records. */
	String name();

	/** Whether decoding returns exactly the frames written. */
	boolean lossless();

	/** Encoder arguments for {@code rgb24} input. */
	List<String> rgbArguments();

	/**
	 * Encoder arguments for {@code gray} input.
	 *
	 * @throws UnsupportedOperationException for a lossy codec
	 */
	List<String> grayArguments();

	default JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("name", name());
		return json;
	}

	/** Lossless H.264 (the default): predicts frames from earlier ones, so a clip compresses well. */
	record H264Lossless() implements ClipCodec {
		public static final String NAME = "h264-lossless";

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public boolean lossless() {
			return true;
		}

		@Override
		public List<String> rgbArguments() {
			return List.of("-c:v", "libx264rgb", "-qp", "0", "-preset", "medium", "-g", "48", "-pix_fmt", "rgb24");
		}

		@Override
		public List<String> grayArguments() {
			return List.of("-c:v", "libx264", "-qp", "0", "-preset", "medium", "-g", "48", "-pix_fmt", "gray");
		}
	}

	/** FFV1: lossless and intra-only, so any frame decodes on its own, at a larger size. */
	record Ffv1() implements ClipCodec {
		public static final String NAME = "ffv1";

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public boolean lossless() {
			return true;
		}

		@Override
		public List<String> rgbArguments() {
			return List.of("-c:v", "ffv1", "-level", "3", "-g", "1", "-pix_fmt", "gbrp");
		}

		@Override
		public List<String> grayArguments() {
			return List.of("-c:v", "ffv1", "-level", "3", "-g", "1", "-pix_fmt", "gray");
		}
	}

	/** Lossy H.264 at constant quality {@code crf} (0–51, lower is better), with full-resolution colour. */
	record H264Lossy(int crf) implements ClipCodec {
		public static final String NAME = "h264-lossy";

		public H264Lossy {
			if (crf < 0 || crf > 51) {
				throw new IllegalArgumentException("crf must be 0 to 51, got " + crf);
			}
		}

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public boolean lossless() {
			return false;
		}

		@Override
		public List<String> rgbArguments() {
			return List.of("-c:v", "libx264", "-crf", Integer.toString(this.crf), "-preset", "medium", "-g", "48", "-pix_fmt", "yuv444p");
		}

		@Override
		public List<String> grayArguments() {
			throw new UnsupportedOperationException("masks must be lossless; " + NAME + " is lossy");
		}

		@Override
		public JsonObject toJson() {
			JsonObject json = ClipCodec.super.toJson();
			json.addProperty("crf", this.crf);
			return json;
		}
	}
}
