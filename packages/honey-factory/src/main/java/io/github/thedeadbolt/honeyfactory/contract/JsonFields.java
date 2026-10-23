package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Typed, path-aware access to one JSON object. Every failure is a {@link ContractException}
 * naming the offending member, so callers report actionable errors without extra checks.
 */
public final class JsonFields {
	private final JsonObject object;
	private final String path;

	private JsonFields(JsonObject object, String path) {
		this.object = object;
		this.path = path;
	}

	public static JsonFields of(JsonObject object) {
		return new JsonFields(object, "");
	}

	/** Parses strict RFC 8259 JSON text whose top level is an object. */
	public static JsonObject parseObject(String text) {
		JsonReader reader = new JsonReader(new StringReader(text));
		reader.setStrictness(Strictness.STRICT);
		JsonElement element;
		try {
			element = JsonParser.parseReader(reader);
		} catch (JsonParseException e) {
			throw new ContractException("", "invalid JSON: " + e.getMessage());
		}

		if (!element.isJsonObject()) {
			throw new ContractException("", "expected a JSON object");
		}

		return element.getAsJsonObject();
	}

	public String path() {
		return this.path;
	}

	public String path(String name) {
		return this.path + "/" + name;
	}

	public ContractException error(String name, String message) {
		return new ContractException(path(name), message);
	}

	/** A copy of the underlying object, for members the contract leaves free-form. */
	public JsonObject raw() {
		return this.object.deepCopy();
	}

	public boolean has(String name) {
		return this.object.has(name);
	}

	/** Rejects members outside {@code allowed}; used for configuration input, not records. */
	public void rejectUnknown(Set<String> allowed) {
		for (String name : this.object.keySet()) {
			if (!allowed.contains(name)) {
				throw error(name, "unknown setting; expected one of " + allowed.stream().sorted().toList());
			}
		}
	}

	public JsonFields object(String name) {
		JsonElement element = require(name);
		if (!element.isJsonObject()) {
			throw error(name, "expected an object");
		}

		return new JsonFields(element.getAsJsonObject(), path(name));
	}

	/** Returns the nested object, or an empty one when the member is absent. */
	public JsonFields objectOrEmpty(String name) {
		return has(name) ? object(name) : new JsonFields(new JsonObject(), path(name));
	}

	public @Nullable JsonFields nullableObject(String name) {
		return isNull(name) ? null : object(name);
	}

	public String string(String name) {
		JsonElement element = require(name);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw error(name, "expected a string");
		}

