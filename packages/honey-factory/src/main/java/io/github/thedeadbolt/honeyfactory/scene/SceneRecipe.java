package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.Weather;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable scene settings: start from a preset, change what you need, then {@link #build()}
 * the immutable {@link SceneSpec} a generator runs. Setters return the recipe, so calls
 * chain:
 *
 * <pre>{@code
 * SceneSpec spec = SceneRecipe.of("level3")
 *     .lighting(Lighting.of(new IntRange(1000, 11000), Weather.CLEAR, Weather.RAIN))
 *     .addPart(NearAimBlocksPart.DEFAULT)
 *     .build();
 * }</pre>
 *
 * <p>{@link #parts()} is the live list, so parts can also be inserted, removed or reordered
 * directly. Each part draws from its own stream keyed by its position in that list.
 */
public final class SceneRecipe {
	private String preset;
	private SceneBase base;
	private final List<LayoutPart> parts;
	private SightRule sight;
	private Lighting lighting;

	SceneRecipe(SceneSpec spec) {
		this.preset = spec.preset();
		this.base = spec.base();
		this.parts = new ArrayList<>(spec.parts());
		this.sight = spec.sight();
		this.lighting = spec.lighting();
	}

	/** A copy of a named preset, such as {@code level3}; see {@link ScenePresets}. */
	public static SceneRecipe of(String name) {
		return ScenePresets.spec(name).toRecipe();
	}

	public String preset() {
		return this.preset;
	}

	/** Renames the recipe, for example after changing a preset beyond recognition. */
	public SceneRecipe preset(String name) {
		this.preset = name;
		return this;
	}

	public SceneBase base() {
		return this.base;
	}

	public SceneRecipe base(SceneBase base) {
		this.base = base;
		return this;
	}

	/** The parts in build order; edit the list directly. */
	public List<LayoutPart> parts() {
		return this.parts;
	}

	public SceneRecipe addPart(LayoutPart part) {
		this.parts.add(part);
		return this;
	}

	public SceneRecipe parts(List<LayoutPart> parts) {
		this.parts.clear();
		this.parts.addAll(parts);
		return this;
	}

	public SightRule sight() {
		return this.sight;
	}

	public SceneRecipe sight(SightRule sight) {
		this.sight = sight;
		return this;
	}

	public Lighting lighting() {
		return this.lighting;
	}

	public SceneRecipe lighting(Lighting lighting) {
		this.lighting = lighting;
		return this;
	}

	/** Fixed lighting at one time of day and weather. */
	public SceneRecipe lighting(int timeOfDayTicks, Weather weather) {
		return lighting(Lighting.fixed(timeOfDayTicks, weather));
	}

	/** An immutable snapshot; later edits to this recipe do not change it. */
	public SceneSpec build() {
		return new SceneSpec(this.preset, this.base, this.parts, this.sight, this.lighting);
	}
}
