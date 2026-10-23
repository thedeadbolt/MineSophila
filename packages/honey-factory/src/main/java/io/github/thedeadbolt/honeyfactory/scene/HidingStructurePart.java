package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A structure of full blocks built against the aim cell to hide part of the target
 * ({@code hiding_structure}, the first level-3 part).
 *
 * <p>Draws a material from {@link BlockPalettes#STRUCTURES}, a kind from {@code kinds} and a
 * side {@code s = nextInt(4)} (+X, +Z, -X, -Z), then builds in the cells next to the aim:
 *
 * <ul>
 *   <li>{@code wall}: {@code 2 + nextInt(3)} cells along side {@code s}, starting
 *       {@code nextInt(length)} cells before the cell beside the aim, so it always covers
 *       that cell; {@code 1 + nextInt(2)} blocks tall.</li>
 *   <li>{@code corner}: the cells beside the aim on sides {@code s} and {@code s+1} and the
 *       diagonal between them; {@code 1 + nextInt(2)} blocks tall.</li>
 *   <li>{@code recess}: all eight neighbours except the three on side {@code s}, two blocks
 *       tall, with a lintel above the aim cell when {@code nextInt(2) == 0}.</li>
 *   <li>{@code pillars}: {@code 2 + nextInt(2)} distinct neighbours, drawn from the eight,
 *       each {@code 2 + nextInt(2)} blocks tall.</li>
 * </ul>
 */
public record HidingStructurePart(List<String> kinds) implements LayoutPart {
	public static final String TYPE = "hiding_structure";
	public static final List<String> KINDS = List.of("wall", "corner", "recess", "pillars");
	public static final HidingStructurePart DEFAULT = new HidingStructurePart(KINDS);
	/** Side directions in {@code nextInt(4)} order. */
	static final int[][] SIDES = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

	public HidingStructurePart {
		kinds = requireKinds(kinds, KINDS);
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public void build(Context context) {
		SeededRandom random = context.random();
		LayoutBuilder layout = context.layout();
		JsonObject drawn = context.drawn();
		String material = BlockPalettes.pick(random, BlockPalettes.STRUCTURES);
		String kind = this.kinds.get(random.nextInt(this.kinds.size()));
		int side = random.nextInt(SIDES.length);
		drawn.addProperty("structure", kind);
		drawn.addProperty("structure_side", side);
		drawn.addProperty("structure_material", material);
		int[] aim = context.aim();
		int[] out = SIDES[side];
		int[] along = SIDES[(side + 1) % 4];
		int x = aim[0];
		int z = aim[2];
		switch (kind) {
			case "wall" -> {
				int length = 2 + random.nextInt(3);
				int start = -random.nextInt(length);
				int height = 1 + random.nextInt(2);
				for (int i = start; i < start + length; i++) {
					layout.column(x + out[0] + along[0] * i, z + out[1] + along[1] * i, height, material);
				}

				drawn.addProperty("structure_length", length);
				drawn.addProperty("structure_height", height);
			}
			case "corner" -> {
				int height = 1 + random.nextInt(2);
				layout.column(x + out[0], z + out[1], height, material);
				layout.column(x + along[0], z + along[1], height, material);
				layout.column(x + out[0] + along[0], z + out[1] + along[1], height, material);
				drawn.addProperty("structure_height", height);
			}
			case "recess" -> {
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						boolean openSide = dx * out[0] + dz * out[1] == 1;
						if ((dx != 0 || dz != 0) && !openSide) {
							layout.column(x + dx, z + dz, 2, material);
						}
					}
				}

				boolean lintel = random.nextInt(2) == 0;
				if (lintel) {
					layout.column(x, z, aim[1] + 1, 1, material);
				}

				drawn.addProperty("structure_lintel", lintel);
			}
			case "pillars" -> {
				List<int[]> neighbours = new ArrayList<>();
				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx != 0 || dz != 0) {
							neighbours.add(new int[] {dx, dz});
						}
					}
				}

				int count = 2 + random.nextInt(2);
				for (int i = 0; i < count; i++) {
					int[] offset = neighbours.remove(random.nextInt(neighbours.size()));
					layout.column(x + offset[0], z + offset[1], 2 + random.nextInt(2), material);
				}

				drawn.addProperty("structure_count", count);
			}
			default -> throw new IllegalStateException("Unknown hiding structure " + kind);
		}
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("kinds", JsonWrite.strings(this.kinds));
		return json;
	}

	static HidingStructurePart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "kinds"));
		try {
			return new HidingStructurePart(fields.has("kinds") ? fields.strings("kinds") : KINDS);
		} catch (IllegalArgumentException e) {
			throw fields.error("kinds", e.getMessage());
		}
	}

	/** A non-empty list of distinct names from {@code allowed}. */
	static List<String> requireKinds(List<String> kinds, List<String> allowed) {
		if (kinds.isEmpty() || Set.copyOf(kinds).size() != kinds.size() || !allowed.containsAll(kinds)) {
			throw new IllegalArgumentException("must list distinct kinds from " + allowed + ", got " + kinds);
		}

		return List.copyOf(kinds);
	}
}
