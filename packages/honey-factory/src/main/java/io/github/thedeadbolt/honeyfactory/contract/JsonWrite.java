package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/** Small helpers for building contract JSON. */
public final class JsonWrite {
	private JsonWrite() {
	}

	public static JsonArray array(int... values) {
		JsonArray array = new JsonArray();
		for (int value : values) {
			array.add(value);
		}

		return array;
	}

	public static JsonArray array(double... values) {
		JsonArray array = new JsonArray();
		for (double value : values) {
			array.add(value);
		}

		return array;
	}

	public static JsonArray strings(Collection<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	public static JsonElement nullable(@Nullable Number value) {
		return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
	}

	public static JsonElement nullable(@Nullable String value) {
		return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
	}

	public static JsonElement nullable(int @Nullable [] values) {
		return values == null ? JsonNull.INSTANCE : array(values);
	}

	public static JsonElement nullable(double @Nullable [] values) {
		return values == null ? JsonNull.INSTANCE : array(values);
	}
}
