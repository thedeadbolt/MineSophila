package io.github.thedeadbolt.honeyfactory.scene;

/** Arena-relative cells a {@link SightLines} estimate walks through. */
public interface SightGrid {
	/** Whether the cell holds any block. */
	boolean solid(int x, int y, int z);

	/** Whether a body bumps into the cell's block or stands on it; plants, for one, it walks through. */
	default boolean collides(int x, int y, int z) {
		return solid(x, y, z);
	}

	/**
	 * Whether the cell's block rises above the cell to a body, as a fence or wall does (1.5
	 * blocks): the cell above it is closed too, so it is never stepped up or stood on.
	 */
	default boolean tall(int x, int y, int z) {
		return false;
	}

	/**
	 * Whether the cell's drawn geometry crosses the segment from {@code from} to {@code to};
	 * callers pass only cells the segment crosses.
	 */
	boolean blocks(int x, int y, int z, double[] from, double[] to);
}
