package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Level 3, partial hiding ({@value Algorithms#LEVEL3_GENERATOR}): the level-1 platform
 * with a hiding structure built against the target cell, plus walls, pillars, stacks and
 * scattered blocks elsewhere. See {@link PlatformGenerator} for the target and viewpoints.
 *
 * <p>Layout, in draw order:
 *
 * <ol>
 *   <li>Hiding structure: a material from {@link BlockPalettes#STRUCTURES}, a kind and a
 *       side {@code s = nextInt(4)} (+X, +Z, -X, -Z), built in the cells next to the aim:
 *       <ul>
 *         <li>{@code wall}: {@code 2 + nextInt(3)} cells along side {@code s}, starting
 *             {@code nextInt(length)} cells before the cell beside the aim, so it always
 *             covers that cell; {@code 1 + nextInt(2)} blocks tall.</li>
 *         <li>{@code corner}: the cells beside the aim on sides {@code s} and {@code s+1}
 *             and the diagonal between them; {@code 1 + nextInt(2)} blocks tall.</li>
 *         <li>{@code recess}: all eight neighbours except the three on side {@code s},
 *             two blocks tall, with a lintel above the aim cell when {@code nextInt(2) == 0}.</li>
 *         <li>{@code pillars}: {@code 2 + nextInt(2)} distinct neighbours, drawn from the
 *             eight, each {@code 2 + nextInt(2)} blocks tall.</li>
 *       </ul></li>
 *   <li>Background: {@code 3 + nextInt(4)} structures, each a material, a kind and a base
 *       cell at least {@value #CLEARANCE} cells (Chebyshev) from the aim, drawn as in level 2.
 *       A wall runs {@code 2 + nextInt(4)} cells along +X or +Z and is {@code 1 + nextInt(3)}
 *       tall; a pillar is {@code 2 + nextInt(3)} tall; a stack covers 2 × 2 cells, each
 *       {@code 1 + nextInt(2)} tall. Cells that are not free are skipped.</li>
 *   <li>Scattered blocks: {@code 2 + nextInt(5)} single {@link BlockPalettes#clutter} blocks
 *       on free cells at the same clearance.</li>
 * </ol>
 *
 * <p>A viewpoint is kept only when some but not all sight lines to the aim cell are clear.
 */
public final class Level3Generator extends PlatformGenerator {
	static final int CLEARANCE = 3;
	/** Side directions in {@code nextInt(4)} order. */
	private static final int[][] SIDES = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
	private static final String[] KINDS = {"wall", "corner", "recess", "pillars"};
	private static final String[] BACKGROUND_KINDS = {"wall", "pillar", "stack"};

	public Level3Generator(ResolvedConfig config) {
		super(config, 3, Algorithms.LEVEL3_GENERATOR);
	}

	@Override
	protected List<Placement> layout(SeededRandom random, int[] aim, JsonObject parameters) {
		ResolvedConfig.Arena arena = this.config.arena();
		LayoutBuilder layout = new LayoutBuilder(arena.width(), arena.length(), arena.height(), aim);
		String material = BlockPalettes.pick(random, BlockPalettes.STRUCTURES);
		String kind = KINDS[random.nextInt(KINDS.length)];
		int side = random.nextInt(SIDES.length);
		parameters.addProperty("structure", kind);
		parameters.addProperty("structure_side", side);
		parameters.addProperty("structure_material", material);
		hidingStructure(random, layout, aim, kind, side, material, parameters);

		int background = 3 + random.nextInt(4);
		for (int i = 0; i < background; i++) {
			backgroundStructure(random, layout);
		}

		int scattered = 2 + random.nextInt(5);
		for (int i = 0; i < scattered; i++) {
			String block = BlockPalettes.clutter(random);
			int[] cell = layout.freeCell(random, CLEARANCE);
			if (cell != null) {
				layout.column(cell[0], cell[1], 1, block);
			}
		}

		List<Placement> placements = layout.placements();
		parameters.addProperty("background_structures", background);
		parameters.addProperty("scattered_blocks", scattered);
		parameters.addProperty("blocks", placements.size());
		return placements;
	}

	private static void hidingStructure(SeededRandom random, LayoutBuilder layout, int[] aim, String kind, int side, String material, JsonObject parameters) {
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

				parameters.addProperty("structure_length", length);
				parameters.addProperty("structure_height", height);
			}
			case "corner" -> {
				int height = 1 + random.nextInt(2);
				layout.column(x + out[0], z + out[1], height, material);
				layout.column(x + along[0], z + along[1], height, material);
				layout.column(x + out[0] + along[0], z + out[1] + along[1], height, material);
				parameters.addProperty("structure_height", height);
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

				parameters.addProperty("structure_lintel", lintel);
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

				parameters.addProperty("structure_count", count);
			}
			default -> throw new IllegalStateException("Unknown hiding structure " + kind);
		}
	}

	private static void backgroundStructure(SeededRandom random, LayoutBuilder layout) {
		String material = BlockPalettes.pick(random, BlockPalettes.STRUCTURES);
		String kind = BACKGROUND_KINDS[random.nextInt(BACKGROUND_KINDS.length)];
		int[] base = layout.freeCell(random, CLEARANCE);
		if (base == null) {
			return;
		}

		switch (kind) {
			case "wall" -> {
				boolean alongX = random.nextInt(2) == 0;
				int length = 2 + random.nextInt(4);
				int height = 1 + random.nextInt(3);
				for (int i = 0; i < length; i++) {
					int cellX = base[0] + (alongX ? i : 0);
					int cellZ = base[1] + (alongX ? 0 : i);
					if (layout.free(cellX, cellZ, CLEARANCE)) {
						layout.column(cellX, cellZ, height, material);
					}
				}
			}
			case "pillar" -> layout.column(base[0], base[1], 2 + random.nextInt(3), material);
			case "stack" -> {
				for (int dx = 0; dx < 2; dx++) {
					for (int dz = 0; dz < 2; dz++) {
						int height = 1 + random.nextInt(2);
						if (layout.free(base[0] + dx, base[1] + dz, CLEARANCE)) {
							layout.column(base[0] + dx, base[1] + dz, height, material);
						}
					}
				}
			}
			default -> throw new IllegalStateException("Unknown background structure " + kind);
		}
	}

	@Override
	protected @Nullable String checkSight(SolidGrid grid, double[] eye, int[] aim) {
		double clear = SightLines.clearFraction(grid, eye, aim);
		return clear > 0.0 && clear < 1.0 ? null : "target_not_partially_hidden";
	}
}
