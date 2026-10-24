package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.Set;

/**
 * Mixed items on {@link LayoutBuilder#freeCell free cells} at least {@code clearance} cells
 * from the aim ({@code scattered_items}, a level-4 part): clutter blocks, slabs, stairs,
 * fences, leaves or plants. Draws a material family, then {@code count} items.
 */
public record ScatteredItemsPart(IntRange count, int clearance) implements LayoutPart {
	public static final String TYPE = "scattered_items";
	public static final ScatteredItemsPart DEFAULT = new ScatteredItemsPart(new IntRange(3, 8), 2);

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		SeededRandom random = context.random();
		BlockPalettes.Family family = Constructions.family(random);
		int count = this.count.draw(random);
		for (int i = 0; i < count; i++) {
			int[] cell = context.layout().freeCell(random, this.clearance);
			if (cell != null) {
				Constructions.item(random, context.layout(), cell[0], cell[1], family);
			}
		}

		context.drawn().addProperty("material", family.full());
		context.drawn().addProperty("items", count);
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("count", this.count.toJson());
		json.addProperty("clearance", this.clearance);
		return json;
	}

	static ScatteredItemsPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "count", "clearance"));
		return new ScatteredItemsPart(IntRange.read(fields, "count", 0, 1000, DEFAULT.count()), fields.integer32("clearance", 0, 256, DEFAULT.clearance()));
	}
}
