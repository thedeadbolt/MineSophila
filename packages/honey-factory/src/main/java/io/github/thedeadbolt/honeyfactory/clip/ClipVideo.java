package io.github.thedeadbolt.honeyfactory.clip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One video file of a clip, written frame by frame through an {@code ffmpeg} process. The
 * file appears under its name only once ffmpeg has finished it; until then it is
 * {@code <name>.partial}, and a failed or aborted video leaves nothing behind.
 */
public final class ClipVideo implements AutoCloseable {
	/** The frame rate written into the file: three frames per 20 Hz tick. Players use it; labels count frames. */
	public static final int NOMINAL_FPS = 60;
	private static final int ERROR_TAIL_CHARS = 4096;

	private final Path file;
	private final Path partial;
	private final int frameBytes;
	private final Process process;
	private final OutputStream input;
	private final Thread errors;
	private final StringBuilder errorTail = new StringBuilder();
	private boolean finished;

	private ClipVideo(Path file, int frameBytes, Process process) {
		this.file = file;
		this.partial = partial(file);
		this.frameBytes = frameBytes;
		this.process = process;
		this.input = process.getOutputStream();
		this.errors = Thread.ofVirtual().start(this::drainErrors);
	}

	/** Starts a video of {@code width × height} RGB8 frames. */
	public static ClipVideo rgb(Path file, int width, int height, ClipCodec codec) throws IOException {
		return start(file, width, height, "rgb24", 3, codec.rgbArguments());
	}

	/**
	 * Starts a video of {@code width × height} 8-bit gray frames, such as a mask.
	 *
	 * @throws IllegalArgumentException for a lossy codec: masks are labels and stay exact
	 */
	public static ClipVideo gray(Path file, int width, int height, ClipCodec codec) throws IOException {
		if (!codec.lossless()) {
			throw new IllegalArgumentException("masks must be lossless; " + codec.name() + " is lossy");
		}

		return start(file, width, height, "gray", 1, codec.grayArguments());
	}

	private static ClipVideo start(Path file, int width, int height, String pixelFormat, int channels, List<String> codecArguments) throws IOException {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("a video needs a positive size, got " + width + "x" + height);
		}

		List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-v", "error", "-y",
			"-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", width + "x" + height, "-r", Integer.toString(NOMINAL_FPS), "-i", "pipe:0"));
		command.addAll(codecArguments);
		command.addAll(List.of("-f", "matroska", partial(file).toString()));
		Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		return new ClipVideo(file, width * height * channels, process);
	}

	/** Appends one frame, row-major from the top. */
	public void write(byte[] frame) throws IOException {
		if (this.finished) {
			throw new IllegalStateException("video " + this.file + " is already finished");
		}

		if (frame.length != this.frameBytes) {
			throw new IllegalArgumentException("a frame of " + this.file.getFileName() + " has " + this.frameBytes + " bytes, got " + frame.length);
		}

		try {
			this.input.write(frame);
		} catch (IOException e) {
			abort();
			throw new IOException("ffmpeg stopped while writing " + this.file + ": " + errorTail(), e);
		}
	}

	/**
	 * Finishes the video and gives it its name.
	 *
	 * @throws IOException when ffmpeg fails; nothing is left behind
	 */
	@Override
	public void close() throws IOException {
		if (this.finished) {
			return;
		}

		this.finished = true;
		try {
			this.input.close();
		} catch (IOException e) {
			// A dead ffmpeg closes the pipe; its exit code below says why.
		}

		int exit = waitFor();
		if (exit != 0) {
			Files.deleteIfExists(this.partial);
			throw new IOException("ffmpeg failed with exit code " + exit + " writing " + this.file + ": " + errorTail());
		}

		Files.move(this.partial, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/** Stops ffmpeg and removes the unfinished file. */
	public void abort() throws IOException {
		if (this.finished && !this.process.isAlive()) {
			Files.deleteIfExists(this.partial);
			return;
		}

		this.finished = true;
		this.process.destroyForcibly();
		waitFor();
		Files.deleteIfExists(this.partial);
	}

	/** The first line of {@code ffmpeg -version}, for the manifest. */
	public static String version() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String first = reader.readLine();
				reader.transferTo(java.io.Writer.nullWriter());
				if (process.waitFor() != 0 || first == null) {
					throw new IllegalStateException("ffmpeg -version failed");
				}

				return first.strip();
			}
		} catch (IOException e) {
			throw new IllegalStateException("ffmpeg is required for clip datasets: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while asking ffmpeg for its version", e);
		}
	}

	/**
	 * Checks that ffmpeg runs.
	 *
	 * @throws IllegalStateException naming the requirement when it does not
	 */
	public static void requireAvailable() {
		try {
			version();
		} catch (IllegalStateException e) {
			throw new IllegalStateException("ffmpeg is required for clip datasets: install it and put it on the PATH (" + e.getMessage() + ")", e);
		}
	}

	private int waitFor() throws IOException {
		try {
			int exit = this.process.waitFor();
			this.errors.join();
			return exit;
		} catch (InterruptedException e) {
			this.process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while waiting for ffmpeg writing " + this.file, e);
		}
	}

	/** Reads ffmpeg's messages as they come, so its pipe never fills, keeping the last few kilobytes. */
	private void drainErrors() {
		try (InputStream in = this.process.getErrorStream()) {
			byte[] buffer = new byte[1024];
			for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
				synchronized (this.errorTail) {
					this.errorTail.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
					if (this.errorTail.length() > ERROR_TAIL_CHARS) {
						this.errorTail.delete(0, this.errorTail.length() - ERROR_TAIL_CHARS);
					}
				}
			}
		} catch (IOException e) {
			// The process is gone; what was read is kept.
		}
	}

	private String errorTail() {
		synchronized (this.errorTail) {
			String tail = this.errorTail.toString().strip();
			return tail.isEmpty() ? "(no message)" : tail;
		}
	}

	private static Path partial(Path file) {
		return file.resolveSibling(file.getFileName() + ".partial");
	}
}
