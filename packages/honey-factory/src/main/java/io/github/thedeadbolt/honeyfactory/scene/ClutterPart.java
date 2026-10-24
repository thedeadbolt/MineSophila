package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scattered single blocks and small piles, many yellow or orange, none near the target
 * ({@code clutter}, the level-2 layout).
 *
 * <p>Draws {@code items} items. Each draws a material ({@link BlockPalettes#clutter}), a
 * kind (single block or pile, even odds) and a base {@link LayoutBuilder#freeCell free cell}
 * at least {@code clearance} cells from the aim; an item without one is skipped. A pile
 * grows {@code 1 + nextInt(3)} more cells, each a step in one of four directions from a
 * random cell of the pile, kept only when free. Every pile cell is a column
 * {@code 1 + nextInt(2)} blocks tall; a single block is one block.
 */
public record ClutterPart(IntRange items, int clearance) implements LayoutPart {
	public static final String TYPE = "clutter";
	public static final ClutterPart DEFAULT = new ClutterPart(new IntRange(6, 14), 2);
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		LayoutBuilder layout = context.layout();
		SeededRandom random = context.random();
		int items = this.items.draw(random);
		int piles = 0;
		int distractors = 0;
		for (int item = 0; item < items; item++) {
			String material = BlockPalettes.clutter(random);
			boolean pile = random.nextInt(2) == 0;
			int[] base = layout.freeCell(random, this.clearance);
			if (base == null) {
				continue;
			}

			distractors += BlockPalettes.DISTRACTORS.contains(material) ? 1 : 0;
			if (!pile) {
				layout.column(base[0], base[1], 1, material);
				continue;
			}

			piles++;
			List<int[]> cells = new ArrayList<>(List.of(base));
			int extra = 1 + random.nextInt(3);
			for (int i = 0; i < extra; i++) {
				int[] from = cells.get(random.nextInt(cells.size()));
				int[] step = STEPS[random.nextInt(STEPS.length)];
				int[] cell = {from[0] + step[0], from[1] + step[1]};
				if (layout.free(cell[0], cell[1], this.clearance) && cells.stream().noneMatch(c -> c[0] == cell[0] && c[1] == cell[1])) {
					cells.add(cell);
				}
			}

			for (int[] cell : cells) {
				layout.column(cell[0], cell[1], 1 + random.nextInt(2), material);
			}
		}

		context.drawn().addProperty("items", items);
		context.drawn().addProperty("piles", piles);
		context.drawn().addProperty("distractor_items", distractors);
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("items", this.items.toJson());
		json.addProperty("clearance", this.clearance);
		return json;
	}

	static ClutterPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "items", "clearance"));
		return new ClutterPart(IntRange.read(fields, "items", 0, 1000, DEFAULT.items()), fields.integer32("clearance", 0, 256, DEFAULT.clearance()));
	}
}
