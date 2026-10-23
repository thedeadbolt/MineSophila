package io.github.thedeadbolt.honeyfactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Applies the {@code set}/{@code remove} JSON Pointer operations used by shared fixtures. */
public final class JsonPatch {
	private JsonPatch() {
	}

	public static JsonObject apply(JsonObject document, JsonArray operations) {
		JsonObject result = document.deepCopy();
		for (JsonElement element : operations) {
			JsonObject operation = element.getAsJsonObject();
			String[] tokens = operation.get("path").getAsString().substring(1).split("/", -1);
			JsonElement parent = result;
			for (int i = 0; i < tokens.length - 1; i++) {
				parent = child(parent, unescape(tokens[i]));
			}

			String last = unescape(tokens[tokens.length - 1]);
			boolean set = operation.get("op").getAsString().equals("set");
			if (parent instanceof JsonObject object) {
				if (set) {
					object.add(last, operation.get("value").deepCopy());
				} else if (object.remove(last) == null) {
					throw new IllegalArgumentException("No member to remove at " + operation.get("path"));
				}
			} else {
				JsonArray array = parent.getAsJsonArray();
				if (set) {
					array.set(Integer.parseInt(last), operation.get("value").deepCopy());
				} else {
					array.remove(Integer.parseInt(last));
				}
			}
		}

		return result;
	}

	private static JsonElement child(JsonElement parent, String token) {
		JsonElement child = parent.isJsonObject() ? parent.getAsJsonObject().get(token) : parent.getAsJsonArray().get(Integer.parseInt(token));
		if (child == null) {
			throw new IllegalArgumentException("No member " + token);
		}

		return child;
	}

	private static String unescape(String token) {
		return token.replace("~1", "/").replace("~0", "~");
	}
}
