package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.contract.JsonFields;
import io.github.thedeadbolt.honeyfactory.contract.JsonWrite;
import java.util.List;
import java.util.Set;

/**
 * A small built setting around the aim cell, made of full blocks, stairs, slabs, fences
 * and vegetation ({@code constructed_setting}, the main level-4 part).
 *
 * <p>Draws a kind from {@code kinds} and a material {@link BlockPalettes#FAMILIES family},
 * then builds:
 *
 * <ul>
 *   <li>{@code room}: walls three blocks tall around an interior of {@code 3 + nextInt(5)} by
 *       {@code 3 + nextInt(5)} cells containing the aim, with a doorway, one to three windows
 *       (open or holding a fence), a slab roof with probability 3/5 and up to two pieces of
 *       furniture.</li>
 *   <li>{@code courtyard}: a ring one or two blocks tall of full blocks and fences around
 *       {@code 7 + nextInt(5)} square cells, with two gaps, plants and leaf bushes inside and
 *       up to two stair benches.</li>
 *   <li>{@code alley}: two walls two to four blocks tall along X or Z, a passage
 *       {@code 1 + nextInt(3)} cells wide holding the aim, an overhead slab bridge with
 *       probability 1/2 and clutter in the passage.</li>
 *   <li>{@code ruin}: two to four broken wall segments near the aim, gaps more likely higher
 *       up, with slab caps, rubble and overgrowth.</li>
 *   <li>{@code terraces}: two or three stepped tiers of earth rising away from the aim,
 *       topped with ground blocks, some edges cut as stairs, plants on top.</li>
 * </ul>
 *
 * <p>The exact draws are defined by this class.
 */
public record ConstructedSettingPart(List<String> kinds) implements LayoutPart {
	public static final String TYPE = "constructed_setting";
	public static final List<String> KINDS = List.of("room", "courtyard", "alley", "ruin", "terraces");
	public static final ConstructedSettingPart DEFAULT = new ConstructedSettingPart(KINDS);
	private static final int ROOM_WALL_HEIGHT = 3;

	public ConstructedSettingPart {
		kinds = HidingStructurePart.requireKinds(kinds, KINDS);
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
		JsonObject drawn = context.drawn();
		String kind = this.kinds.get(random.nextInt(this.kinds.size()));
		BlockPalettes.Family family = Constructions.family(random);
		drawn.addProperty("setting", kind);
		drawn.addProperty("material", family.full());
		switch (kind) {
			case "room" -> room(random, layout, aim, family, drawn);
			case "courtyard" -> courtyard(random, layout, aim, family, drawn);
			case "alley" -> alley(random, layout, aim, family, drawn);
			case "ruin" -> ruin(random, layout, aim, family, drawn);
			case "terraces" -> terraces(random, layout, aim, family, drawn);
			default -> throw new IllegalStateException("Unknown setting " + kind);
		}
	}

	@Override
	public JsonObject settingsJson() {
		JsonObject json = new JsonObject();
		json.add("kinds", JsonWrite.strings(this.kinds));
		return json;
	}

	static ConstructedSettingPart read(JsonFields fields) {
		fields.rejectUnknown(Set.of("type", "kinds"));
		try {
			return new ConstructedSettingPart(fields.has("kinds") ? fields.strings("kinds") : KINDS);
		} catch (IllegalArgumentException e) {
			throw fields.error("kinds", e.getMessage());
		}
	}

