package io.github.thedeadbolt.honeyfactory.scene;

import java.util.Arrays;

/**
 * Estimates how much of a block cell an eye can see through a {@link SolidGrid}, so
 * generators can discard hopeless viewpoints before any capture. The saved mask, not
 * this estimate, decides whether a capture is accepted.
 *
 * <p>Each face of the cell that faces the eye is sampled at a 3 × 3 grid of points at
 * 0.15, 0.5 and 0.85 along its edges. A sample is clear when the segment from the eye to
 * it, nudged just inside the cell, crosses no solid cell other than the cell itself.
 * Coordinates are arena-relative.
 */
public final class SightLines {
	private static final double[] OFFSETS = {0.15, 0.5, 0.85};
	private static final double INSET = 1e-4;

	private SightLines() {
	}

	/** The fraction of samples that are clear, or 0 when the eye is inside the cell. */
	public static double clearFraction(SolidGrid grid, double[] eye, int[] cell) {
		int samples = 0;
		int clear = 0;
		for (int axis = 0; axis < 3; axis++) {
			for (int side = 0; side < 2; side++) {
				boolean facing = side == 1 ? eye[axis] > cell[axis] + 1 : eye[axis] < cell[axis];
				if (!facing) {
					continue;
				}

				int u = (axis + 1) % 3;
				int v = (axis + 2) % 3;
				for (double a : OFFSETS) {
					for (double b : OFFSETS) {
						double[] point = new double[3];
						point[axis] = cell[axis] + side + (side == 1 ? -INSET : INSET);
						point[u] = cell[u] + a;
						point[v] = cell[v] + b;
						samples++;
						clear += reaches(grid, eye, point, cell) ? 1 : 0;
					}
				}
			}
		}

		return samples == 0 ? 0.0 : (double) clear / samples;
	}

	/** Walks the cells the segment crosses (Amanatides–Woo) until it enters {@code target}. */
	private static boolean reaches(SolidGrid grid, double[] from, double[] to, int[] target) {
		int[] cell = new int[3];
		int[] step = new int[3];
		double[] next = new double[3];
		double[] delta = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			cell[axis] = (int) Math.floor(from[axis]);
			double direction = to[axis] - from[axis];
			if (direction == 0.0) {
				next[axis] = Double.POSITIVE_INFINITY;
				delta[axis] = Double.POSITIVE_INFINITY;
				continue;
			}

			step[axis] = direction > 0 ? 1 : -1;
			double boundary = direction > 0 ? cell[axis] + 1 : cell[axis];
			next[axis] = (boundary - from[axis]) / direction;
			delta[axis] = Math.abs(1.0 / direction);
		}

		while (true) {
			if (Arrays.equals(cell, target)) {
				return true;
			}

			if (grid.solid(cell[0], cell[1], cell[2])) {
				return false;
			}

			int axis = next[0] <= next[1] ? (next[0] <= next[2] ? 0 : 2) : (next[1] <= next[2] ? 1 : 2);
			if (next[axis] > 1.0) {
				return true;
			}

			cell[axis] += step[axis];
			next[axis] += delta[axis];
		}
	}
}
