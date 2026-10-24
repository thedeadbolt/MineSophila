package io.github.thedeadbolt.honeyfactory.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * What level-5 generation needs to know about a source patch's core: per cell, a few
 * properties of the block there, and per column, the surface. Coordinates are relative to
 * the core's minimum corner, {@code y = 0} being the bottom of the copied window. The
 * server derives every flag from the block state, so the same source gives the same
 * terrain.
 */
public final class PatchTerrain implements SightGrid {
	/** Not air. */
	public static final int OCCUPIED = 1;
	/** A full opaque cube, which blocks a sight-line estimate. */
	public static final int OCCLUDES = 2;
	/** Has a collision shape. */
	public static final int COLLIDES = 4;
	/** Holds a fluid. */
	public static final int FLUID = 8;
	/** Its top face can hold a block. */
	public static final int STURDY_TOP = 16;
	/** Air, or a non-fluid block a placement may replace, such as grass or snow. */
	public static final int REPLACEABLE = 32;
	public static final int LEAVES = 64;

	private final int width;
	private final int height;
	private final int length;
	private final byte[] flags;
	private final int[] surface;

	/**
	 * @param flags per cell, indexed {@code (y · length + z) · width + x}
	 * @param surface per column, indexed {@code z · width + x}: the lowest y above every
	 *     block that blocks motion, fluids included and leaves excluded
	 */
	public PatchTerrain(int width, int height, int length, byte[] flags, int[] surface) {
		if (flags.length != width * height * length || surface.length != width * length) {
			throw new IllegalArgumentException("Terrain arrays do not match " + width + "x" + height + "x" + length);
		}

		this.width = width;
		this.height = height;
		this.length = length;
		this.flags = flags.clone();
		this.surface = surface.clone();
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int length() {
		return this.length;
	}

	public boolean has(int x, int y, int z, int flag) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.height && z < this.length && (this.flags[(y * this.length + z) * this.width + x] & flag) != 0;
	}

	public int surface(int x, int z) {
		return this.surface[z * this.width + x];
	}

	@Override
	public boolean solid(int x, int y, int z) {
		return has(x, y, z, OCCUPIED);
	}

	/** Only full opaque cubes block an estimate on natural terrain, so it errs towards keeping views. */
	@Override
	public boolean blocks(int x, int y, int z, double[] from, double[] to) {
		return has(x, y, z, OCCLUDES);
	}

	/**
	 * Cells where a target may stand, one per column at most, ordered by z then x: the
	 * surface cell when it is {@link #REPLACEABLE} and not fluid, standing on a sturdy,
	 * non-fluid, non-leaf block inside the window.
	 */
	public List<int[]> targetSites() {
		List<int[]> sites = new ArrayList<>();
		for (int z = 0; z < this.length; z++) {
			for (int x = 0; x < this.width; x++) {
				int y = surface(x, z);
				if (y >= 1 && y < this.height && has(x, y, z, REPLACEABLE) && !has(x, y, z, FLUID)
					&& has(x, y - 1, z, STURDY_TOP) && !has(x, y - 1, z, FLUID) && !has(x, y - 1, z, LEAVES)) {
					sites.add(new int[] {x, y, z});
				}
			}
		}

		return sites;
	}
}