	private static void room(SeededRandom random, LayoutBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject drawn) {
		int width = 3 + random.nextInt(5);
		int length = 3 + random.nextInt(5);
		int x0 = aim[0] - random.nextInt(width);
		int z0 = aim[2] - random.nextInt(length);
		for (int z = z0 - 1; z <= z0 + length; z++) {
			for (int x = x0 - 1; x <= x0 + width; x++) {
				if (x == x0 - 1 || x == x0 + width || z == z0 - 1 || z == z0 + length) {
					layout.column(x, z, LayoutBuilder.GROUND_Y, ROOM_WALL_HEIGHT, family.full());
				}
			}
		}

		int[] door = wallCell(random, x0, z0, width, length);
		layout.remove(door[0], LayoutBuilder.GROUND_Y, door[1]);
		layout.remove(door[0], LayoutBuilder.GROUND_Y + 1, door[1]);
		int windows = 1 + random.nextInt(3);
		String fence = BlockPalettes.pick(random, BlockPalettes.FENCES);
		for (int i = 0; i < windows; i++) {
			int[] window = wallCell(random, x0, z0, width, length);
			if (random.nextInt(2) == 0) {
				layout.set(window[0], LayoutBuilder.GROUND_Y + 1, window[1], fence);
			} else {
				layout.remove(window[0], LayoutBuilder.GROUND_Y + 1, window[1]);
			}
		}

		boolean roof = random.nextInt(5) < 3;
		if (roof) {
			for (int z = z0 - 1; z <= z0 + length; z++) {
				for (int x = x0 - 1; x <= x0 + width; x++) {
					layout.set(x, LayoutBuilder.GROUND_Y + ROOM_WALL_HEIGHT, z, family.slab("bottom"));
				}
			}
		}

		int furniture = random.nextInt(3);
		for (int i = 0; i < furniture; i++) {
			int x = x0 + random.nextInt(width);
			int z = z0 + random.nextInt(length);
			if (!layout.empty(x, LayoutBuilder.GROUND_Y, z)) {
				continue;
			}

			if (random.nextInt(2) == 0) {
				layout.set(x, LayoutBuilder.GROUND_Y, z, family.stair(Constructions.FACINGS[random.nextInt(4)], false));
			} else {
				layout.set(x, LayoutBuilder.GROUND_Y, z, fence);
				layout.set(x, LayoutBuilder.GROUND_Y + 1, z, family.slab("bottom"));
			}
		}

		drawn.addProperty("interior_width", width);
		drawn.addProperty("interior_length", length);
		drawn.addProperty("windows", windows);
		drawn.addProperty("roof", roof);
	}

	/** A wall cell of a room, not a corner: side {@code nextInt(4)}, then a position along it. */
	private static int[] wallCell(SeededRandom random, int x0, int z0, int width, int length) {
		int side = random.nextInt(4);
		int along = random.nextInt(side % 2 == 0 ? length : width);
		return switch (side) {
			case 0 -> new int[] {x0 + width, z0 + along};
			case 1 -> new int[] {x0 + along, z0 + length};
			case 2 -> new int[] {x0 - 1, z0 + along};
			default -> new int[] {x0 + along, z0 - 1};
		};
	}

	private static void courtyard(SeededRandom random, LayoutBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject drawn) {
		int size = 7 + random.nextInt(5);
		int x0 = aim[0] - random.nextInt(size);
		int z0 = aim[2] - random.nextInt(size);
		int height = 1 + random.nextInt(2);
		String fence = BlockPalettes.pick(random, BlockPalettes.FENCES);
		for (int z = z0 - 1; z <= z0 + size; z++) {
			for (int x = x0 - 1; x <= x0 + size; x++) {
				if (x == x0 - 1 || x == x0 + size || z == z0 - 1 || z == z0 + size) {
					layout.column(x, z, LayoutBuilder.GROUND_Y, height, random.nextInt(3) == 0 ? fence : family.full());
				}
			}
		}

		for (int i = 0; i < 2; i++) {
			int[] gap = wallCell(random, x0, z0, size, size);
			for (int y = LayoutBuilder.GROUND_Y; y < LayoutBuilder.GROUND_Y + height; y++) {
				layout.remove(gap[0], y, gap[1]);
			}
		}

		String ground = BlockPalettes.pick(random, BlockPalettes.GROUND);
		int plants = 4 + random.nextInt(6);
		for (int i = 0; i < plants; i++) {
			int x = x0 + random.nextInt(size);
			int z = z0 + random.nextInt(size);
			if (random.nextInt(4) == 0) {
				String leaves = BlockPalettes.pick(random, BlockPalettes.LEAVES);
				if (layout.empty(x, LayoutBuilder.GROUND_Y, z)) {
					layout.column(x, z, LayoutBuilder.GROUND_Y, 1 + random.nextInt(2), leaves);
				}
			} else {
				layout.plant(x, z, ground, BlockPalettes.pick(random, BlockPalettes.PLANTS));
			}
		}

		int benches = random.nextInt(3);
		for (int i = 0; i < benches; i++) {
			int x = x0 + random.nextInt(size);
			int z = z0 + random.nextInt(size);
			if (layout.empty(x, LayoutBuilder.GROUND_Y, z)) {
				layout.set(x, LayoutBuilder.GROUND_Y, z, family.stair(Constructions.FACINGS[random.nextInt(4)], false));
			}
		}

		drawn.addProperty("interior_size", size);
		drawn.addProperty("wall_height", height);
		drawn.addProperty("plants", plants);
	}

