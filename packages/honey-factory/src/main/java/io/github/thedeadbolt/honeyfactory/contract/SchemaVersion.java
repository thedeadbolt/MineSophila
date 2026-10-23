package io.github.thedeadbolt.honeyfactory.contract;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic {@code schema_version} of every record. Readers reject another major version
 * and tolerate unknown members from compatible minor versions.
 */
public final class SchemaVersion {
	public static final int MAJOR = 1;
	public static final int MINOR = 0;
	public static final String CURRENT = MAJOR + "." + MINOR;

	private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

	private SchemaVersion() {
	}

	public static String require(JsonFields fields) {
		String version = fields.string("schema_version");
		Matcher matcher = VERSION.matcher(version);
		if (!matcher.matches()) {
			throw fields.error("schema_version", "expected MAJOR.MINOR, got '" + version + "'");
		}

		if (Integer.parseInt(matcher.group(1)) != MAJOR) {
			throw fields.error("schema_version", "unsupported major version " + version + "; this reader supports " + MAJOR + ".x");
		}

		return version;
	}
}
