package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;

/**
 * An inclusive integer range a recipe setting draws from, written as a number when
 * {@code min == max} and as {@code [min, max]} otherwise.
 */
public record IntRange(int min, int max) {
	public IntRange {
		if (min > max) {
			throw new IllegalArgumentException("Range minimum " + min + " exceeds its maximum " + max);
		}
	}

	public static IntRange of(int value) {
		return new IntRange(value, value);
	}

	/** {@code min + nextInt(max - min + 1)}; draws nothing when the range holds one value. */
	public int draw(SeededRandom random) {
		return this.min == this.max ? this.min : this.min + random.nextInt(this.max - this.min + 1);
	}

	public JsonElement toJson() {
		return this.min == this.max ? new JsonPrimitive(this.min) : JsonWrite.array(this.min, this.max);
	}

	/** Reads {@code name} as a number or a two-element range within {@code [lowest, highest]}, or {@code fallback} when absent. */
	public static IntRange read(JsonFields fields, String name, int lowest, int highest, IntRange fallback) {
		if (!fields.has(name)) {
			return fallback;
		}

		if (!fields.raw().get(name).isJsonArray()) {
			return of(fields.integer32(name, lowest, highest));
		}

		int[] bounds = fields.integers(name, 2);
		if (bounds[0] < lowest || bounds[1] > highest || bounds[0] > bounds[1]) {
			throw fields.error(name, "expected " + lowest + " <= min <= max <= " + highest + ", got [" + bounds[0] + ", " + bounds[1] + "]");
		}

		return new IntRange(bounds[0], bounds[1]);
	}
}
