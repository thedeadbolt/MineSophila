package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.Sha256;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A portable deterministic stream for scene generators, independent of any JDK random
 * number generator. Block {@code n} is the SHA-256 of the UTF-8 text {@code <key>/<n>};
 * each block yields four big-endian 64-bit words, shifted right by 11 bits to 53-bit
 * values. Generators derive one stream per independent decision, keyed by purpose, seed
 * and index, so a rejected draw never shifts another.
 */
public final class SeededRandom {
	private static final double UNIT = 0x1.0p-53;

	private final String key;
	private long block;
	private ByteBuffer words = ByteBuffer.allocate(0);

	public SeededRandom(String key) {
		this.key = key;
	}

	/** The next value in {@code [0, 2^53)}. */
	public long nextLong53() {
		if (!this.words.hasRemaining()) {
			this.words = ByteBuffer.wrap(Sha256.digest((this.key + "/" + this.block++).getBytes(StandardCharsets.UTF_8)));
		}

		return this.words.getLong() >>> 11;
	}

	/** Uniform in {@code [0, 1)}. */
	public double nextDouble() {
		return nextLong53() * UNIT;
	}

	/** Uniform in {@code [min, max)}; {@code min} when the range is empty. */
	public double uniform(double min, double max) {
		return min + (max - min) * nextDouble();
	}

	/** Uniform integer in {@code [0, bound)}, without modulo bias. */
	public int nextInt(int bound) {
		if (bound <= 0) {
			throw new IllegalArgumentException("Bound must be positive: " + bound);
		}

		long limit = (1L << 53) - (1L << 53) % bound;
		long value;
		do {
			value = nextLong53();
		} while (value >= limit);

		return (int) (value % bound);
	}
}