		return element.getAsString();
	}

	public String string(String name, String defaultValue) {
		return has(name) ? string(name) : defaultValue;
	}

	/** A lowercase hexadecimal SHA-256 string. */
	public String sha256(String name) {
		String value = string(name);
		if (!Sha256.isHex(value)) {
			throw error(name, "must be 64 lowercase hexadecimal characters");
		}

		return value;
	}

	/** A namespaced ID such as {@code minecraft:overworld}. */
	public String namespacedId(String name) {
		String value = string(name);
		if (!Ids.NAMESPACED_ID.matcher(value).matches()) {
			throw error(name, "must be a namespaced ID such as minecraft:overworld");
		}

		return value;
	}

	public @Nullable String nullableString(String name) {
		return isNull(name) ? null : string(name);
	}

	public boolean bool(String name) {
		JsonElement element = require(name);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			throw error(name, "expected true or false");
		}

		return element.getAsBoolean();
	}

	public boolean bool(String name, boolean defaultValue) {
		return has(name) ? bool(name) : defaultValue;
	}

	public long integer(String name, long min, long max) {
		BigDecimal value = number(name);
		try {
			long result = value.longValueExact();
			if (result < min || result > max) {
				throw error(name, "must be between " + min + " and " + max + ", got " + result);
			}

			return result;
		} catch (ArithmeticException e) {
			throw error(name, "expected an integer, got " + value);
		}
	}

	public long integer(String name, long min, long max, long defaultValue) {
		return has(name) ? integer(name, min, max) : defaultValue;
	}

	public int integer32(String name, int min, int max) {
		return (int) integer(name, min, max);
	}

	public int integer32(String name, int min, int max, int defaultValue) {
		return has(name) ? integer32(name, min, max) : defaultValue;
	}

	public double decimal(String name, double min, double max) {
		double value = number(name).doubleValue();
		if (!Double.isFinite(value) || value < min || value > max) {
			throw error(name, "must be between " + min + " and " + max + ", got " + value);
		}

		return value;
	}

	public double decimal(String name, double min, double max, double defaultValue) {
		return has(name) ? decimal(name, min, max) : defaultValue;
	}

	public @Nullable Double nullableDecimal(String name, double min, double max) {
		return isNull(name) ? null : decimal(name, min, max);
	}

	public <E extends Enum<E>> E enumValue(String name, Function<String, E> parser) {
		String value = string(name);
		try {
			return parser.apply(value);
		} catch (IllegalArgumentException e) {
			throw error(name, e.getMessage());
		}
	}

	public JsonArray array(String name) {
		JsonElement element = require(name);
		if (!element.isJsonArray()) {
			throw error(name, "expected an array");
		}

		return element.getAsJsonArray();
	}

	public List<JsonFields> objects(String name) {
		JsonArray array = array(name);
		List<JsonFields> result = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			if (!array.get(i).isJsonObject()) {
				throw new ContractException(path(name) + "/" + i, "expected an object");
			}

			result.add(new JsonFields(array.get(i).getAsJsonObject(), path(name) + "/" + i));
		}

		return result;
	}

	public List<String> strings(String name) {
		JsonArray array = array(name);
		List<String> result = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			JsonElement element = array.get(i);
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
				throw new ContractException(path(name) + "/" + i, "expected a string");
			}

			result.add(element.getAsString());
		}

		return result;
	}

	public int[] integers(String name, int length) {
		JsonArray array = fixedArray(name, length);
		int[] result = new int[length];
		for (int i = 0; i < length; i++) {
			BigDecimal value = numberAt(name, array, i);
			try {
				result[i] = value.intValueExact();
			} catch (ArithmeticException e) {
				throw new ContractException(path(name) + "/" + i, "expected an integer, got " + value);
			}
		}

		return result;
	}

	public int @Nullable [] nullableIntegers(String name, int length) {
		return isNull(name) ? null : integers(name, length);
	}

	public double[] decimals(String name, int length) {
		JsonArray array = fixedArray(name, length);
		double[] result = new double[length];
		for (int i = 0; i < length; i++) {
			result[i] = numberAt(name, array, i).doubleValue();
		}

		return result;
	}

	public double @Nullable [] nullableDecimals(String name, int length) {
		return isNull(name) ? null : decimals(name, length);
	}

	/** True when the member is present and JSON {@code null}; absent members are an error. */
	public boolean isNull(String name) {
		return require(name).isJsonNull();
	}

	private JsonElement require(String name) {
		JsonElement element = this.object.get(name);
		if (element == null) {
			throw error(name, "is required");
		}

		return element;
	}

	private BigDecimal number(String name) {
		JsonElement element = require(name);
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			throw error(name, "expected a number");
		}

		return element.getAsBigDecimal();
	}

	private JsonArray fixedArray(String name, int length) {
		JsonArray array = array(name);
		if (array.size() != length) {
			throw error(name, "expected " + length + " items, got " + array.size());
		}

		return array;
	}

	private BigDecimal numberAt(String name, JsonArray array, int index) {
		JsonElement element = array.get(index);
		if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
			throw new ContractException(path(name) + "/" + index, "expected a number");
		}

		BigDecimal value = primitive.getAsBigDecimal();
		if (!Double.isFinite(value.doubleValue())) {
			throw new ContractException(path(name) + "/" + index, "expected a finite number");
		}

		return value;
	}
}
