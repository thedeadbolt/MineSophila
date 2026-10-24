package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.List;
import java.util.Set;

/**
 * A recipe's lighting: each scene draws its time of day uniformly from {@code timeOfDayTicks}
 * and its weather uniformly from {@code weathers}. A single time and weather make the
 * lighting fixed, and then nothing is drawn. Rain and thunder show random precipitation
 * particles, so their RGB images are not byte-reproducible; masks are unaffected.
 */
public record Lighting(IntRange timeOfDayTicks, List<Weather> weathers) {
	public static final int MAX_TIME_OF_DAY_TICKS = 23_999;
	/** Noon in clear weather. */
	public static final Lighting DEFAULT = fixed(6000, Weather.CLEAR);

	public Lighting {
		weathers = List.copyOf(weathers);
		if (weathers.isEmpty()) {
			throw new IllegalArgumentException("Lighting needs at least one weather");
		}

		if (timeOfDayTicks.min() < 0 || timeOfDayTicks.max() > MAX_TIME_OF_DAY_TICKS) {
			throw new IllegalArgumentException("Times of day are 0-" + MAX_TIME_OF_DAY_TICKS + " ticks, got " + timeOfDayTicks);
		}
	}

	/** Times drawn from {@code timeOfDayTicks}, weather from {@code weathers}. */
	public static Lighting of(IntRange timeOfDayTicks, Weather... weathers) {
		return new Lighting(timeOfDayTicks, List.of(weathers));
	}

	public static Lighting fixed(int timeOfDayTicks, Weather weather) {
		return new Lighting(IntRange.of(timeOfDayTicks), List.of(weather));
	}

	/** One scene's time and weather. */
	public record Drawn(int timeOfDayTicks, Weather weather) {
	}

	/** Draws the time, then the weather; a setting with one value draws nothing. */
	public Drawn draw(SeededRandom random) {
		int time = this.timeOfDayTicks.draw(random);
		Weather weather = this.weathers.size() == 1 ? this.weathers.getFirst() : this.weathers.get(random.nextInt(this.weathers.size()));
		return new Drawn(time, weather);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.add("time_of_day_ticks", this.timeOfDayTicks.toJson());
		json.add("weathers", JsonWrite.strings(this.weathers.stream().map(Weather::jsonName).toList()));
		return json;
	}

	/**
	 * Reads {@code time_of_day_ticks} (a number or {@code [min, max]}) and either
	 * {@code weathers} (a list) or the single {@code weather}; absent members keep
	 * {@code fallback}'s.
	 */
	public static Lighting read(JsonFields fields, Lighting fallback) {
		fields.rejectUnknown(Set.of("time_of_day_ticks", "weather", "weathers"));
		IntRange time = IntRange.read(fields, "time_of_day_ticks", 0, MAX_TIME_OF_DAY_TICKS, fallback.timeOfDayTicks());
		List<Weather> weathers = fallback.weathers();
		if (fields.has("weather") && fields.has("weathers")) {
			throw fields.error("weather", "give either weather or weathers");
		}

		if (fields.has("weather")) {
			weathers = List.of(fields.enumValue("weather", Weather::fromJson));
		} else if (fields.has("weathers")) {
			List<String> names = fields.strings("weathers");
			if (names.isEmpty() || Set.copyOf(names).size() != names.size()) {
				throw fields.error("weathers", "must list at least one weather, each once");
			}

			try {
				weathers = names.stream().map(Weather::fromJson).toList();
			} catch (IllegalArgumentException e) {
				throw fields.error("weathers", e.getMessage());
			}
		}

		return new Lighting(time, weathers);
	}
}
