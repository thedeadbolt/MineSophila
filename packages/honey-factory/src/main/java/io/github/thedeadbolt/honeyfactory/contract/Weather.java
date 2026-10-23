package io.github.thedeadbolt.honeyfactory.contract;

import java.util.Arrays;

/** Scene weather. */
public enum Weather {
	CLEAR("clear"),
	RAIN("rain"),
	THUNDER("thunder");

	private final String jsonName;

	Weather(String jsonName) {
		this.jsonName = jsonName;
	}

	public String jsonName() {
		return this.jsonName;
	}

	public static Weather fromJson(String name) {
		for (Weather value : values()) {
			if (value.jsonName.equals(name)) {
				return value;
			}
		}

		throw new IllegalArgumentException("unknown value '" + name + "'; expected one of " + Arrays.stream(values()).map(Weather::jsonName).toList());
	}
}
