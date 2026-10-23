package io.github.thedeadbolt.honeyfactory.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Lowercase hexadecimal SHA-256, the contract's only hash algorithm. */
public final class Sha256 {
	public static final String ALGORITHM = "sha256";

	private Sha256() {
	}

	public static byte[] digest(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	public static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(digest(bytes));
	}

	public static boolean isHex(String value) {
		return value.length() == 64 && value.chars().allMatch(c -> c >= '0' && c <= '9' || c >= 'a' && c <= 'f');
	}
}
