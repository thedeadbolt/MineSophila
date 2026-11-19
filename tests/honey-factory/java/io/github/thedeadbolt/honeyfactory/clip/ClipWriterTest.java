package io.github.thedeadbolt.honeyfactory.clip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.CaptureMode;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A clip writer whose encoder dies fails the clip instead of holding its caller. */
class ClipWriterTest {
	private static final int SIZE = 64;
	private static final CaptureProjection PERSPECTIVE = CaptureProjection.of(CaptureMode.PERSPECTIVE_90, SIZE, SIZE, null);
	private static final CameraPose POSE = new CameraPose(0.5, 0.0, 0.5, 0.0f, 0.0f);

	@TempDir
	Path directory;

	@Test
	void ffmpegDyingMidClipFailsTheClipWithoutBlocking() throws IOException {
		ClipWriter writer = ClipWriter.open(this.directory, Split.TRAIN, "s000000-c00", SIZE, SIZE, new ClipCodec.H264Lossless());
		writer.frame(images(), record(0));
		List<ProcessHandle> encoders = ProcessHandle.current().children()
			.filter(child -> child.info().command().map(command -> command.endsWith("ffmpeg")).orElse(false)).toList();
		assertEquals(2, encoders.size(), "an RGB and a mask encoder");
		encoders.forEach(ProcessHandle::destroyForcibly);

		// A clip runs up to 600 frames on the game's render thread: frame() must report the
		// failure, never wait for permits the skipped encode stages would have returned.
		assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertThrows(IOException.class, () -> {
			for (int frame = 1; frame < 30 * ClipWriter.QUEUED_FRAMES; frame++) {
				writer.frame(images(), record(frame));
			}
		}));
		assertThrows(IOException.class, writer::finish);
		try (Stream<Path> left = Files.walk(this.directory)) {
			assertFalse(left.anyMatch(path -> path.getFileName().toString().startsWith("s000000-c00")), "no clip, whole or partial, is left");
		}
	}

	private static CapturedImages images() {
		return CapturedImages.withoutTarget(SIZE, SIZE, new byte[SIZE * SIZE * 3]);
	}

	private static FrameRecord record(int frame) {
		return ClipLabels.frame("s000000-c00", frame, frame / 3, frame % 3, 3, new ClipEnvironment.Frame(POSE, (frame % 3) / 3.0, images()), null, PERSPECTIVE, 1.62, null);
	}
}
