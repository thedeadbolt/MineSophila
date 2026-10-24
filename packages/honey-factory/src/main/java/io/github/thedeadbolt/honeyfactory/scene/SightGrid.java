package io.github.thedeadbolt.honeyfactory.scene;

/** Arena-relative cells a {@link SightLines} estimate walks through. */
public interface SightGrid {
	/** Whether the cell holds any block. */
	boolean solid(int x, int y, int z);

	/**
	 * Whether the cell's drawn geometry crosses the segment from {@code from} to {@code to};
	 * callers pass only cells the segment crosses.
	 */
	boolean blocks(int x, int y, int z, double[] from, double[] to);
}
