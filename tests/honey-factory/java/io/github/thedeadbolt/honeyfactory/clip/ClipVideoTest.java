package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Clip frames go through ffmpeg and come back exactly, or close to it for a lossy codec. */
class ClipVideoTest {
	private static final int WIDTH = 64;
	private static final int HEIGHT = 32;
	private static final int FRAMES = 60;

	@TempDir
	Path directory;

	static Stream<ClipCodec> losslessCodecs() {
		return Stream.of(new ClipCodec.H264Lossless(), new ClipCodec.Ffv1());
	}

	@ParameterizedTest
	@MethodSource("losslessCodecs")
	void roundTripIsPixelExact(ClipCodec codec) throws IOException {
		byte[][] rgb = new byte[FRAMES][];
		byte[][] gray = new byte[FRAMES][];
		Path rgbFile = this.directory.resolve("rgb.mkv");
		Path grayFile = this.directory.resolve("visible.mkv");
		try (ClipVideo colour = ClipVideo.rgb(rgbFile, WIDTH, HEIGHT, codec); ClipVideo mask = ClipVideo.gray(grayFile, WIDTH, HEIGHT, ClipCodecs.maskCodecFor(codec))) {
			for (int f = 0; f < FRAMES; f++) {
				rgb[f] = slidingShape(f, 3);
				gray[f] = mask(f);
				colour.write(rgb[f]);
				mask.write(gray[f]);
			}
		}

		assertArrayEquals(concat(rgb), decode(rgbFile, "rgb24"));
		assertArrayEquals(concat(gray), decode(grayFile, "gray"));
		assertFalse(Files.exists(this.directory.resolve("rgb.mkv.partial")));
	}

	@Test
	void lossyCodecIsCloseNotExact() throws IOException {
		ClipCodec lossy = new ClipCodec.H264Lossy(18);
		assertFalse(lossy.lossless());
		byte[][] rgb = new byte[FRAMES][];
		Path file = this.directory.resolve("rgb.mkv");
		try (ClipVideo video = ClipVideo.rgb(file, WIDTH, HEIGHT, lossy)) {
			for (int f = 0; f < FRAMES; f++) {
				rgb[f] = slidingShape(f, 3);
				video.write(rgb[f]);
			}
		}

		byte[] expected = concat(rgb);
		byte[] decoded = decode(file, "rgb24");
		assertEquals(expected.length, decoded.length);
		long error = 0;
		for (int i = 0; i < expected.length; i++) {
			error += Math.abs((expected[i] & 0xFF) - (decoded[i] & 0xFF));
		}

		assertTrue(error / (double) expected.length < 2.0, "mean absolute error " + error / (double) expected.length);
	}

	@Test
	void masksAlwaysLossless() {
		assertTrue(ClipCodecs.maskCodecFor(new ClipCodec.H264Lossy(18)).lossless());
		assertEquals(new ClipCodec.Ffv1(), ClipCodecs.maskCodecFor(new ClipCodec.Ffv1()));
		assertEquals(new ClipCodec.H264Lossless(), ClipCodecs.maskCodecFor(new ClipCodec.H264Lossless()));
		assertThrows(UnsupportedOperationException.class, () -> new ClipCodec.H264Lossy(18).grayArguments());
		assertThrows(IllegalArgumentException.class, () -> ClipVideo.gray(this.directory.resolve("m.mkv"), WIDTH, HEIGHT, new ClipCodec.H264Lossy(18)));
	}

	@Test
	void codecsReadBackFromJson() {
		for (ClipCodec codec : List.of(new ClipCodec.H264Lossless(), new ClipCodec.Ffv1(), new ClipCodec.H264Lossy(23))) {
			assertEquals(codec, ClipCodecs.fromJson(codec.toJson()));
		}

		assertEquals(23, new ClipCodec.H264Lossy(23).toJson().get("crf").getAsInt());
		assertThrows(IllegalArgumentException.class, () -> new ClipCodec.H264Lossy(52));
	}

	@Test
	void unknownCodecNamed() {
		JsonObject json = new JsonObject();
		json.addProperty("name", "x");
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ClipCodecs.fromJson(json));
		assertTrue(error.getMessage().contains("h264-lossless"), error.getMessage());
		assertTrue(error.getMessage().contains("ffv1"), error.getMessage());
		assertTrue(error.getMessage().contains("h264-lossy"), error.getMessage());
	}

	@Test
	void abortLeavesNoFile() throws IOException {
		Path file = this.directory.resolve("rgb.mkv");
		ClipVideo video = ClipVideo.rgb(file, WIDTH, HEIGHT, new ClipCodec.H264Lossless());
		video.write(slidingShape(0, 3));
		video.abort();
		assertFalse(Files.exists(file));
		assertFalse(Files.exists(this.directory.resolve("rgb.mkv.partial")));
	}

	@Test
	void ffmpegFailureRaises() throws IOException {
		Path file = this.directory.resolve("rgb.mkv");
		ClipVideo video = ClipVideo.rgb(file, WIDTH, HEIGHT, new ClipCodec.H264Lossless());
		video.write(slidingShape(0, 3));
		List<ProcessHandle> encoders = ProcessHandle.current().children()
			.filter(child -> child.info().command().map(command -> command.endsWith("ffmpeg")).orElse(false)).toList();
		assertEquals(1, encoders.size());
		encoders.getFirst().destroyForcibly();
		assertThrows(IOException.class, video::close);
		assertFalse(Files.exists(file));
		assertFalse(Files.exists(this.directory.resolve("rgb.mkv.partial")));
	}

	@Test
	void versionNamesFfmpeg() {
		ClipVideo.requireAvailable();
		assertTrue(ClipVideo.version().startsWith("ffmpeg version"), ClipVideo.version());
	}

	/** A bright square sliding across a gradient, so H.264 predicts frames from earlier ones. */
	private static byte[] slidingShape(int frame, int channels) {
		byte[] pixels = new byte[WIDTH * HEIGHT * channels];
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				boolean square = x >= frame % WIDTH && x < frame % WIDTH + 8 && y >= 10 && y < 18;
				for (int c = 0; c < channels; c++) {
					pixels[(y * WIDTH + x) * channels + c] = (byte) (square ? 250 - 40 * c : x * 3 + y * 2 + c * 20);
				}
			}
		}

		return pixels;
	}

	private static byte[] mask(int frame) {
		byte[] pixels = slidingShape(frame, 1);
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = (pixels[i] & 0xFF) == 250 ? (byte) 255 : 0;
		}

		return pixels;
	}

	private static byte[] concat(byte[][] frames) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] frame : frames) {
			out.writeBytes(frame);
		}

		return out.toByteArray();
	}

	private static byte[] decode(Path file, String pixelFormat) throws IOException {
		Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-i", file.toString(), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-")
			.redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream in = process.getInputStream()) {
			byte[] bytes = in.readAllBytes();
			assertEquals(0, process.waitFor());
			return bytes;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		}
	}
}
