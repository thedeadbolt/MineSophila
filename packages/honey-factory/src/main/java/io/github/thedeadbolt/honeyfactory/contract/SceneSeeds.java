package io.github.thedeadbolt.honeyfactory.contract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Independent layout, target and viewpoint seeds for one scene attempt ({@value #ALGORITHM}).
 *
 * <p>Each seed is the first 8 bytes, big-endian, of the SHA-256 of the UTF-8 text
 * {@code honey-factory/seed-v1/<purpose>/<dataset seed>/<scene index>/<attempt index>},
 * shifted right by 11 bits to a 53-bit value. No shared random stream is consumed, so a
 * rejected scene never changes another scene's seeds, and seeds stay exact JSON integers.
 */
public record SceneSeeds(long layout, long target, long viewpoint) {
	public static final String ALGORITHM = "seed-v1";
	public static final long MAX_SEED = (1L << 53) - 1;

	public static SceneSeeds derive(long datasetSeed, int sceneIndex, int attemptIndex) {
		return new SceneSeeds(
			derive("layout", datasetSeed, sceneIndex, attemptIndex),
			derive("target", datasetSeed, sceneIndex, attemptIndex),
			derive("viewpoint", datasetSeed, sceneIndex, attemptIndex)
		);
	}

	public static long derive(String purpose, long datasetSeed, int sceneIndex, int attemptIndex) {
		if (datasetSeed < 0 || datasetSeed > MAX_SEED || sceneIndex < 0 || attemptIndex < 0) {
			throw new IllegalArgumentException("Seed inputs out of range");
		}

		String text = "honey-factory/" + ALGORITHM + "/" + purpose + "/" + datasetSeed + "/" + sceneIndex + "/" + attemptIndex;
		return ByteBuffer.wrap(Sha256.digest(text.getBytes(StandardCharsets.UTF_8))).getLong() >>> 11;
	}
}
