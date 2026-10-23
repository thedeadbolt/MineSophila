package io.github.thedeadbolt.honeyfactory.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Named starting recipes, one per difficulty level. Every preset stands on the default
 * platform with noon, clear lighting; they differ in parts and sight rule.
 *
 * <table>
 *   <tr><th>Preset</th><th>Parts</th><th>Sight rule</th></tr>
 *   <tr><td>{@code level1}</td><td>none</td><td>{@code any}</td></tr>
 *   <tr><td>{@code level2}</td><td>{@code clutter}</td><td>{@code all_clear}</td></tr>
 *   <tr><td>{@code level3}</td><td>{@code hiding_structure}, {@code background_structures},
 *       {@code scattered_blocks}</td><td>{@code partly_hidden}</td></tr>
 *   <tr><td>{@code level4}</td><td>{@code constructed_setting}, {@code near_aim_blocks},
 *       {@code scattered_items}</td><td>{@code clear_and_obstructed}</td></tr>
 * </table>
 */
public final class ScenePresets {
	private static final Map<String, SceneSpec> PRESETS = new LinkedHashMap<>();

	static {
		add("level1", List.of(), SightRule.ANY);
		add("level2", List.of(ClutterPart.DEFAULT), SightRule.ALL_CLEAR);
		add("level3", List.of(HidingStructurePart.DEFAULT, BackgroundStructuresPart.DEFAULT, ScatteredBlocksPart.DEFAULT), SightRule.PARTLY_HIDDEN);
		add("level4", List.of(ConstructedSettingPart.DEFAULT, NearAimBlocksPart.DEFAULT, ScatteredItemsPart.DEFAULT), SightRule.CLEAR_AND_OBSTRUCTED);
	}

	private ScenePresets() {
	}

	private static void add(String name, List<LayoutPart> parts, SightRule sight) {
		PRESETS.put(name, new SceneSpec(name, SceneBase.Platform.DEFAULT, parts, sight, Lighting.DEFAULT));
	}

	public static Set<String> names() {
		return Set.copyOf(PRESETS.keySet());
	}

	/** The preset for a difficulty level, such as {@code level3}. */
	public static String forLevel(int difficulty) {
		return "level" + difficulty;
	}

	/** @throws IllegalArgumentException for an unknown name */
	public static SceneSpec spec(String name) {
		SceneSpec spec = PRESETS.get(name);
		if (spec == null) {
			throw new IllegalArgumentException("unknown preset '" + name + "'; expected one of " + PRESETS.keySet());
		}

		return spec;
	}
}
