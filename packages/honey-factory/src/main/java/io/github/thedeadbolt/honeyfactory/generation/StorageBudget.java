package io.github.thedeadbolt.honeyfactory.generation;

import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Disk space of one run. Before it starts, the free space under the output root must hold
 * the uncompressed size of every requested image plus the configured reserve; PNG
 * compression only makes the real output smaller. While it runs, each scene attempt
 * starts only if one scene's uncompressed images still fit above the reserve.
 */
public final class StorageBudget {
	/** Usable bytes on the file system holding {@code path}, which exists. */
	@FunctionalInterface
	public interface DiskSpace {
		DiskSpace SYSTEM = path -> Files.getFileStore(path).getUsableSpace();

		long usableBytes(Path path) throws IOException;
	}

	private final DiskSpace disk;
	private final Path outputRoot;
	private final long reserveBytes;
	private final long sceneBytes;
	private final long datasetBytes;

	StorageBudget(ResolvedConfig config, DiskSpace disk) {
		this.disk = disk;
		this.outputRoot = config.outputRoot();
		this.reserveBytes = config.output().freeSpaceReserveBytes();
		ResolvedConfig.Capture capture = config.capture();
		// Uncompressed 8-bit RGB plus the 8-bit mask of every viewpoint.
		this.sceneBytes = (long) capture.width() * capture.height() * 4 * config.viewpoints().count();
		this.datasetBytes = this.sceneBytes * config.quotas().acceptedScenes();
	}

	/** @throws IOException if the whole dataset, uncompressed, would cross the reserve */
	void preflight() throws IOException {
		long usable = usable();
		if (usable - this.datasetBytes < this.reserveBytes) {
			throw new IOException("Not enough free space under " + this.outputRoot + ": " + usable + " bytes usable, but the dataset needs up to "
				+ this.datasetBytes + " bytes and output.free_space_reserve_bytes keeps " + this.reserveBytes + " free");
		}
	}

	/** Why the next scene attempt must not start, or null when one more scene still fits. */
	@Nullable String exhausted() throws IOException {
		long usable = usable();
		return usable - this.sceneBytes < this.reserveBytes ? usable + " bytes usable, reserve " + this.reserveBytes : null;
	}

	private long usable() throws IOException {
		Path existing = this.outputRoot;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}

		if (existing == null) {
			throw new IOException("No existing directory above " + this.outputRoot);
		}

		return this.disk.usableBytes(existing);
	}
}
