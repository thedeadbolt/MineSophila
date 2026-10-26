package io.github.thedeadbolt.honeyfactory.contract;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.control.BodySenses;
import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One line of a clip's {@code frames.jsonl} (M12-2): the labels of one frame, all derived
 * from that frame's own capture and pose. Frame 0 is the reset at tick 0, sub-frame 0;
 * tick {@code t >= 1} has sub-frames 1 to {@code framesPerTick}, the last at the tick's end.
 * {@code tick} labels sit on frame 0 and on each tick's last frame.
 */
public record FrameRecord(
	String schemaVersion,
	String clipId,
	int frame,
	int tick,
	int subFrame,
	int framesPerTick,
	double partialTick,
	CameraPose pose,
	SampleRecord.Target target,
	boolean touchesEdge,
	@Nullable Bearing bearing,
	@Nullable TickLabels tickLabels
) {
	/** Masks and labels may be as wide as the largest image the contract allows; the video holds the real size. */
	private static final int MAX_SIDE = 65536;
	private static final double TOLERANCE = 1e-9;

	/**
	 * Where the honey lies from the body: degrees right of its facing, degrees above the
	 * horizon, and blocks from the eye to the honey's centre.
	 */
	public record Bearing(double azimuthDegrees, double elevationDegrees, double distanceBlocks) {
	}

	/** What the teacher believed of the honey's position when it decided this tick. */
	public record Belief(Source source, int @Nullable [] position, int observedTick) {
		public enum Source implements JsonEnum {
			/** Visible in the observed frame: the teacher used its true position. */
			SEEN,
			/** Not visible: the teacher used where it was last seen. */
			MEMORY,
			/** Not visible and never seen, or forgotten: no position. */
			NONE;

			public static Source fromJson(String name) {
				return JsonEnum.parse(Source.class, name);
			}
		}
	}

	/**
	 * One tick's labels. At tick 0 the commands, mode, belief and driver are null: nothing
	 * has been decided yet.
	 */
	public record TickLabels(
		long serverTick,
		double[] velocity,
		BodySenses senses,
		@Nullable MotorCommand executed,
		@Nullable MotorCommand teacher,
		@Nullable TeacherMode mode,
		@Nullable Belief belief,
		boolean drifting,
		@Nullable Driver driver,
		double coverage
	) {
	}

	/** The partial tick of sub-frame {@code subFrame}; the reset frame's is 1. */
	public static double partialTick(int subFrame, int framesPerTick) {
		return subFrame == 0 ? 1.0 : subFrame / (double) framesPerTick;
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("schema_version", this.schemaVersion);
		json.addProperty("clip_id", this.clipId);
		json.addProperty("frame", this.frame);
		json.addProperty("tick", this.tick);
		json.addProperty("sub_frame", this.subFrame);
		json.addProperty("frames_per_tick", this.framesPerTick);
		json.addProperty("partial_tick", this.partialTick);
		json.add("pose", ClipRecord.poseJson(this.pose));
		json.add("target", SampleRecord.targetJson(this.target));
		json.addProperty("touches_edge", this.touchesEdge);
		if (this.bearing == null) {
			json.add("bearing", JsonNull.INSTANCE);
		} else {
			JsonObject bearing = new JsonObject();
			bearing.addProperty("azimuth_degrees", this.bearing.azimuthDegrees());
			bearing.addProperty("elevation_degrees", this.bearing.elevationDegrees());
			bearing.addProperty("distance_blocks", this.bearing.distanceBlocks());
			json.add("bearing", bearing);
		}

		if (this.tickLabels != null) {
			TickLabels labels = this.tickLabels;
			JsonObject tick = new JsonObject();
			tick.addProperty("server_tick", labels.serverTick());
			tick.add("velocity", JsonWrite.array(labels.velocity()));
			tick.add("senses", labels.senses().toJson());
			tick.add("executed", labels.executed() == null ? JsonNull.INSTANCE : labels.executed().toJson());
			tick.add("teacher", labels.teacher() == null ? JsonNull.INSTANCE : labels.teacher().toJson());
			tick.add("teacher_mode", JsonWrite.nullable(labels.mode() == null ? null : labels.mode().jsonName()));
			if (labels.belief() == null) {
				tick.add("belief", JsonNull.INSTANCE);
			} else {
				JsonObject belief = new JsonObject();
				belief.addProperty("source", labels.belief().source().jsonName());
				belief.add("position", JsonWrite.nullable(labels.belief().position()));
				belief.addProperty("observed_tick", labels.belief().observedTick());
				tick.add("belief", belief);
			}

			tick.addProperty("drifting", labels.drifting());
			tick.add("driver", JsonWrite.nullable(labels.driver() == null ? null : labels.driver().jsonName()));
			tick.addProperty("coverage", labels.coverage());
			json.add("tick_labels", tick);
		}

		return json;
	}

	/** Parses and validates every single-record invariant of the contract. */
	public static FrameRecord fromJson(JsonObject json) {
		JsonFields fields = JsonFields.of(json);
		String version = SchemaVersion.require(fields);
		String clipId = fields.string("clip_id");
		if (!Ids.CLIP_ID.matcher(clipId).matches()) {
			throw fields.error("clip_id", "must match " + Ids.CLIP_ID.pattern());
		}

		int frame = fields.integer32("frame", 0, Integer.MAX_VALUE);
		int tick = fields.integer32("tick", 0, Integer.MAX_VALUE);
		int subFrame = fields.integer32("sub_frame", 0, 8);
		int framesPerTick = fields.integer32("frames_per_tick", 1, 8);
		double partialTick = fields.decimal("partial_tick", 0.0, 1.0);
		if (tick == 0) {
			if (subFrame != 0 || frame != 0 || partialTick != 1.0) {
				throw fields.error("sub_frame", "the reset frame is frame 0, sub-frame 0, partial tick 1");
			}
		} else {
			if (subFrame < 1 || subFrame > framesPerTick) {
				throw fields.error("sub_frame", "must lie in 1 to " + framesPerTick + " after the reset");
			}

			int expected = (tick - 1) * framesPerTick + subFrame;
			if (frame != expected) {
				throw fields.error("frame", "must be " + expected + " for tick " + tick + ", sub-frame " + subFrame);
			}

			if (Math.abs(partialTick - partialTick(subFrame, framesPerTick)) > TOLERANCE) {
				throw fields.error("partial_tick", "must be sub_frame / frames_per_tick (" + partialTick(subFrame, framesPerTick) + ")");
			}
		}

		CameraPose pose = ClipRecord.pose(fields.object("pose"));
		SampleRecord.Target target = SampleRecord.target(fields.object("target"), MAX_SIDE, MAX_SIDE);
		Bearing bearing = null;
		if (!fields.isNull("bearing")) {
			JsonFields value = fields.object("bearing");
			bearing = new Bearing(value.decimal("azimuth_degrees", -180.0, 180.0), value.decimal("elevation_degrees", -90.0, 90.0), value.decimal("distance_blocks", 0.0, 1.0e6));
		}

		if (target.targetPresent() == (bearing == null)) {
			throw fields.error("bearing", "is null exactly when the target is absent");
		}

		boolean labelled = subFrame == 0 || subFrame == framesPerTick;
		if (labelled != fields.has("tick_labels")) {
			throw fields.error("tick_labels", "appear exactly on the reset frame and each tick's last frame");
		}

		TickLabels labels = labelled ? tickLabels(fields.object("tick_labels"), tick) : null;
		return new FrameRecord(version, clipId, frame, tick, subFrame, framesPerTick, partialTick, pose, target, fields.bool("touches_edge"), bearing, labels);
	}

	private static TickLabels tickLabels(JsonFields labels, int tick) {
		MotorCommand executed = labels.isNull("executed") ? null : ClipRecord.command(labels.object("executed"));
		MotorCommand teacher = labels.isNull("teacher") ? null : ClipRecord.command(labels.object("teacher"));
		TeacherMode mode = labels.isNull("teacher_mode") ? null : labels.enumValue("teacher_mode", TeacherMode::fromJson);
		Belief belief = null;
		if (!labels.isNull("belief")) {
			JsonFields value = labels.object("belief");
			Belief.Source source = value.enumValue("source", Belief.Source::fromJson);
			int[] position = value.nullableIntegers("position", 3);
			if ((source == Belief.Source.NONE) != (position == null)) {
				throw value.error("position", "is null exactly when the source is none");
			}

			int observed = value.integer32("observed_tick", 0, Integer.MAX_VALUE);
			if (observed != tick - 1) {
				throw value.error("observed_tick", "must be " + (tick - 1) + ", the tick before");
			}

			belief = new Belief(source, position, observed);
		}

		Driver driver = labels.isNull("driver") ? null : labels.enumValue("driver", Driver::fromJson);
		boolean decided = executed != null && teacher != null && mode != null && belief != null && driver != null;
		boolean undecided = executed == null && teacher == null && mode == null && belief == null && driver == null;
		if (tick == 0 ? !undecided : !decided) {
			throw labels.error("executed", tick == 0 ? "the reset frame carries no commands, belief or driver" : "a later tick carries its commands, mode, belief and driver");
		}

		boolean drifting = labels.bool("drifting");
		if (driver == Driver.TEACHER_DRIFT && !drifting || driver == Driver.TEACHER && drifting) {
			throw labels.error("driver", "is teacher_drift exactly when the teacher drove a drifting tick");
		}

		return new TickLabels(labels.integer("server_tick", 0, Long.MAX_VALUE), labels.decimals("velocity", 3), senses(labels.object("senses")), executed, teacher, mode, belief,
			drifting, driver, labels.decimal("coverage", 0.0, 1.0));
	}

	private static BodySenses senses(JsonFields senses) {
		double any = Double.MAX_VALUE;
		return new BodySenses(
			senses.decimal("forward_speed", -any, any),
			senses.decimal("strafe_speed", -any, any),
			senses.decimal("vertical_speed", -any, any),
			senses.decimal("yaw_rate", -any, any),
			senses.decimal("pitch_rate", -any, any),
			(float) senses.decimal("pitch", -90.0, 90.0),
			senses.decimals("down", 3),
			senses.bool("on_ground"),
			senses.bool("bumped"),
			senses.bool("in_water"),
			ClipRecord.command(senses.object("last_command"))
		);
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "FrameRecord[%s frame %d, tick %d.%d]", this.clipId, this.frame, this.tick, this.subFrame);
	}
}
