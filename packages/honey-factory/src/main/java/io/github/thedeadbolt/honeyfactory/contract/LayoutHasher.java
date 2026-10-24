package io.github.thedeadbolt.honeyfactory.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streams the {@link BlockLayout} ({@value BlockLayout#ALGORITHM}) text into SHA-256 without
 * holding it, for volumes of millions of blocks. Blocks must arrive in layout order (y, then
 * z, then x), air omitted, states canonical.
 */
public final class LayoutHasher {
	private final MessageDigest digest;
	private final StringBuilder line = new StringBuilder(96);

	public LayoutHasher(int[] boundsMin, int[] boundsMax) {
		try {
			this.digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}

		this.line.append("honey-factory/").append(BlockLayout.ALGORITHM).append('\n')
			.append("bounds ").append(boundsMin[0]).append(' ').append(boundsMin[1]).append(' ').append(boundsMin[2]).append(' ')
			.append(boundsMax[0]).append(' ').append(boundsMax[1]).append(' ').append(boundsMax[2]).append('\n');
		flush();
	}

	public void block(int x, int y, int z, String canonicalState) {
		this.line.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(canonicalState).append('\n');
		flush();
	}

	public String finish() {
		return HexFormat.of().formatHex(this.digest.digest());
	}

	private void flush() {
		this.digest.update(this.line.toString().getBytes(StandardCharsets.UTF_8));
		this.line.setLength(0);
	}
}
