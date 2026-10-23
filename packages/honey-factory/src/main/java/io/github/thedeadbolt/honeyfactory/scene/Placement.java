package io.github.thedeadbolt.honeyfactory.scene;

/**
 * One block set at an arena-relative position. {@code state} is a canonical block state
 * as defined by the {@code layout-v1} contract; {@code minecraft:air} removes a block.
 */
public record Placement(int x, int y, int z, String state) {
	public static final String AIR = "minecraft:air";
}
