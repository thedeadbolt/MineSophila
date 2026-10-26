package io.github.thedeadbolt.honeyfactory.clip;

import io.github.thedeadbolt.honeyfactory.capture.CapturedImages;
import io.github.thedeadbolt.honeyfactory.contract.ClipRecord;
import io.github.thedeadbolt.honeyfactory.contract.FrameRecord;
import io.github.thedeadbolt.honeyfactory.contract.Ids;
import io.github.thedeadbolt.honeyfactory.contract.Jsonl;
import io.github.thedeadbolt.honeyfactory.contract.Split;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * Writes one clip: the RGB and visible-mask videos and {@code frames.jsonl}, into
 * {@code <clip folder>.partial/} until {@link #finish}, which renames the folder into place.
 * Frames are encoded on a worker thread; at most {@value #QUEUED_FRAMES} wait, so a slow
 * encoder holds the caller back instead of filling memory.
 */
public final class ClipWriter {
	static final int QUEUED_FRAMES = 8;

	/** The finished clip's files, as its record describes them. */
	public record Written(ClipRecord.VideoFile rgb, ClipRecord.VideoFile visibleMask, ClipRecord.DataFile frames) {
	}

	private final Path root;
	private final String directory;
	private final Path partial;
	private final int width;
	private final int height;
	private final ClipVideo rgb;
	private final ClipVideo mask;
	private final StringBuilder frames = new StringBuilder();
	private final ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Honey Factory clip encoder");
		thread.setDaemon(true);
		return thread;
	});
	private final Semaphore queued = new Semaphore(QUEUED_FRAMES);
	private CompletableFuture<Void> written = CompletableFuture.completedFuture(null);
	private int count;
	private boolean closed;

	private ClipWriter(Path root, String directory, int width, int height, ClipCodec codec) throws IOException {
		this.root = root;
		this.directory = directory;
		this.partial = root.resolve(directory + ".partial");
		this.width = width;
		this.height = height;
		Files.createDirectories(this.partial);
		this.rgb = ClipVideo.rgb(this.partial.resolve(ClipRecord.RGB_FILE), width, height, codec);
		ClipVideo started;
		try {
			started = ClipVideo.gray(this.partial.resolve(ClipRecord.MASK_FILE), width, height, ClipCodecs.maskCodecFor(codec));
		} catch (IOException | RuntimeException e) {
			this.rgb.abort();
			deletePartial();
			throw e;
		}

		this.mask = started;
	}

	/** Starts clip {@code clipId} of {@code split} in the dataset at {@code root}. */
	public static ClipWriter open(Path root, Split split, String clipId, int width, int height, ClipCodec codec) throws IOException {
		return new ClipWriter(root, Ids.clipDirectory(split, clipId), width, height, codec);
	}

	/** Queues one frame's images for encoding and records its labels. */
	public void frame(CapturedImages images, FrameRecord record) throws IOException {
		if (images.width() != this.width || images.height() != this.height) {
			throw new IllegalArgumentException("clip frames are " + this.width + "x" + this.height + ", got " + images.width() + "x" + images.height());
		}

		if (record.frame() != this.count) {
			throw new IllegalArgumentException("expected frame " + this.count + ", got " + record.frame());
		}

		this.queued.acquireUninterruptibly();
		this.written = this.written.thenRunAsync(() -> {
			try {
				this.rgb.write(images.rgb());
				this.mask.write(images.visibleMask());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} finally {
				this.queued.release();
			}
		}, this.encoder);
		this.frames.append(Jsonl.line(record.toJson()));
		this.count++;
	}

	/** Frames recorded so far. */
	public int frames() {
		return this.count;
	}

	/**
	 * Waits for the encoders, writes the frame records and moves the clip into place.
	 *
	 * @throws IOException when encoding failed; the clip is then removed
	 */
	public Written finish() throws IOException {
		this.closed = true;
		try {
			this.written.join();
			this.rgb.close();
			this.mask.close();
			Files.writeString(this.partial.resolve(ClipRecord.FRAMES_FILE), this.frames, StandardCharsets.UTF_8);
			Path target = this.root.resolve(this.directory);
			Files.createDirectories(target.getParent());
			Files.move(this.partial, target, StandardCopyOption.ATOMIC_MOVE);
			String base = this.directory + "/";
			return new Written(
				video(base + ClipRecord.RGB_FILE),
				video(base + ClipRecord.MASK_FILE),
				new ClipRecord.DataFile(base + ClipRecord.FRAMES_FILE, size(base + ClipRecord.FRAMES_FILE), sha256(base + ClipRecord.FRAMES_FILE))
			);
		} catch (CompletionException e) {
			abort();
			if (e.getCause() instanceof UncheckedIOException io) {
				throw io.getCause();
			}

			throw e;
		} catch (IOException | RuntimeException e) {
			abort();
			throw e;
		} finally {
			this.encoder.shutdown();
		}
	}

	/** Stops the encoders and removes everything this clip wrote. */
	public void abort() throws IOException {
		this.closed = true;
		this.encoder.shutdownNow();
		this.rgb.abort();
		this.mask.abort();
		deletePartial();
	}

	public boolean closed() {
		return this.closed;
	}

	private ClipRecord.VideoFile video(String path) throws IOException {
		return new ClipRecord.VideoFile(path, this.width, this.height, this.count, size(path), sha256(path));
	}

	private long size(String path) throws IOException {
		return Files.size(this.root.resolve(path));
	}

	/** SHA-256 of a dataset file, streamed: videos are too large to hold in memory twice. */
	public static String sha256(Path file) throws IOException {
		try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
			in.transferTo(OutputStream.nullOutputStream());
			return HexFormat.of().formatHex(in.getMessageDigest().digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private String sha256(String path) throws IOException {
		return sha256(this.root.resolve(path));
	}

	/** Removes this clip's own partial folder only. */
	private void deletePartial() throws IOException {
		if (!Files.exists(this.partial)) {
			return;
		}

		try (Stream<Path> files = Files.walk(this.partial)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		}
	}
}
