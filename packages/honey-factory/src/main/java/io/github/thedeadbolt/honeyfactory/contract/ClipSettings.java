package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.clip.ClipCodec;
import io.github.thedeadbolt.honeyfactory.clip.ClipCodecs;
import io.github.thedeadbolt.honeyfactory.control.MotorLimits;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A clip dataset's {@code generator.clips} (M12-2): how clips are driven, labelled and
 * stored. It is part of the resolved configuration, so it enters the configuration hash.
 *
 * @param ticks the length of each clip type in ticks; an approach may end sooner, once it has
 *     reached the honey and lingered {@code approachLingerTicks}
 * @param mix relative weights of the clip types drawn for target-present scenes; a
 *     target-absent scene's clips are {@link ClipType#NO_HONEY}
 */
public record ClipSettings(
	int framesPerTick,
	int clipsPerScene,
	int attemptsPerClip,
	Map<ClipType, Integer> ticks,
	int approachLingerTicks,
	Map<ClipType, Double> mix,
	Drift drift,
	Teacher teacher,
	MotorLimits motor,
	ClipCodec codec
) {
	/** Clip types a target-present scene draws from, in mix order. */
	public static final List<ClipType> PRESENT_TYPES = List.of(ClipType.APPROACH, ClipType.PASS_BY, ClipType.SCAN, ClipType.OCCLUSION, ClipType.DETOUR, ClipType.SEARCH);

	/**
	 * The default RGB quality: lossy H.264 at CRF 18 kept the second pilot's frames above 46 dB
	 * PSNR while storing them 24–47 times smaller than lossless. Masks are always lossless.
	 */
	static final int DEFAULT_CRF = 18;

	/** A search's default length: time to find the honey, then walk to it. */
	static final int SEARCH_TICKS = 200;

	public ClipSettings {
		ticks = Map.copyOf(ticks);
		mix = Map.copyOf(mix);
	}

	/**
	 * Temporally correlated departures from the teacher's command.
	 *
	 * @param rate the expected share of ticks drifting, in [0, 1)
	 * @param meanTicks the mean length of one drift episode
	 * @param hazardFraction the share of episodes that push towards the nearest ledge or wall
	 *     within reach, so the data holds the teacher recovering near hazards
	 */
	public record Drift(double rate, double meanTicks, double maxTurnRate, double maxForward, double hazardFraction) {
		/** Drift without hazard episodes. */
		public Drift(double rate, double meanTicks, double maxTurnRate, double maxForward) {
			this(rate, meanTicks, maxTurnRate, maxForward, 0.0);
		}
	}

	/**
	 * @param minVisiblePixels the visible target pixels at or above which the teacher sees the honey
	 * @param reacquireTicks how long after losing the honey the teacher re-acquires before it searches
	 * @param reachDistanceBlocks how close, horizontally from the eye, counts as reaching the honey
	 */
	public record Teacher(int minVisiblePixels, int reacquireTicks, double reachDistanceBlocks) {
	}

	/** The M12-2 defaults for a clip dataset. */
	public static ClipSettings defaults() {
		Map<ClipType, Integer> ticks = new EnumMap<>(ClipType.class);
		for (ClipType type : ClipType.values()) {
			ticks.put(type, type == ClipType.SEARCH ? SEARCH_TICKS : type == ClipType.APPROACH || type == ClipType.DETOUR || type == ClipType.OCCLUSION ? 100 : 32);
		}

		// Scripted clips (scan, pass-by) are 15% of a dataset with 20% target-absent scenes; M13 decides by ablation whether to keep them.
		Map<ClipType, Double> mix = new EnumMap<>(ClipType.class);
		mix.put(ClipType.APPROACH, 0.30);
		mix.put(ClipType.PASS_BY, 0.075);
		mix.put(ClipType.SCAN, 0.075);
		mix.put(ClipType.OCCLUSION, 0.10);
		mix.put(ClipType.DETOUR, 0.15);
		mix.put(ClipType.SEARCH, 0.15);
		return new ClipSettings(3, 4, 20, ticks, 8, mix, new Drift(0.10, 6.0, 6.0, 0.5, 0.3), new Teacher(50, 60, 1.5), MotorLimits.DEFAULT, new ClipCodec.H264Lossy(DEFAULT_CRF));
	}

	/** The ticks a clip of {@code type} may run. */
	public int ticks(ClipType type) {
		return this.ticks.get(type);
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("frames_per_tick", this.framesPerTick);
		json.addProperty("clips_per_scene", this.clipsPerScene);
		json.addProperty("attempts_per_clip", this.attemptsPerClip);
		JsonObject ticks = new JsonObject();
		for (ClipType type : ClipType.values()) {
			ticks.addProperty(type.jsonName(), this.ticks.get(type));
		}

		json.add("ticks", ticks);
		json.addProperty("approach_linger_ticks", this.approachLingerTicks);
		JsonObject mix = new JsonObject();
		for (ClipType type : PRESENT_TYPES) {
			mix.addProperty(type.jsonName(), this.mix.get(type));
		}

		json.add("mix", mix);
		JsonObject drift = new JsonObject();
		drift.addProperty("rate", this.drift.rate());
		drift.addProperty("mean_ticks", this.drift.meanTicks());
		drift.addProperty("max_turn_rate", this.drift.maxTurnRate());
		drift.addProperty("max_forward", this.drift.maxForward());
		drift.addProperty("hazard_fraction", this.drift.hazardFraction());
		json.add("drift", drift);
		JsonObject teacher = new JsonObject();
		teacher.addProperty("min_visible_pixels", this.teacher.minVisiblePixels());
		teacher.addProperty("reacquire_ticks", this.teacher.reacquireTicks());
		teacher.addProperty("reach_distance_blocks", this.teacher.reachDistanceBlocks());
		json.add("teacher", teacher);
		JsonObject motor = new JsonObject();
		motor.addProperty("max_turn_rate", this.motor.maxTurnRate());
		motor.addProperty("max_turn_accel", this.motor.maxTurnAccel());
		motor.addProperty("max_pitch_rate", this.motor.maxPitchRate());
		motor.addProperty("max_pitch_accel", this.motor.maxPitchAccel());
		json.add("motor", motor);
		json.add("codec", this.codec.toJson());
		return json;
	}

	/**
	 * Parses and checks {@code generator.clips}.
	 *
	 * @throws ContractException naming the first member out of range
	 */
	public static ClipSettings fromJson(JsonFields clips) {
		JsonFields ticksFields = clips.object("ticks");
		Map<ClipType, Integer> ticks = new EnumMap<>(ClipType.class);
		for (ClipType type : ClipType.values()) {
			// Search came after the first clip datasets; theirs leave it out.
			ticks.put(type, type == ClipType.SEARCH && !ticksFields.has(type.jsonName()) ? SEARCH_TICKS : ticksFields.integer32(type.jsonName(), 1, 100_000));
		}

		JsonFields mixFields = clips.object("mix");
		Map<ClipType, Double> mix = new EnumMap<>(ClipType.class);
		double total = 0.0;
		for (ClipType type : PRESENT_TYPES) {
			double weight = type == ClipType.SEARCH && !mixFields.has(type.jsonName()) ? 0.0 : mixFields.decimal(type.jsonName(), 0.0, 1.0e6);
			mix.put(type, weight);
			total += weight;
		}

		if (!(total > 0.0)) {
			throw clips.error("mix", "needs a positive weight");
		}

		JsonFields drift = clips.object("drift");
		JsonFields teacher = clips.object("teacher");
		JsonFields motor = clips.object("motor");
		double tiny = Double.MIN_VALUE;
		ClipCodec codec;
		try {
			codec = ClipCodecs.fromJson(clips.object("codec").raw());
		} catch (IllegalArgumentException e) {
			throw clips.error("codec", e.getMessage());
		}

		if (clips.object("codec").has("crf") && !(codec instanceof ClipCodec.H264Lossy)) {
			throw clips.object("codec").error("crf", "applies to " + ClipCodec.H264Lossy.NAME + " only");
		}

		return new ClipSettings(
			clips.integer32("frames_per_tick", 1, 8),
			clips.integer32("clips_per_scene", 1, Ids.MAX_CLIPS),
			clips.integer32("attempts_per_clip", 1, ClipSeeds.MAX_ATTEMPTS - 1),
			ticks,
			clips.integer32("approach_linger_ticks", 0, 100_000),
			mix,
			new Drift(drift.decimal("rate", 0.0, Math.nextDown(1.0)), drift.decimal("mean_ticks", 1.0, 1.0e6), drift.decimal("max_turn_rate", 0.0, 1.0e6), drift.decimal("max_forward", 0.0, 1.0),
				drift.decimal("hazard_fraction", 0.0, 1.0)),
			new Teacher(teacher.integer32("min_visible_pixels", 0, Integer.MAX_VALUE), teacher.integer32("reacquire_ticks", 0, 100_000), teacher.decimal("reach_distance_blocks", tiny, 1.0e6)),
			new MotorLimits(motor.decimal("max_turn_rate", tiny, 1.0e6), motor.decimal("max_turn_accel", tiny, 1.0e6), motor.decimal("max_pitch_rate", tiny, 1.0e6), motor.decimal("max_pitch_accel", tiny, 1.0e6)),
			codec
		);
	}
}
