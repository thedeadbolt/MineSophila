package io.github.thedeadbolt.honeyfactory.contract;

/**
 * Independent seeds for one clip attempt ({@value SceneSeeds#ALGORITHM}, M12-2): where the
 * clip starts, how its driver moves and when it drifts. The purposes are
 * {@code clip-start}, {@code clip-drive} and {@code clip-drift}; the attempt index is
 * {@code clipIndex * 1000 + attempt}, so a clip's redraws never touch another clip's seeds.
 */
public record ClipSeeds(long start, long drive, long drift) {
	/** Attempts one clip may take; the attempt index reserves this many per clip. */
	public static final int MAX_ATTEMPTS = 1000;

	public static ClipSeeds derive(long datasetSeed, int sceneIndex, int clipIndex, int attempt) {
		if (clipIndex < 0 || attempt < 0 || attempt >= MAX_ATTEMPTS) {
			throw new IllegalArgumentException("Clip seed inputs out of range");
		}

		int index = clipIndex * MAX_ATTEMPTS + attempt;
		return new ClipSeeds(
			SceneSeeds.derive("clip-start", datasetSeed, sceneIndex, index),
			SceneSeeds.derive("clip-drive", datasetSeed, sceneIndex, index),
			SceneSeeds.derive("clip-drift", datasetSeed, sceneIndex, index)
		);
	}
}
