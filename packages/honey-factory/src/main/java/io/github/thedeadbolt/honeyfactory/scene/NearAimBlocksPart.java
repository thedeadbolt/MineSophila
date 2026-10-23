package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Irregular blocks in distinct ground cells next to the aim ({@code near_aim_blocks}, a
 * level-4 part): a slab, a stair, a fence post, leaves, a plant or a full block capped with
 * a slab.
 *
 * <p>Draws a material family, then {@code count} (at most 8) items, each a neighbour drawn
 * without replacement from the eight and a kind {@code nextInt(6)}; an item whose cell is
 * taken is skipped.
 */
public record NearAimBlocksPart(IntRange count) implements LayoutPart {
	public static final String TYPE = "near_aim_blocks";
	public static final NearAimBlocksPart DEFAULT = new NearAimBlocksPart(new IntRange(1, 3));

	public NearAimBlocksPart {
		if (count.min() < 0 || count.max() > 8) {
			throw new IllegalArgumentException("The aim has eight neighbours; count must lie in 0-8, got " + count);
		}
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		SeededRandom random = context.random();
		LayoutBuilder layout = context.layout();
		int[] aim = context.aim();
		BlockPalettes.Family family = Constructions.family(random);
		List<int[]> neighbours = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					neighbours.add(new int[] {dx, dz});
				}
			}
		}

		int count = this.count.draw(random);
		int y = LayoutBuilder.GROUND_Y;
		for (int i = 0; i < count; i++) {
			int[] offset = neighbours.remove(random.nextInt(neighbours.size()));
			int x = aim[0] + offset[0];
			int z = aim[2] + offset[1];
			int kind = random.nextInt(6);
			if (!layout.empty(x, y, z)) {
				continue;
			}

			switch (kind) {
				case 0 -> layout.set(x, y, z, family.slab("bottom"));
				case 1 -> layout.set(x, y, z, family.stair(Constructions.FACINGS[random.nextInt(4)], random.nextInt(4) == 0));
				case 2 -> layout.set(x, y, z, BlockPalettes.pick(random, BlockPalettes.FENCES));
				case 3 -> layout.set(x, y, z, BlockPalettes.pick(random, BlockPalettes.LEAVES));
				case 4 -> layout.plant(x, z, BlockPalettes.pick(random, BlockPalettes.GROUND), BlockPalettes.pick(random, BlockPalettes.PLANTS));
				default -> {
					layout.set(x, y, z, family.full());
					layout.set(x, y + 1, z, family.slab("bottom"));
				}
			}
		}

		context.drawn().addProperty("material", family.full());
		context.drawn().addProperty("blocks", count);
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("count", this.count.toJson());
		return json;
	}

	static NearAimBlocksPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "count"));
		return new NearAimBlocksPart(IntRange.read(fields, "count", 0, 8, DEFAULT.count()));
	}
}
