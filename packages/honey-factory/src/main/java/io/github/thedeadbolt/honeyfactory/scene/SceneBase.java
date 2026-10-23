package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.Set;

/** What a recipe's layout is built on. */
public sealed interface SceneBase {
	JsonObject toJson();

	/** A flat floor of {@code floor} over the whole arena footprint; targets and viewpoints stand on it. */
	record Platform(String floor) implements SceneBase {
		public static final String TYPE = "platform";
		public static final Platform DEFAULT = new Platform("minecraft:light_gray_concrete");

		@Override
		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("type", TYPE);
			json.addProperty("floor", this.floor);
			return json;
		}
	}

	static SceneBase read(JsonFields fields) {
		String type = fields.string("type");
		if (!type.equals(Platform.TYPE)) {
			throw fields.error("type", "unknown base '" + type + "'; expected " + Platform.TYPE);
		}

		fields.rejectUnknown(Set.of("type", "floor"));
		String floor = fields.string("floor", Platform.DEFAULT.floor());
		if (!BlockShapes.fullCube(floor)) {
			throw fields.error("floor", "must be a full-cube block state, got " + floor);
		}

		return new Platform(floor);
	}
}
