package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.Set;

/**
 * Walls, pillars and stacks of full blocks away from the target ({@code background_structures},
 * the second level-3 part).
 *
 * <p>Draws {@code count} structures, each a material from {@link BlockPalettes#STRUCTURES},
 * a kind and a base {@link LayoutBuilder#freeCell free cell} at least {@code clearance} cells
 * from the aim. A wall runs {@code 2 + nextInt(4)} cells along +X or +Z and is
 * {@code 1 + nextInt(3)} tall; a pillar is {@code 2 + nextInt(3)} tall; a stack covers
 * 2 × 2 cells, each {@code 1 + nextInt(2)} tall. Cells that are not free are skipped.
 */
public record BackgroundStructuresPart(IntRange count, int clearance) implements LayoutPart {
	public static final String TYPE = "background_structures";
	public static final BackgroundStructuresPart DEFAULT = new BackgroundStructuresPart(new IntRange(3, 6), 3);
	private static final String[] KINDS = {"wall", "pillar", "stack"};

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		SeededRandom random = context.random();
		LayoutBuilder layout = context.layout();
		int count = this.count.draw(random);
		for (int i = 0; i < count; i++) {
			String material = BlockPalettes.pick(random, BlockPalettes.STRUCTURES);
			String kind = KINDS[random.nextInt(KINDS.length)];
			int[] base = layout.freeCell(random, this.clearance);
			if (base == null) {
				continue;
			}

			switch (kind) {
				case "wall" -> {
					boolean alongX = random.nextInt(2) == 0;
					int length = 2 + random.nextInt(4);
					int height = 1 + random.nextInt(3);
					for (int j = 0; j < length; j++) {
						int x = base[0] + (alongX ? j : 0);
						int z = base[1] + (alongX ? 0 : j);
						if (layout.free(x, z, this.clearance)) {
							layout.column(x, z, height, material);
						}
					}
				}
				case "pillar" -> layout.column(base[0], base[1], 2 + random.nextInt(3), material);
				case "stack" -> {
					for (int dx = 0; dx < 2; dx++) {
						for (int dz = 0; dz < 2; dz++) {
							int height = 1 + random.nextInt(2);
							if (layout.free(base[0] + dx, base[1] + dz, this.clearance)) {
								layout.column(base[0] + dx, base[1] + dz, height, material);
							}
						}
					}
				}
				default -> throw new IllegalStateException("Unknown background structure " + kind);
			}
		}

		context.drawn().addProperty("structures", count);
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("count", this.count.toJson());
		json.addProperty("clearance", this.clearance);
		return json;
	}

	static BackgroundStructuresPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "count", "clearance"));
		return new BackgroundStructuresPart(IntRange.read(fields, "count", 0, 1000, DEFAULT.count()), fields.integer32("clearance", 0, 256, DEFAULT.clearance()));
	}
}