	private static void alley(SeededRandom random, LayoutBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject drawn) {
		boolean alongX = random.nextInt(2) == 0;
		int gap = 1 + random.nextInt(3);
		int offset = random.nextInt(gap);
		int length = 8 + random.nextInt(7);
		int start = -random.nextInt(length);
		int leftHeight = 2 + random.nextInt(3);
		int rightHeight = 2 + random.nextInt(3);
		BlockPalettes.Family other = Constructions.family(random);
		int along0 = alongX ? aim[0] : aim[2];
		int across0 = (alongX ? aim[2] : aim[0]) - offset;
		for (int i = start; i < start + length; i++) {
			int along = along0 + i;
			alleyColumn(layout, alongX, along, across0 - 1, leftHeight, family.full());
			alleyColumn(layout, alongX, along, across0 + gap, rightHeight, other.full());
		}

		boolean bridge = random.nextInt(2) == 0;
		if (bridge) {
			int at = along0 + start + random.nextInt(length);
			int y = LayoutBuilder.GROUND_Y + Math.min(leftHeight, rightHeight) - 1;
			for (int across = across0; across < across0 + gap; across++) {
				int x = alongX ? at : across;
				int z = alongX ? across : at;
				layout.set(x, y, z, family.slab("top"));
			}
		}

		int clutter = 2 + random.nextInt(4);
		for (int i = 0; i < clutter; i++) {
			int along = along0 + start + random.nextInt(length);
			int across = across0 + random.nextInt(gap);
			int x = alongX ? along : across;
			int z = alongX ? across : along;
			if (layout.empty(x, LayoutBuilder.GROUND_Y, z)) {
				Constructions.item(random, layout, x, z, family);
			}
		}

		drawn.addProperty("along_x", alongX);
		drawn.addProperty("passage_width", gap);
		drawn.addProperty("wall_length", length);
		drawn.addProperty("bridge", bridge);
	}

	private static void alleyColumn(LayoutBuilder layout, boolean alongX, int along, int across, int height, String state) {
		layout.column(alongX ? along : across, alongX ? across : along, LayoutBuilder.GROUND_Y, height, state);
	}

