package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Level 4, constructed environments ({@value Algorithms#LEVEL4_GENERATOR}): the level-1
 * platform with a small built setting around the target cell, made of full blocks, stairs,
 * slabs, fences and vegetation. See {@link PlatformGenerator} for the target and viewpoints.
 *
 * <p>Layout, in draw order, all from the layout seed:
 *
 * <ol>
 *   <li>A setting {@code kind} ({@link #KINDS}) and a material {@link BlockPalettes#FAMILIES
 *       family}, then the setting itself:
 *       <ul>
 *         <li>{@code room}: walls three blocks tall around an interior of {@code 3 + nextInt(5)}
 *             by {@code 3 + nextInt(5)} cells containing the aim, with a doorway, one to three
 *             windows (open or holding a fence), a slab roof with probability 3/5 and up to
 *             two pieces of furniture.</li>
 *         <li>{@code courtyard}: a ring one or two blocks tall of full blocks and fences around
 *             {@code 7 + nextInt(5)} square cells, with two gaps, plants and leaf bushes inside
 *             and up to two stair benches.</li>
 *         <li>{@code alley}: two walls two to four blocks tall along X or Z, a passage
 *             {@code 1 + nextInt(3)} cells wide holding the aim, an overhead slab bridge with
 *             probability 1/2 and clutter in the passage.</li>
 *         <li>{@code ruin}: two to four broken wall segments near the aim, gaps more likely
 *             higher up, with slab caps, rubble and overgrowth.</li>
 *         <li>{@code terraces}: two or three stepped tiers of earth rising away from the aim,
 *             topped with ground blocks, some edges cut as stairs, plants on top.</li>
 *       </ul></li>
 *   <li>One to three irregular blocks in distinct cells next to the aim: a slab, a stair, a
 *       fence post, leaves, a plant or a full block capped with a slab.</li>
 *   <li>{@code 3 + nextInt(6)} scattered items on free cells at least {@value #CLEARANCE}
 *       cells from the aim: clutter blocks, slabs, stairs, fences, leaves or plants.</li>
 * </ol>
 *
 * <p>The exact draws are defined by this class; changing them needs a new generator version.
 * A viewpoint is kept when at least one estimated sight line to the aim cell is clear and
 * at least one passes a block cell ({@link SightLines.Estimate}): part of the target lies
 * behind a shape, beyond a gap in one, or behind vegetation. Leaves and plants never block
 * an estimate. The saved mask then applies the configured minimum visible fraction.
 */
public final class Level4Generator extends PlatformGenerator {
	static final int CLEARANCE = 2;
	static final String[] KINDS = {"room", "courtyard", "alley", "ruin", "terraces"};
	/** Side directions in {@code nextInt(4)} order, with the stair facing that looks along each. */
	private static final int[][] SIDES = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
	private static final String[] FACINGS = {"east", "south", "west", "north"};
	private static final int ROOM_WALL_HEIGHT = 3;

	public Level4Generator(ResolvedConfig config) {
		super(config, 4, Algorithms.LEVEL4_GENERATOR);
	}

	@Override
	protected List<Placement> layout(SeededRandom random, int[] aim, JsonObject parameters) {
		ResolvedConfig.Arena arena = this.config.arena();
		ConstructionBuilder layout = new ConstructionBuilder(arena.width(), arena.length(), arena.height(), aim);
		String kind = KINDS[random.nextInt(KINDS.length)];
		BlockPalettes.Family family = BlockPalettes.FAMILIES.get(random.nextInt(BlockPalettes.FAMILIES.size()));
		parameters.addProperty("setting", kind);
		parameters.addProperty("material", family.full());
		switch (kind) {
			case "room" -> room(random, layout, aim, family, parameters);
			case "courtyard" -> courtyard(random, layout, aim, family, parameters);
			case "alley" -> alley(random, layout, aim, family, parameters);
			case "ruin" -> ruin(random, layout, aim, family, parameters);
			case "terraces" -> terraces(random, layout, aim, family, parameters);
			default -> throw new IllegalStateException("Unknown setting " + kind);
		}

		int near = nearAim(random, layout, aim, family);
		int scattered = 3 + random.nextInt(6);
		for (int i = 0; i < scattered; i++) {
			int[] cell = layout.freeCell(random, CLEARANCE);
			if (cell != null) {
				item(random, layout, cell[0], cell[1], family);
			}
		}

		List<Placement> placements = layout.placements();
		parameters.addProperty("near_aim_blocks", near);
		parameters.addProperty("scattered_items", scattered);
		parameters.addProperty("blocks", placements.size());
		return placements;
	}

	private static void room(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject parameters) {
		int width = 3 + random.nextInt(5);
		int length = 3 + random.nextInt(5);
		int x0 = aim[0] - random.nextInt(width);
		int z0 = aim[2] - random.nextInt(length);
		for (int z = z0 - 1; z <= z0 + length; z++) {
			for (int x = x0 - 1; x <= x0 + width; x++) {
				if (x == x0 - 1 || x == x0 + width || z == z0 - 1 || z == z0 + length) {
					layout.column(x, z, GROUND_Y, ROOM_WALL_HEIGHT, family.full());
				}
			}
		}

		int[] door = wallCell(random, x0, z0, width, length);
		layout.remove(door[0], GROUND_Y, door[1]);
		layout.remove(door[0], GROUND_Y + 1, door[1]);
		int windows = 1 + random.nextInt(3);
		String fence = BlockPalettes.pick(random, BlockPalettes.FENCES);
		for (int i = 0; i < windows; i++) {
			int[] window = wallCell(random, x0, z0, width, length);
			if (random.nextInt(2) == 0) {
				layout.set(window[0], GROUND_Y + 1, window[1], fence);
			} else {
				layout.remove(window[0], GROUND_Y + 1, window[1]);
			}
		}

		boolean roof = random.nextInt(5) < 3;
		if (roof) {
			for (int z = z0 - 1; z <= z0 + length; z++) {
				for (int x = x0 - 1; x <= x0 + width; x++) {
					layout.set(x, GROUND_Y + ROOM_WALL_HEIGHT, z, family.slab("bottom"));
				}
			}
		}

		int furniture = random.nextInt(3);
		for (int i = 0; i < furniture; i++) {
			int x = x0 + random.nextInt(width);
			int z = z0 + random.nextInt(length);
			if (!layout.empty(x, GROUND_Y, z)) {
				continue;
			}

			if (random.nextInt(2) == 0) {
				layout.set(x, GROUND_Y, z, family.stair(FACINGS[random.nextInt(4)], false));
			} else {
				layout.set(x, GROUND_Y, z, fence);
				layout.set(x, GROUND_Y + 1, z, family.slab("bottom"));
			}
		}

		parameters.addProperty("interior_width", width);
		parameters.addProperty("interior_length", length);
		parameters.addProperty("windows", windows);
		parameters.addProperty("roof", roof);
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

	private static void courtyard(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject parameters) {
		int size = 7 + random.nextInt(5);
		int x0 = aim[0] - random.nextInt(size);
		int z0 = aim[2] - random.nextInt(size);
		int height = 1 + random.nextInt(2);
		String fence = BlockPalettes.pick(random, BlockPalettes.FENCES);
		for (int z = z0 - 1; z <= z0 + size; z++) {
			for (int x = x0 - 1; x <= x0 + size; x++) {
				if (x == x0 - 1 || x == x0 + size || z == z0 - 1 || z == z0 + size) {
					layout.column(x, z, GROUND_Y, height, random.nextInt(3) == 0 ? fence : family.full());
				}
			}
		}

		for (int i = 0; i < 2; i++) {
			int[] gap = wallCell(random, x0, z0, size, size);
			for (int y = GROUND_Y; y < GROUND_Y + height; y++) {
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
				if (layout.empty(x, GROUND_Y, z)) {
					layout.column(x, z, GROUND_Y, 1 + random.nextInt(2), leaves);
				}
			} else {
				layout.plant(x, z, ground, BlockPalettes.pick(random, BlockPalettes.PLANTS));
			}
		}

		int benches = random.nextInt(3);
		for (int i = 0; i < benches; i++) {
			int x = x0 + random.nextInt(size);
			int z = z0 + random.nextInt(size);
			if (layout.empty(x, GROUND_Y, z)) {
				layout.set(x, GROUND_Y, z, family.stair(FACINGS[random.nextInt(4)], false));
			}
		}

		parameters.addProperty("interior_size", size);
		parameters.addProperty("wall_height", height);
		parameters.addProperty("plants", plants);
	}

	private static void alley(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject parameters) {
		boolean alongX = random.nextInt(2) == 0;
		int gap = 1 + random.nextInt(3);
		int offset = random.nextInt(gap);
		int length = 8 + random.nextInt(7);
		int start = -random.nextInt(length);
		int leftHeight = 2 + random.nextInt(3);
		int rightHeight = 2 + random.nextInt(3);
		BlockPalettes.Family other = BlockPalettes.FAMILIES.get(random.nextInt(BlockPalettes.FAMILIES.size()));
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
			int y = GROUND_Y + Math.min(leftHeight, rightHeight) - 1;
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
			if (layout.empty(x, GROUND_Y, z)) {
				item(random, layout, x, z, family);
			}
		}

		parameters.addProperty("along_x", alongX);
		parameters.addProperty("passage_width", gap);
		parameters.addProperty("wall_length", length);
		parameters.addProperty("bridge", bridge);
	}

	private static void alleyColumn(ConstructionBuilder layout, boolean alongX, int along, int across, int height, String state) {
		layout.column(alongX ? along : across, alongX ? across : along, GROUND_Y, height, state);
	}

	private static void ruin(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject parameters) {
		int segments = 2 + random.nextInt(3);
		for (int s = 0; s < segments; s++) {
			int side = random.nextInt(4);
			int distance = 1 + random.nextInt(3);
			int length = 3 + random.nextInt(4);
			int start = -random.nextInt(length);
			int height = 2 + random.nextInt(3);
			BlockPalettes.Family material = random.nextInt(2) == 0 ? family : BlockPalettes.FAMILIES.get(random.nextInt(BlockPalettes.FAMILIES.size()));
			int[] out = SIDES[side];
			int[] along = SIDES[(side + 1) % 4];
			for (int i = start; i < start + length; i++) {
				int x = aim[0] + out[0] * distance + along[0] * i;
				int z = aim[2] + out[1] * distance + along[1] * i;
				int top = 0;
				for (int y = GROUND_Y; y < GROUND_Y + height; y++) {
					if (random.nextInt(10) < 2 * (y - GROUND_Y) + 1) {
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
			if (layout.empty(x, GROUND_Y, z)) {
				layout.set(x, GROUND_Y, z, random.nextInt(2) == 0 ? family.slab("bottom") : family.stair(FACINGS[random.nextInt(4)], false));
			}
		}

		int overgrowth = 1 + random.nextInt(3);
		for (int i = 0; i < overgrowth; i++) {
			int x = aim[0] - 3 + random.nextInt(7);
			int z = aim[2] - 3 + random.nextInt(7);
			String leaves = BlockPalettes.pick(random, BlockPalettes.LEAVES);
			int y = GROUND_Y;
			while (!layout.empty(x, y, z) && y < GROUND_Y + 6) {
				y++;
			}

			layout.set(x, y, z, leaves);
		}

		parameters.addProperty("segments", segments);
		parameters.addProperty("rubble", rubble);
	}

	private static void terraces(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family, JsonObject parameters) {
		int side = random.nextInt(4);
		int steps = 2 + random.nextInt(2);
		int depth = 1 + random.nextInt(2);
		int width = 5 + random.nextInt(5);
		int start = -random.nextInt(width);
		String ground = BlockPalettes.pick(random, BlockPalettes.GROUND);
		String fill = BlockPalettes.pick(random, BlockPalettes.TERRAIN_FILL);
		int[] out = SIDES[side];
		int[] along = SIDES[(side + 1) % 4];
		for (int step = 0; step < steps; step++) {
			int top = GROUND_Y + step;
			for (int row = 0; row < depth; row++) {
				int distance = 1 + step * depth + row;
				for (int i = start; i < start + width; i++) {
					int x = aim[0] + out[0] * distance + along[0] * i;
					int z = aim[2] + out[1] * distance + along[1] * i;
					layout.column(x, z, GROUND_Y, top - GROUND_Y, fill);
					boolean cut = row == 0 && random.nextInt(3) == 0;
					layout.set(x, top, z, cut ? family.stair(FACINGS[side], false) : ground);
				}
			}
		}

		int plants = 2 + random.nextInt(5);
		for (int i = 0; i < plants; i++) {
			int distance = 1 + random.nextInt(steps * depth);
			int x = aim[0] + out[0] * distance + along[0] * (start + random.nextInt(width));
			int z = aim[2] + out[1] * distance + along[1] * (start + random.nextInt(width));
			int top = GROUND_Y + (distance - 1) / depth;
			String plant = random.nextInt(4) == 0 ? BlockPalettes.pick(random, BlockPalettes.LEAVES) : BlockPalettes.pick(random, BlockPalettes.PLANTS);
			if (ground.equals(layout.at(x, top, z)) && layout.empty(x, top + 1, z)) {
				layout.set(x, top + 1, z, plant);
			}
		}

		parameters.addProperty("terrace_side", side);
		parameters.addProperty("steps", steps);
		parameters.addProperty("step_depth", depth);
	}

	/** Irregular blocks in distinct neighbours of the aim; returns how many were drawn. */
	private static int nearAim(SeededRandom random, ConstructionBuilder layout, int[] aim, BlockPalettes.Family family) {
		List<int[]> neighbours = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					neighbours.add(new int[] {dx, dz});
				}
			}
		}

		int count = 1 + random.nextInt(3);
		for (int i = 0; i < count; i++) {
			int[] offset = neighbours.remove(random.nextInt(neighbours.size()));
			int x = aim[0] + offset[0];
			int z = aim[2] + offset[1];
			int kind = random.nextInt(6);
			if (!layout.empty(x, GROUND_Y, z)) {
				continue;
			}

			switch (kind) {
				case 0 -> layout.set(x, GROUND_Y, z, family.slab("bottom"));
				case 1 -> layout.set(x, GROUND_Y, z, family.stair(FACINGS[random.nextInt(4)], random.nextInt(4) == 0));
				case 2 -> layout.set(x, GROUND_Y, z, BlockPalettes.pick(random, BlockPalettes.FENCES));
				case 3 -> layout.set(x, GROUND_Y, z, BlockPalettes.pick(random, BlockPalettes.LEAVES));
				case 4 -> layout.plant(x, z, BlockPalettes.pick(random, BlockPalettes.GROUND), BlockPalettes.pick(random, BlockPalettes.PLANTS));
				default -> {
					layout.set(x, GROUND_Y, z, family.full());
					layout.set(x, GROUND_Y + 1, z, family.slab("bottom"));
				}
			}
		}

		return count;
	}

	/** One scattered item at ground-level cell {@code (x, z)}. */
	private static void item(SeededRandom random, ConstructionBuilder layout, int x, int z, BlockPalettes.Family family) {
		switch (random.nextInt(7)) {
			case 0, 1 -> layout.set(x, GROUND_Y, z, BlockPalettes.clutter(random));
			case 2 -> layout.set(x, GROUND_Y, z, family.slab(random.nextInt(3) == 0 ? "double" : "bottom"));
			case 3 -> layout.set(x, GROUND_Y, z, family.stair(FACINGS[random.nextInt(4)], false));
			case 4 -> layout.column(x, z, GROUND_Y, 1 + random.nextInt(2), BlockPalettes.pick(random, BlockPalettes.FENCES));
			case 5 -> layout.column(x, z, GROUND_Y, 1 + random.nextInt(2), BlockPalettes.pick(random, BlockPalettes.LEAVES));
			default -> layout.plant(x, z, BlockPalettes.pick(random, BlockPalettes.GROUND), BlockPalettes.pick(random, BlockPalettes.PLANTS));
		}
	}

	@Override
	protected @Nullable String checkSight(SolidGrid grid, double[] eye, int[] aim) {
		SightLines.Estimate estimate = SightLines.estimate(grid, eye, aim);
		if (estimate.clear() == 0.0) {
			return "target_hidden";
		}

		return estimate.open() < 1.0 ? null : "target_unobstructed";
	}
}
