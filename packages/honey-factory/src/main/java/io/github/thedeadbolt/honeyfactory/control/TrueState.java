package io.github.thedeadbolt.honeyfactory.control;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import io.github.thedeadbolt.honeyfactory.contract.SampleRecord;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;

/**
 * The world as it is at one step, for teachers and logs and never for a student: the
 * body's absolute pose and velocity, the scene, and the target as the masks show it. A
 * target cut by the frame edge is a valid state here, marked by {@code targetTouchesEdge}.
 */
public record TrueState(CameraPose pose, double[] velocity, String layoutSha256, long seed, long serverTick, SampleRecord.Target target, boolean targetTouchesEdge) {
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.add("position", JsonWrite.array(this.pose.x(), this.pose.y(), this.pose.z()));
		json.addProperty("yaw", this.pose.yawDegrees());
		json.addProperty("pitch", this.pose.pitchDegrees());
		json.add("velocity", JsonWrite.array(this.velocity));
		json.addProperty("layout_sha256", this.layoutSha256);
		json.addProperty("seed", this.seed);
		json.addProperty("server_tick", this.serverTick);
		json.add("target", SampleRecord.targetJson(this.target));
		json.addProperty("target_touches_edge", this.targetTouchesEdge);
		return json;
	}
}