	private static void ruin(SeededRandom random, LayoutBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject drawn) {
		int segments = 2 + random.nextInt(3);
		for (int s = 0; s < segments; s++) {
			int side = random.nextInt(4);
			int distance = 1 + random.nextInt(3);
			int length = 3 + random.nextInt(4);
			int start = -random.nextInt(length);
			int height = 2 + random.nextInt(3);
			BlockPalettes.Family material = random.nextInt(2) == 0 ? family : Constructions.family(random);
			int[] out = HidingStructurePart.SIDES[side];
			int[] along = HidingStructurePart.SIDES[(side + 1) % 4];
			for (int i = start; i < start + length; i++) {
				int x = aim[0] + out[0] * distance + along[0] * i;
				int z = aim[2] + out[1] * distance + along[1] * i;
				int top = 0;
				for (int y = LayoutBuilder.GROUND_Y; y < LayoutBuilder.GROUND_Y + height; y++) {
					if (random.nextInt(10) < 2 * (y - LayoutBuilder.GROUND_Y) + 1) {
						break;
					}

					layout.set(x, y, z, material.full());
					top = y;
				}

				if (top > 0 && random.nextInt(2) == 0) {
					layout.set(x, top + 1, z, material.slab("bottom"));
				}
			}
		}

		int rubble = 2 + random.nextInt(4);
		for (int i = 0; i < rubble; i++) {
			int x = aim[0] - 4 + random.nextInt(9);
			int z = aim[2] - 4 + random.nextInt(9);
			if (layout.empty(x, LayoutBuilder.GROUND_Y, z)) {
				layout.set(x, LayoutBuilder.GROUND_Y, z, random.nextInt(2) == 0 ? family.slab("bottom") : family.stair(Constructions.FACINGS[random.nextInt(4)], false));
			}
		}

		int overgrowth = 1 + random.nextInt(3);
		for (int i = 0; i < overgrowth; i++) {
			int x = aim[0] - 3 + random.nextInt(7);
			int z = aim[2] - 3 + random.nextInt(7);
			String leaves = BlockPalettes.pick(random, BlockPalettes.LEAVES);
			int y = LayoutBuilder.GROUND_Y;
			while (!layout.empty(x, y, z) && y < LayoutBuilder.GROUND_Y + 6) {
				y++;
			}

			layout.set(x, y, z, leaves);
		}

		drawn.addProperty("segments", segments);
		drawn.addProperty("rubble", rubble);
	}

	private static void terraces(SeededRandom random, LayoutBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject drawn) {
		int side = random.nextInt(4);
		int steps = 2 + random.nextInt(2);
		int depth = 1 + random.nextInt(2);
		int width = 5 + random.nextInt(5);
		int start = -random.nextInt(width);
		String ground = BlockPalettes.pick(random, BlockPalettes.GROUND);
		String fill = BlockPalettes.pick(random, BlockPalettes.TERRAIN_FILL);
		int[] out = HidingStructurePart.SIDES[side];
		int[] along = HidingStructurePart.SIDES[(side + 1) % 4];
		for (int step = 0; step < steps; step++) {
			int top = LayoutBuilder.GROUND_Y + step;
			for (int row = 0; row < depth; row++) {
				int distance = 1 + step * depth + row;
				for (int i = start; i < start + width; i++) {
					int x = aim[0] + out[0] * distance + along[0] * i;
					int z = aim[2] + out[1] * distance + along[1] * i;
					layout.column(x, z, LayoutBuilder.GROUND_Y, top - LayoutBuilder.GROUND_Y, fill);
					boolean cut = row == 0 && random.nextInt(3) == 0;
					layout.set(x, top, z, cut ? family.stair(Constructions.FACINGS[side], false) : ground);
				}
			}
		}

		int plants = 2 + random.nextInt(5);
		for (int i = 0; i < plants; i++) {
			int distance = 1 + random.nextInt(steps * depth);
			int x = aim[0] + out[0] * distance + along[0] * (start + random.nextInt(width));
			int z = aim[2] + out[1] * distance + along[1] * (start + random.nextInt(width));
			int top = LayoutBuilder.GROUND_Y + (distance - 1) / depth;
			String plant = random.nextInt(4) == 0 ? BlockPalettes.pick(random, BlockPalettes.LEAVES) : BlockPalettes.pick(random, BlockPalettes.PLANTS);
			if (ground.equals(layout.at(x, top, z)) && layout.empty(x, top + 1, z)) {
				layout.set(x, top + 1, z, plant);
			}
		}

		drawn.addProperty("terrace_side", side);
		drawn.addProperty("steps", steps);
		drawn.addProperty("step_depth", depth);
	}
}
