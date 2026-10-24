package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An immutable scene recipe, as a generator runs it and a manifest records it: the preset
 * it started from, a base, the layout parts in build order, the viewpoint sight rule and
 * the lighting. Edit one through {@link #toRecipe()}.
 */
public record SceneSpec(String preset, SceneBase base, List<LayoutPart> parts, SightRule sight, Lighting lighting) {
	public SceneSpec {
		parts = List.copyOf(parts);
		if (base instanceof SceneBase.NaturalTerrain && !parts.isEmpty()) {
			throw new IllegalArgumentException("layout parts need a platform base; a natural_patch base takes none");
		}
	}

	public SceneRecipe toRecipe() {
		return new SceneRecipe(this);
	}

	/** {@code {preset, base, parts, sight, lighting}}, every setting explicit. */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("preset", this.preset);
		json.add("base", this.base.toJson());
		JsonArray parts = new JsonArray();
		this.parts.forEach(part -> parts.add(LayoutParts.toJson(part)));
		json.add("parts", parts);
		json.addProperty("sight", this.sight.jsonName());
		json.add("lighting", this.lighting.toJson());
		return json;
	}

	/**
	 * Reads a recipe: {@code preset} (default {@code defaultPreset}) supplies every setting,
	 * and any of {@code base}, {@code parts}, {@code sight} and {@code lighting} given
	 * replace the preset's. Lighting members not given keep the preset's.
	 */
	public static SceneSpec read(JsonFields fields, String defaultPreset) {
		fields.rejectUnknown(Set.of("preset", "base", "parts", "sight", "lighting"));
		String preset = fields.string("preset", defaultPreset);
		SceneSpec spec;
		try {
			spec = ScenePresets.spec(preset);
		} catch (IllegalArgumentException e) {
			throw fields.error("preset", e.getMessage());
		}

		SceneBase base = fields.has("base") ? SceneBase.read(fields.object("base")) : spec.base();
		List<LayoutPart> parts = spec.parts();
		if (fields.has("parts")) {
			parts = new ArrayList<>();
			for (JsonFields part : fields.objects("parts")) {
				parts.add(LayoutParts.read(part));
			}
		}

		SightRule sight = fields.has("sight") ? fields.enumValue("sight", SightRule::fromJson) : spec.sight();
		Lighting lighting = fields.has("lighting") ? Lighting.read(fields.object("lighting"), spec.lighting()) : spec.lighting();
		try {
			return new SceneSpec(preset, base, parts, sight, lighting);
		} catch (IllegalArgumentException e) {
			throw fields.error("parts", e.getMessage());
		}
	}
}
