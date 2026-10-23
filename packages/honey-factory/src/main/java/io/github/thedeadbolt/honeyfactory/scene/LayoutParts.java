package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The registry of layout part types by name. A part's JSON is its {@code type} plus its
 * settings; omitted settings take the type's defaults.
 */
public final class LayoutParts {
	private static final Map<String, Function<JsonFields, LayoutPart>> TYPES = new LinkedHashMap<>();

	static {
		register(ClutterPart.TYPE, ClutterPart::read);
		register(HidingStructurePart.TYPE, HidingStructurePart::read);
		register(BackgroundStructuresPart.TYPE, BackgroundStructuresPart::read);
		register(ScatteredBlocksPart.TYPE, ScatteredBlocksPart::read);
		register(ConstructedSettingPart.TYPE, ConstructedSettingPart::read);
		register(NearAimBlocksPart.TYPE, NearAimBlocksPart::read);
		register(ScatteredItemsPart.TYPE, ScatteredItemsPart::read);
	}

	private LayoutParts() {
	}

	/** Adds a part type; {@code reader} parses its JSON, settings beside {@code type}. */
	public static synchronized void register(String type, Function<JsonFields, LayoutPart> reader) {
		if (TYPES.putIfAbsent(type, reader) != null) {
			throw new IllegalArgumentException("Layout part type " + type + " is already registered");
		}
	}

	public static synchronized Set<String> types() {
		return Set.copyOf(TYPES.keySet());
	}

	/** @throws io.github.thedeadbolt.honeyfactory.contract.ContractException for an unknown type or invalid settings */
	public static synchronized LayoutPart read(JsonFields fields) {
		String type = fields.string("type");
		Function<JsonFields, LayoutPart> reader = TYPES.get(type);
		if (reader == null) {
			throw fields.error("type", "unknown layout part '" + type + "'; expected one of " + TYPES.keySet());
		}

		try {
			return reader.apply(fields);
		} catch (IllegalArgumentException e) {
			throw fields.error("type", e.getMessage());
		}
	}

	/** {@code {"type": ..., settings...}}. */
	public static JsonObject toJson(LayoutPart part) {
		JsonObject json = new JsonObject();
		json.addProperty("type", part.type());
		part.settingsJson().entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
		return json;
	}
}
