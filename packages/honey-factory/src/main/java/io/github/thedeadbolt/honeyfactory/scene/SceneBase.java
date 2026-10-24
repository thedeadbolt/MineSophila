package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.Set;

/** What a recipe's scenes are built on. */
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

	/**
	 * A patch of the dataset world's normal Overworld copied into the arena
	 * ({@link SourcePatches}). Targets and cameras stay in the core; {@code contextPadding}
	 * blocks of terrain surround it on every horizontal side so no void edge shows, and
	 * {@code height} blocks are copied. A patch serves {@code scenesPerPatch} consecutive
	 * scenes, and the copied regions of any two patches lie at least
	 * {@code regionSeparationBlocks} apart. Horizontal sizes and the height are whole chunks
	 * or sections.
	 */
	record NaturalTerrain(int coreWidth, int coreLength, int height, int contextPadding, int scenesPerPatch, int regionSeparationBlocks) implements SceneBase {
		public static final String TYPE = "natural_patch";
		/** Initial defaults from the M6 benchmark; see the M6 findings. */
		public static final NaturalTerrain DEFAULT = new NaturalTerrain(32, 32, 128, 192, 8, 256);
		public static final int MAX_CORE = 256;
		public static final int MAX_PADDING = 512;

		public NaturalTerrain {
			requireMultiple("core_width", coreWidth, 16, 16, MAX_CORE);
			requireMultiple("core_length", coreLength, 16, 16, MAX_CORE);
			requireMultiple("height", height, 16, 32, 256);
			requireMultiple("context_padding", contextPadding, 16, 0, MAX_PADDING);
			requireMultiple("region_separation_blocks", regionSeparationBlocks, 32, 0, 4096);
			if (scenesPerPatch < 1 || scenesPerPatch > 10_000) {
				throw new IllegalArgumentException("scenes_per_patch must be 1-10000, got " + scenesPerPatch);
			}
		}

		private static void requireMultiple(String name, int value, int multiple, int min, int max) {
			if (value < min || value > max || value % multiple != 0) {
				throw new IllegalArgumentException(name + " must be a multiple of " + multiple + " in " + min + "-" + max + ", got " + value);
			}
		}

		/** The copied width, core plus padding on both sides. */
		public int copyWidth() {
			return this.coreWidth + 2 * this.contextPadding;
		}

		public int copyLength() {
			return this.coreLength + 2 * this.contextPadding;
		}

		@Override
		public JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("type", TYPE);
			json.addProperty("core_width", this.coreWidth);
			json.addProperty("core_length", this.coreLength);
			json.addProperty("height", this.height);
			json.addProperty("context_padding", this.contextPadding);
			json.addProperty("scenes_per_patch", this.scenesPerPatch);
			json.addProperty("region_separation_blocks", this.regionSeparationBlocks);
			return json;
		}

		static NaturalTerrain read(JsonFields fields) {
			fields.rejectUnknown(Set.of("type", "core_width", "core_length", "height", "context_padding", "scenes_per_patch", "region_separation_blocks"));
			try {
				return new NaturalTerrain(
					fields.integer32("core_width", 0, Integer.MAX_VALUE, DEFAULT.coreWidth()),
					fields.integer32("core_length", 0, Integer.MAX_VALUE, DEFAULT.coreLength()),
					fields.integer32("height", 0, Integer.MAX_VALUE, DEFAULT.height()),
					fields.integer32("context_padding", 0, Integer.MAX_VALUE, DEFAULT.contextPadding()),
					fields.integer32("scenes_per_patch", 0, Integer.MAX_VALUE, DEFAULT.scenesPerPatch()),
					fields.integer32("region_separation_blocks", 0, Integer.MAX_VALUE, DEFAULT.regionSeparationBlocks())
				);
			} catch (IllegalArgumentException e) {
				String message = e.getMessage();
				throw fields.error(message.substring(0, message.indexOf(' ')), message);
			}
		}
	}

	static SceneBase read(JsonFields fields) {
		String type = fields.string("type");
		if (type.equals(NaturalTerrain.TYPE)) {
			return NaturalTerrain.read(fields);
		}

		if (!type.equals(Platform.TYPE)) {
			throw fields.error("type", "unknown base '" + type + "'; expected " + Platform.TYPE + " or " + NaturalTerrain.TYPE);
		}

		fields.rejectUnknown(Set.of("type", "floor"));
		String floor = fields.string("floor", Platform.DEFAULT.floor());
		if (!BlockShapes.fullCube(floor)) {
			throw fields.error("floor", "must be a full-cube block state, got " + floor);
		}

		return new Platform(floor);
	}
}
