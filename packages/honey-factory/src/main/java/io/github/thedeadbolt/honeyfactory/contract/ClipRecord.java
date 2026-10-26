package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.thedeadbolt.honeyfactory.clip.ClipCodec;
import io.github.thedeadbolt.honeyfactory.clip.ClipCodecs;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One line of {@code clips.jsonl} (M12-2): a motion clip of a scene, its seeds and start,
 * the commands that drove it, and its videos and frame records. The clip replays from its
 * scene and {@code executed}.
 */
public record ClipRecord(
	String schemaVersion,
	String clipId,
	String sceneId,
	Split split,
	int clipIndex,
	ClipType requestedType,
	ClipType clipType,
	int attempt,
	ClipSeeds seeds,
	CameraPose start,
	int framesPerTick,
	int tickCount,
	int frameCount,
	EndReason endReason,
	ClipCodec codec,
	VideoFile rgb,
	VideoFile visibleMask,
	DataFile frames,
	List<MotorCommand> executed,
	Summary summary
) {
	public static final String VIDEO_MEDIA_TYPE = "video/x-matroska";
	public static final String FRAMES_MEDIA_TYPE = "application/jsonl";
	public static final String RGB_FILE = "rgb.mkv";
	public static final String MASK_FILE = "visible.mkv";
	public static final String FRAMES_FILE = "frames.jsonl";

	public ClipRecord {
		executed = List.copyOf(executed);
	}

	/** Why a clip ended: it ran its length, or an approach reached the honey and lingered. */
	public enum EndReason implements JsonEnum {
		LENGTH,
		REACHED;

		public static EndReason fromJson(String name) {
			return JsonEnum.parse(EndReason.class, name);
		}
	}

	public record VideoFile(String path, int width, int height, int frames, long bytes, String sha256) {
	}

	public record DataFile(String path, long bytes, String sha256) {
	}

	/**
	 * @param reached whether an approach clip reached the honey; null for other types
	 * @param coverageFinal the share of reachable cells the teacher had seen at the end
	 * @param ticksByDriver ticks by who chose the executed command (see {@link Driver})
	 * @param guardedTicks ticks whose executed command the edge guard stopped short of a ledge
	 */
	public record Summary(int driftTicks, @Nullable Boolean reached, double coverageFinal, Map<TeacherMode, Integer> ticksByMode, Map<Driver, Integer> ticksByDriver,
		int guardedTicks) {
		public Summary {
			ticksByMode = Map.copyOf(ticksByMode);
			ticksByDriver = Map.copyOf(ticksByDriver);
		}
	}

	/** The frames a clip of {@code ticks} ticks holds: the reset frame and {@code framesPerTick} per tick. */
	public static int frameCount(int ticks, int framesPerTick) {
		return 1 + ticks * framesPerTick;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", this.schemaVersion);
		json.addProperty("clip_id", this.clipId);
		json.addProperty("scene_id", this.sceneId);
		json.addProperty("split", this.split.jsonName());
		json.addProperty("clip_index", this.clipIndex);
		json.addProperty("requested_type", this.requestedType.jsonName());
		json.addProperty("clip_type", this.clipType.jsonName());
		json.addProperty("attempt", this.attempt);
		JsonObject seeds = new JsonObject();
		seeds.addProperty("start", this.seeds.start());
		seeds.addProperty("drive", this.seeds.drive());
		seeds.addProperty("drift", this.seeds.drift());
		json.add("seeds", seeds);
		json.add("start_pose", poseJson(this.start));
		json.addProperty("frames_per_tick", this.framesPerTick);
		json.addProperty("tick_count", this.tickCount);
		json.addProperty("frame_count", this.frameCount);
		json.addProperty("end_reason", this.endReason.jsonName());
		json.add("codec", this.codec.toJson());
		JsonObject files = new JsonObject();
		files.add("rgb", videoJson(this.rgb));
		files.add("visible_mask", videoJson(this.visibleMask));
		JsonObject frames = new JsonObject();
		frames.addProperty("path", this.frames.path());
		frames.addProperty("media_type", FRAMES_MEDIA_TYPE);
		frames.addProperty("bytes", this.frames.bytes());
		frames.addProperty("sha256", this.frames.sha256());
		files.add("frames", frames);
		json.add("files", files);
		JsonArray executed = new JsonArray();
		this.executed.forEach(command -> executed.add(command.toJson()));
		json.add("executed", executed);
		JsonObject summary = new JsonObject();
		summary.addProperty("drift_ticks", this.summary.driftTicks());
		summary.add("reached", this.summary.reached() == null ? JsonNull.INSTANCE : new JsonPrimitive(this.summary.reached()));
		summary.addProperty("coverage_final", this.summary.coverageFinal());
		JsonObject modes = new JsonObject();
		for (TeacherMode mode : TeacherMode.values()) {
			modes.addProperty(mode.jsonName(), this.summary.ticksByMode().getOrDefault(mode, 0));
		}

		summary.add("ticks_by_mode", modes);
		JsonObject drivers = new JsonObject();
		for (Driver driver : Driver.values()) {
			drivers.addProperty(driver.jsonName(), this.summary.ticksByDriver().getOrDefault(driver, 0));
		}

		summary.add("ticks_by_driver", drivers);
		summary.addProperty("guarded_ticks", this.summary.guardedTicks());
		json.add("summary", summary);
		return json;
	}

	/** The contract's pose object: feet position and Minecraft yaw and pitch. */
	public static JsonObject poseJson(CameraPose pose) {
		JsonObject json = new JsonObject();
		json.add("position", JsonWrite.array(pose.x(), pose.y(), pose.z()));
		json.addProperty("yaw_degrees", pose.yawDegrees());
		json.addProperty("pitch_degrees", pose.pitchDegrees());
		return json;
	}

	static CameraPose pose(JsonFields pose) {
		double[] position = pose.decimals("position", 3);
		return new CameraPose(position[0], position[1], position[2], (float) pose.decimal("yaw_degrees", -180.0, 180.0), (float) pose.decimal("pitch_degrees", -90.0, 90.0));
	}

	/** A motor command record; members a later minor version adds are tolerated. */
	static MotorCommand command(JsonFields command) {
		int delay = command.integer32("delay_ticks", 0, 0);
		return new MotorCommand(command.decimal("forward", -1.0, 1.0), command.decimal("strafe", -1.0, 1.0), command.decimal("turn_rate", -Double.MAX_VALUE, Double.MAX_VALUE),
			command.decimal("pitch_rate", -Double.MAX_VALUE, Double.MAX_VALUE), command.bool("jump"), delay);
	}

	private static JsonObject videoJson(VideoFile file) {
		JsonObject json = new JsonObject();
		json.addProperty("path", file.path());
		json.addProperty("media_type", VIDEO_MEDIA_TYPE);
		json.addProperty("width", file.width());
		json.addProperty("height", file.height());
		json.addProperty("frames", file.frames());
		json.addProperty("bytes", file.bytes());
		json.addProperty("sha256", file.sha256());
		return json;
	}

	/** Parses and validates every single-record invariant of the contract. */
	public static ClipRecord fromJson(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		String version = SchemaVersion.require(fields);
		String clipId = fields.string("clip_id");
		if (!Ids.CLIP_ID.matcher(clipId).matches()) {
			throw fields.error("clip_id", "must match " + Ids.CLIP_ID.pattern());
		}

		String sceneId = fields.string("scene_id");
		if (!Ids.SCENE_ID.matcher(sceneId).matches()) {
			throw fields.error("scene_id", "must match " + Ids.SCENE_ID.pattern());
		}

		Split split = fields.enumValue("split", Split::fromJson);
		int clipIndex = fields.integer32("clip_index", 0, Ids.MAX_CLIPS);
		if (!clipId.equals(Ids.clipId(sceneId, clipIndex))) {
			throw fields.error("clip_id", "must be " + Ids.clipId(sceneId, clipIndex) + " for this scene and clip index");
		}

		ClipType requested = fields.enumValue("requested_type", ClipType::fromJson);
		ClipType clipType = fields.enumValue("clip_type", ClipType::fromJson);
		if (requested != clipType && !((requested == ClipType.OCCLUSION || requested == ClipType.DETOUR) && clipType == ClipType.APPROACH)) {
			throw fields.error("clip_type", "may differ from requested_type only as an occlusion or detour clip falling back to approach");
		}

		int attempt = fields.integer32("attempt", 0, ClipSeeds.MAX_ATTEMPTS - 1);
		JsonFields seeds = fields.object("seeds");
		ClipSeeds clipSeeds = new ClipSeeds(seeds.integer("start", 0, SceneSeeds.MAX_SEED), seeds.integer("drive", 0, SceneSeeds.MAX_SEED), seeds.integer("drift", 0, SceneSeeds.MAX_SEED));
		CameraPose start = pose(fields.object("start_pose"));
		int framesPerTick = fields.integer32("frames_per_tick", 1, 8);
		int ticks = fields.integer32("tick_count", 1, Integer.MAX_VALUE / 8);
		int frameCount = fields.integer32("frame_count", 2, Integer.MAX_VALUE);
		if (frameCount != frameCount(ticks, framesPerTick)) {
			throw fields.error("frame_count", "must be " + frameCount(ticks, framesPerTick) + ", one reset frame plus ticks x frames per tick");
		}

		EndReason endReason = fields.enumValue("end_reason", EndReason::fromJson);
		ClipCodec codec;
		try {
			codec = ClipCodecs.fromJson(fields.object("codec").raw());
		} catch (IllegalArgumentException e) {
			throw fields.error("codec", e.getMessage());
		}

		String directory = Ids.clipDirectory(split, clipId);
		JsonFields files = fields.object("files");
		VideoFile rgb = video(files, "rgb", directory + "/" + RGB_FILE, frameCount);
		VideoFile mask = video(files, "visible_mask", directory + "/" + MASK_FILE, frameCount);
		if (rgb.width() != mask.width() || rgb.height() != mask.height()) {
			throw files.error("visible_mask", "dimensions differ from the RGB video");
		}

		JsonFields frameFile = files.object("frames");
		if (!frameFile.string("path").equals(directory + "/" + FRAMES_FILE)) {
			throw frameFile.error("path", "must be " + directory + "/" + FRAMES_FILE);
		}

		DataFile frames = new DataFile(frameFile.string("path"), frameFile.integer("bytes", 1, Long.MAX_VALUE), frameFile.sha256("sha256"));
		List<MotorCommand> executed = new ArrayList<>();
		for (JsonFields command : fields.objects("executed")) {
			executed.add(command(command));
		}

		if (executed.size() != ticks) {
			throw fields.error("executed", "needs one command per tick (" + ticks + ")");
		}

		JsonFields summary = fields.object("summary");
		int driftTicks = summary.integer32("drift_ticks", 0, ticks);
		Boolean reached = summary.isNull("reached") ? null : summary.bool("reached");
		if ((clipType == ClipType.APPROACH) != (reached != null)) {
			throw summary.error("reached", "is recorded for approach clips only");
		}

		if (endReason == EndReason.REACHED && !Boolean.TRUE.equals(reached)) {
			throw fields.error("end_reason", "a reached end needs an approach clip that reached the honey");
		}

		JsonFields modes = summary.object("ticks_by_mode");
		Map<TeacherMode, Integer> ticksByMode = new EnumMap<>(TeacherMode.class);
		int total = 0;
		for (TeacherMode mode : TeacherMode.values()) {
			int count = modes.integer32(mode.jsonName(), 0, ticks);
			ticksByMode.put(mode, count);
			total += count;
		}

		if (total != ticks) {
			throw summary.error("ticks_by_mode", "must add up to the tick count");
		}

		JsonFields drivers = summary.object("ticks_by_driver");
		Map<Driver, Integer> ticksByDriver = new EnumMap<>(Driver.class);
		int driven = 0;
		for (Driver driver : Driver.values()) {
			int count = drivers.integer32(driver.jsonName(), 0, ticks);
			ticksByDriver.put(driver, count);
			driven += count;
		}

		if (driven != ticks) {
			throw summary.error("ticks_by_driver", "must add up to the tick count");
		}

		int scripted = ticksByDriver.get(Driver.SCRIPT);
		boolean scriptedType = clipType == ClipType.SCAN || clipType == ClipType.PASS_BY;
		if (scriptedType && scripted != ticks || !scriptedType && clipType != ClipType.OCCLUSION && scripted != 0) {
			throw summary.error("ticks_by_driver", "a script drives every tick of scan and pass-by clips, part of an occlusion clip, and no tick of others");
		}

		return new ClipRecord(version, clipId, sceneId, split, clipIndex, requested, clipType, attempt, clipSeeds, start, framesPerTick, ticks, frameCount, endReason, codec, rgb, mask, frames,
			executed, new Summary(driftTicks, reached, summary.decimal("coverage_final", 0.0, 1.0), ticksByMode, ticksByDriver, summary.integer32("guarded_ticks", 0, ticks)));
	}

	private static VideoFile video(JsonFields files, String name, String path, int frameCount) {
		JsonFields file = files.object(name);
		if (!file.string("path").equals(path)) {
			throw file.error("path", "must be " + path);
		}

		if (!VIDEO_MEDIA_TYPE.equals(file.string("media_type"))) {
			throw file.error("media_type", "must be " + VIDEO_MEDIA_TYPE);
		}

		int frames = file.integer32("frames", 1, Integer.MAX_VALUE);
		if (frames != frameCount) {
			throw file.error("frames", "must hold all " + frameCount + " frames");
		}

		return new VideoFile(path, file.integer32("width", 1, 65536), file.integer32("height", 1, 65536), frames, file.integer("bytes", 1, Long.MAX_VALUE), file.sha256("sha256"));
	}
}
