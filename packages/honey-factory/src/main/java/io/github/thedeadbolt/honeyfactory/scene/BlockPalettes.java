package io.github.thedeadbolt.honeyfactory.scene;

import java.util.List;

/**
 * Block palettes of the generated levels. Entries of the level 1–3 lists are opaque full
 * cubes without block-state properties, so each ID is also its canonical state; level 4
 * adds shaped blocks and vegetation with explicit canonical states. Generators draw from
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

	/**
	 * Level-4 building materials: a full block and the stairs and slab IDs cut from it.
	 * Unlike the lists above, stairs and slabs carry block-state properties.
	 */
	public record Family(String full, String stairs, String slab) {
		/** A straight stair, its high side towards {@code facing}. */
		public String stair(String facing, boolean top) {
			return this.stairs + "[facing=" + facing + ",half=" + (top ? "top" : "bottom") + ",shape=straight,waterlogged=false]";
		}

		/** A slab of {@code type} bottom, top or double. */
		public String slab(String type) {
			return this.slab + "[type=" + type + ",waterlogged=false]";
		}
	}

	public static final List<Family> FAMILIES = List.of(
		new Family("minecraft:cobblestone", "minecraft:cobblestone_stairs", "minecraft:cobblestone_slab"),
		new Family("minecraft:mossy_cobblestone", "minecraft:mossy_cobblestone_stairs", "minecraft:mossy_cobblestone_slab"),
		new Family("minecraft:stone_bricks", "minecraft:stone_brick_stairs", "minecraft:stone_brick_slab"),
		new Family("minecraft:mossy_stone_bricks", "minecraft:mossy_stone_brick_stairs", "minecraft:mossy_stone_brick_slab"),
		new Family("minecraft:bricks", "minecraft:brick_stairs", "minecraft:brick_slab"),
		new Family("minecraft:oak_planks", "minecraft:oak_stairs", "minecraft:oak_slab"),
		new Family("minecraft:spruce_planks", "minecraft:spruce_stairs", "minecraft:spruce_slab"),
		new Family("minecraft:mud_bricks", "minecraft:mud_brick_stairs", "minecraft:mud_brick_slab"),
		new Family("minecraft:sandstone", "minecraft:sandstone_stairs", "minecraft:sandstone_slab"),
		new Family("minecraft:polished_andesite", "minecraft:polished_andesite_stairs", "minecraft:polished_andesite_slab"),
		new Family("minecraft:deepslate_bricks", "minecraft:deepslate_brick_stairs", "minecraft:deepslate_brick_slab")
	);

	/** Wooden fence IDs; they all connect to each other. Generators add the connection properties. */
	public static final List<String> FENCES = List.of(
		"minecraft:oak_fence",
		"minecraft:spruce_fence",
		"minecraft:birch_fence",
		"minecraft:dark_oak_fence"
	);

	/** Persistent leaves, so they never decay. */
	public static final List<String> LEAVES = List.of(
		"minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]",
		"minecraft:birch_leaves[distance=7,persistent=true,waterlogged=false]",
		"minecraft:spruce_leaves[distance=7,persistent=true,waterlogged=false]",
		"minecraft:azalea_leaves[distance=7,persistent=true,waterlogged=false]"
	);

	/** Single-block plants; each stands on a {@link #GROUND} block. */
	public static final List<String> PLANTS = List.of(
		"minecraft:short_grass",
		"minecraft:fern",
		"minecraft:bush",
		"minecraft:poppy",
		"minecraft:dandelion",
		"minecraft:cornflower",
		"minecraft:oxeye_daisy"
	);

	/** Ground blocks that plants grow on and terraces are topped with. */
	public static final List<String> GROUND = List.of(
		"minecraft:grass_block[snowy=false]",
		"minecraft:moss_block",
		"minecraft:podzol[snowy=false]",
		"minecraft:coarse_dirt"
	);

	/** Fill below a terrace's ground block. */
	public static final List<String> TERRAIN_FILL = List.of(
		"minecraft:dirt",
		"minecraft:stone",
		"minecraft:packed_mud"
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
