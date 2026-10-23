package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS) serialization, the canonical form hashed
 * for resolved configurations ({@value #ALGORITHM}).
 *
 * <p>Object members are sorted by UTF-16 code units, strings use the minimal JSON
 * escapes and numbers use the ECMAScript shortest round-trip format. Every number is
 * interpreted as an IEEE double, so integer literals above 2^53 - 1 cannot be
 * canonicalized exactly and are rejected.
 */
public final class CanonicalJson {
	public static final String ALGORITHM = "jcs-sha256-v1";

	private static final double MAX_SAFE_INTEGER = 9007199254740991.0;
	private static final Pattern INTEGER_LITERAL = Pattern.compile("-?[0-9]+");

	private CanonicalJson() {
	}

	public static String serialize(JsonElement element) {
		StringBuilder out = new StringBuilder();
		write(element, out);
		return out.toString();
	}

	public static String sha256(JsonElement element) {
		return Sha256.hex(serialize(element).getBytes(StandardCharsets.UTF_8));
	}

	private static void write(JsonElement element, StringBuilder out) {
		if (element == null || element.isJsonNull()) {
			out.append("null");
		} else if (element instanceof JsonObject object) {
			List<Map.Entry<String, JsonElement>> members = new ArrayList<>(object.entrySet());
			members.sort(Map.Entry.comparingByKey());
			out.append('{');
			for (int i = 0; i < members.size(); i++) {
				if (i > 0) {
					out.append(',');
				}

				writeString(members.get(i).getKey(), out);
				out.append(':');
				write(members.get(i).getValue(), out);
			}

			out.append('}');
		} else if (element instanceof JsonArray array) {
			out.append('[');
			for (int i = 0; i < array.size(); i++) {
				if (i > 0) {
					out.append(',');
				}

				write(array.get(i), out);
			}

			out.append(']');
		} else {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				out.append(primitive.getAsBoolean());
			} else if (primitive.isNumber()) {
				out.append(formatNumber(primitive.getAsNumber().toString()));
			} else {
				writeString(primitive.getAsString(), out);
			}
		}
	}

	private static void writeString(String value, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}

		out.append('"');
	}

	/** Formats a JSON number as ECMAScript {@code Number.prototype.toString} would. */
	static String formatNumber(String literal) {
		double value = new BigDecimal(literal).doubleValue();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("JSON number is not a finite double: " + literal);
		}

		if (INTEGER_LITERAL.matcher(literal).matches() && Math.abs(value) > MAX_SAFE_INTEGER) {
			throw new IllegalArgumentException("Integer exceeds 2^53 - 1 and cannot be canonicalized: " + literal);
		}

		if (value == 0.0) {
			return "0";
		}

		BigDecimal shortest = shortestRoundTrip(Math.abs(value));
		String digits = shortest.unscaledValue().toString();
		int k = digits.length();
		int n = k - shortest.scale();
		StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
		if (k <= n && n <= 21) {
			out.append(digits).append("0".repeat(n - k));
		} else if (0 < n && n <= 21) {
			out.append(digits, 0, n).append('.').append(digits, n, k);
		} else if (-6 < n && n <= 0) {
			out.append("0.").append("0".repeat(-n)).append(digits);
		} else {
			int exponent = n - 1;
			out.append(digits.charAt(0));
			if (k > 1) {
				out.append('.').append(digits, 1, k);
			}

			out.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
		}

		return out.toString();
	}

	/**
	 * The decimal with the fewest significant digits that parses back to {@code value},
	 * nearest to it among those. {@link Double#toString} is not enough: it always prints
	 * two digits, so it renders {@link Double#MIN_VALUE} as 4.9E-324 rather than 5E-324.
	 */
	private static BigDecimal shortestRoundTrip(double value) {
		BigDecimal exact = new BigDecimal(value);
		for (int digits = 1; digits <= 17; digits++) {
			BigDecimal candidate = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
			if (candidate.doubleValue() == value) {
				return candidate.stripTrailingZeros();
			}
		}

		throw new IllegalStateException("No 17-digit decimal round-trips " + value);
	}
}
