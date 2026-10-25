package io.github.thedeadbolt.honeyfactory.control;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One step of the control loop: the commands offered, which one ran, the rates the body
 * achieved, and the resulting senses and true state. Step 0 is the reset and has no
 * commands. Senses and truth serialise separately, so a student receives only the senses.
 */
public record ControlStep(int index, @Nullable MotorCommand student, @Nullable MotorCommand teacher, Executed executed, MotorState achieved, BodySenses senses, TrueState truth) {
	/** Whose command the body executed. */
	public enum Executed {
		STUDENT,
		TEACHER;

		public String jsonName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** The command that ran; {@link MotorCommand#IDLE} at step 0. */
	public MotorCommand executedCommand() {
		MotorCommand command = this.executed == Executed.STUDENT ? this.student : this.teacher;
		return command == null ? MotorCommand.IDLE : command;
	}

	/** What a student may receive. */
	public JsonObject sensesJson() {
		return this.senses.toJson();
	}

	/** What a privileged connection and the logs also receive. */
	public JsonObject truthJson() {
		return this.truth.toJson();
	}

	/** Both commands, which one ran and the rates the body achieved. */
	public JsonObject commandsJson() {
		JsonObject json = new JsonObject();
		json.add("student", this.student == null ? JsonNull.INSTANCE : this.student.toJson());
		json.add("teacher", this.teacher == null ? JsonNull.INSTANCE : this.teacher.toJson());
		json.addProperty("executed", this.executed.jsonName());
		JsonObject achieved = new JsonObject();
		achieved.addProperty("yaw_rate", this.achieved.yawRate());
		achieved.addProperty("pitch_rate", this.achieved.pitchRate());
		json.add("achieved", achieved);
		return json;
	}
}
