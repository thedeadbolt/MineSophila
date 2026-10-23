package io.github.thedeadbolt.honeyfactory.scene;

import com.google.gson.JsonObject;
import io.github.thedeadbolt.honeyfactory.config.ResolvedConfig;
import io.github.thedeadbolt.honeyfactory.contract.Algorithms;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Level 2, clutter ({@value Algorithms#LEVEL2_GENERATOR}): the level-1 platform with
 * scattered single blocks and small piles, many of them yellow or orange, none touching
 * the target. See {@link PlatformGenerator} for the target and viewpoints.
 *
 * <p>Layout, in draw order: {@code 6 + nextInt(9)} items. Each draws a material
 * ({@link BlockPalettes#clutter}), a kind (single block or pile, even odds) and a base
 * cell, re-drawn up to {@value LayoutBuilder#CELL_TRIES} times until it is free and at least
 * {@value #CLEARANCE} cells (Chebyshev) from the aim cell; an item without one is
 * skipped. A pile grows {@code 1 + nextInt(3)} more cells, each a step in one of four
 * directions from a random cell of the pile, kept only when free. Every pile cell is a
 * column {@code 1 + nextInt(2)} blocks tall; a single block is one block.
 *
 * <p>A viewpoint is kept only when every sight line to the aim cell is clear, since
 * positive examples must show the whole target.
 */
public final class Level2Generator extends PlatformGenerator {
	static final int CLEARANCE = 2;
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	public Level2Generator(ResolvedConfig config) {
		super(config, 2, Algorithms.LEVEL2_GENERATOR);
	}

	@Override
	protected List<Placement> layout(SeededRandom random, int[] aim, JsonObject parameters) {
		ResolvedConfig.Arena arena = this.config.arena();
		LayoutBuilder layout = new LayoutBuilder(arena.width(), arena.length(), arena.height(), aim);
		int items = 6 + random.nextInt(9);
		int piles = 0;
		int distractors = 0;
		for (int item = 0; item < items; item++) {
			String material = BlockPalettes.clutter(random);
			boolean pile = random.nextInt(2) == 0;
			int[] base = layout.freeCell(random, CLEARANCE);
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
				if (layout.free(cell[0], cell[1], CLEARANCE) && cells.stream().noneMatch(c -> c[0] == cell[0] && c[1] == cell[1])) {
					cells.add(cell);
				}
			}

			for (int[] cell : cells) {
				layout.column(cell[0], cell[1], 1 + random.nextInt(2), material);
			}
		}

		List<Placement> placements = layout.placements();
		parameters.addProperty("items", items);
		parameters.addProperty("piles", piles);
		parameters.addProperty("distractor_items", distractors);
		parameters.addProperty("blocks", placements.size());
		return placements;
	}

	@Override
	protected @Nullable String checkSight(SolidGrid grid, double[] eye, int[] aim) {
		return SightLines.clearFraction(grid, eye, aim) == 1.0 ? null : "target_occluded";
	}
}
