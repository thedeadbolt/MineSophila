package io.github.thedeadbolt.honeyfactory.contract;

import java.util.Arrays;
import java.util.Locale;

/**
 * An enum written to contract JSON as a fixed string, by default its constant name in
 * lower case.
 */
public interface JsonEnum {
	default String jsonName() {
		return ((Enum<?>) this).name().toLowerCase(Locale.ROOT);
	}

	/**
	 * The constant of {@code type} whose JSON name is {@code name}.
	 *
	 * @throws IllegalArgumentException naming the accepted values when there is none
	 */
	static <E extends Enum<E> & JsonEnum> E parse(Class<E> type, String name) {
		E[] values = type.getEnumConstants();
		for (E value : values) {
			if (value.jsonName().equals(name)) {
				return value;
			}
		}

		throw new IllegalArgumentException("unknown value '" + name + "'; expected one of " + Arrays.stream(values).map(JsonEnum::jsonName).toList());
	}
}
