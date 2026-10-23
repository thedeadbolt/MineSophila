package io.github.thedeadbolt.honeyfactory.scene;

import java.util.List;

/**
 * Block palettes of the generated levels. Every entry is an opaque full cube without
 * block-state properties, so its ID is also its canonical state. Generators draw from
 * these lists by index: reordering or editing one changes their output and needs a new
 * generator version.
 */
public final class BlockPalettes {
	/** Yellow and orange blocks that resemble honey. */
	public static final List<String> DISTRACTORS = List.of(
		"minecraft:yellow_concrete",
		"minecraft:orange_concrete",
		"minecraft:yellow_terracotta",
		"minecraft:orange_terracotta",
		"minecraft:yellow_wool",
		"minecraft:orange_wool",
		"minecraft:honeycomb_block",
		"minecraft:sponge",
		"minecraft:gold_block"
	);

	/** Other concrete colours. */
	public static final List<String> CONCRETE = List.of(
		"minecraft:white_concrete",
		"minecraft:red_concrete",
		"minecraft:blue_concrete",
		"minecraft:lime_concrete",
		"minecraft:cyan_concrete",
		"minecraft:black_concrete",
		"minecraft:brown_concrete",
		"minecraft:purple_concrete",
		"minecraft:pink_concrete",
		"minecraft:green_concrete"
	);

	/** A few textured blocks that level 2 mixes into its clutter. */
	public static final List<String> TEXTURED = List.of(
		"minecraft:oak_planks",
		"minecraft:stone",
		"minecraft:bricks",
		"minecraft:cobblestone",
		"minecraft:sandstone"
	);

	/** The broader textured palette of level-3 walls, pillars and stacks. */
	public static final List<String> STRUCTURES = List.of(
		"minecraft:stone_bricks",
		"minecraft:mossy_stone_bricks",
		"minecraft:cobblestone",
		"minecraft:mossy_cobblestone",
		"minecraft:bricks",
		"minecraft:oak_planks",
		"minecraft:spruce_planks",
		"minecraft:andesite",
		"minecraft:polished_andesite",
		"minecraft:granite",
		"minecraft:diorite",
		"minecraft:deepslate_bricks",
		"minecraft:mud_bricks",
		"minecraft:terracotta",
		"minecraft:smooth_stone",
		"minecraft:sandstone"
	);

	private BlockPalettes() {
	}

	/** A uniformly drawn entry of {@code palette}. */
	public static String pick(SeededRandom random, List<String> palette) {
		return palette.get(random.nextInt(palette.size()));
	}

	/**
	 * A clutter material: a distractor with probability 0.4, another concrete colour with
	 * 0.4, otherwise a textured block.
	 */
	public static String clutter(SeededRandom random) {
		int draw = random.nextInt(10);
		return pick(random, draw < 4 ? DISTRACTORS : draw < 8 ? CONCRETE : TEXTURED);
	}
}
