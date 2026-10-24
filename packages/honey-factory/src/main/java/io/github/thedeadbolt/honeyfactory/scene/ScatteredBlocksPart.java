package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.Set;

/**
 * Single clutter blocks ({@link BlockPalettes#clutter}) on {@link LayoutBuilder#freeCell free
 * cells} at least {@code clearance} cells from the aim ({@code scattered_blocks}, the last
 * level-3 part).
 */
public record ScatteredBlocksPart(IntRange count, int clearance) implements LayoutPart {
	public static final String TYPE = "scattered_blocks";
	public static final ScatteredBlocksPart DEFAULT = new ScatteredBlocksPart(new IntRange(2, 6), 3);

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		SeededRandom random = context.random();
		int count = this.count.draw(random);
		for (int i = 0; i < count; i++) {
			String block = BlockPalettes.clutter(random);
			int[] cell = context.layout().freeCell(random, this.clearance);
			if (cell != null) {
				context.layout().column(cell[0], cell[1], 1, block);
			}
		}

		context.drawn().addProperty("blocks", count);
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("count", this.count.toJson());
		json.addProperty("clearance", this.clearance);
		return json;
	}

	static ScatteredBlocksPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "count", "clearance"));
		return new ScatteredBlocksPart(IntRange.read(fields, "count", 0, 1000, DEFAULT.count()), fields.integer32("clearance", 0, 256, DEFAULT.clearance()));
	}
}
