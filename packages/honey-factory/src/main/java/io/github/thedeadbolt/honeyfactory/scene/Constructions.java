package io.github.thedeadbolt.honeyfactory.scene;

/** Draws shared by the level-4 parts. */
final class Constructions {
	/** Stair facings in {@code nextInt(4)} order, matching {@link HidingStructurePart#SIDES}. */
	static final String[] FACINGS = {"east", "south", "west", "north"};

	private Constructions() {
	}

	/** A uniformly drawn material family. */
	static BlockPalettes.Family family(SeededRandom random) {
		return BlockPalettes.FAMILIES.get(random.nextInt(BlockPalettes.FAMILIES.size()));
	}

	/** One scattered item at ground-level cell {@code (x, z)}. */
	static void item(SeededRandom random, LayoutBuilder layout, int x, int z, BlockPalettes.Family family) {
		switch (random.nextInt(7)) {
			case 0, 1 -> layout.set(x, LayoutBuilder.GROUND_Y, z, BlockPalettes.clutter(random));
			case 2 -> layout.set(x, LayoutBuilder.GROUND_Y, z, family.slab(random.nextInt(3) == 0 ? "double" : "bottom"));
			case 3 -> layout.set(x, LayoutBuilder.GROUND_Y, z, family.stair(FACINGS[random.nextInt(4)], false));
			case 4 -> layout.column(x, z, LayoutBuilder.GROUND_Y, 1 + random.nextInt(2), BlockPalettes.pick(random, BlockPalettes.FENCES));
			case 5 -> layout.column(x, z, LayoutBuilder.GROUND_Y, 1 + random.nextInt(2), BlockPalettes.pick(random, BlockPalettes.LEAVES));
			default -> layout.plant(x, z, BlockPalettes.pick(random, BlockPalettes.GROUND), BlockPalettes.pick(random, BlockPalettes.PLANTS));
		}
	}
}
