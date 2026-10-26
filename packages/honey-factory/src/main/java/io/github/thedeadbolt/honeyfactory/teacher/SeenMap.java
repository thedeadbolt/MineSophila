package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.capture.CameraFrame;
import io.github.thedeadbolt.honeyfactory.capture.CaptureProjection;
import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.environment.CameraPose;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import io.github.thedeadbolt.honeyfactory.scene.SightLines;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which cells of the arena have ever been in view: inside the camera's projection, within
 * {@value #RANGE_BLOCKS} blocks, with a clear line from the eye to the cell's centre. Cells
 * are arena-relative, as the scene's {@link SightGrid} indexes them; poses are absolute and
 * converted with the core's minimum corner.
 *
 * <p>This is the teacher's only knowledge of the scene's layout: it plans through blocks it
 * has seen, and searches towards the edge of what it has seen.
 */
public final class SeenMap {
	static final double RANGE_BLOCKS = 32.0;
	/** A step up the body can take (auto-jump) and a drop it may take, in blocks. */
	static final int STEP_UP = 1;
	static final int MAX_DROP = 3;
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private final int[] origin;
	private final int width;
	private final int height;
	private final int length;
	private final boolean[] seen;
	private int count;

	/** An empty map of {@code core}, the arena's core in absolute coordinates. */
	public SeenMap(BlockBox core) {
		this.origin = core.min();
		this.width = core.maxX() - core.minX() + 1;
		this.height = core.maxY() - core.minY() + 1;
		this.length = core.maxZ() - core.minZ() + 1;
		this.seen = new boolean[this.width * this.height * this.length];
	}

	/** A cell's key in sets of cells. */
	public static long key(int x, int y, int z) {
		return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
	}

	/** Marks every cell the camera at {@code pose} sees through {@code projection}. */
	public void observe(CameraPose pose, double eyeHeight, CaptureProjection projection, SightGrid grid) {
		CameraFrame camera = CameraFrame.of(pose, eyeHeight);
		double[] eye = {camera.eye()[0] - this.origin[0], camera.eye()[1] - this.origin[1], camera.eye()[2] - this.origin[2]};
		int reach = (int) Math.ceil(RANGE_BLOCKS);
		int minX = Math.max(0, (int) Math.floor(eye[0]) - reach);
		int maxX = Math.min(this.width - 1, (int) Math.floor(eye[0]) + reach);
		int minY = Math.max(0, (int) Math.floor(eye[1]) - reach);
		int maxY = Math.min(this.height - 1, (int) Math.floor(eye[1]) + reach);
		int minZ = Math.max(0, (int) Math.floor(eye[2]) - reach);
		int maxZ = Math.min(this.length - 1, (int) Math.floor(eye[2]) + reach);
		double range = RANGE_BLOCKS * RANGE_BLOCKS;
		for (int y = minY; y <= maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					int index = index(x, y, z);
					if (this.seen[index]) {
						continue;
					}

					double dx = x + 0.5 - eye[0];
					double dy = y + 0.5 - eye[1];
					double dz = z + 0.5 - eye[2];
					if (dx * dx + dy * dy + dz * dz > range) {
						continue;
					}

					BlockBox cell = BlockBox.cell(new int[] {x + this.origin[0], y + this.origin[1], z + this.origin[2]});
					if (projection.intersects(camera, cell) && SightLines.sees(grid, eye, new int[] {x, y, z})) {
						this.seen[index] = true;
						this.count++;
					}
				}
			}
		}
	}

	/** Whether arena-relative cell {@code (x, y, z)} has been in view. */
	public boolean seen(int x, int y, int z) {
		return inside(x, y, z) && this.seen[index(x, y, z)];
	}

	/** Cells seen so far. */
	public int count() {
		return this.count;
	}

	/** The share of {@code reachable} cells seen so far, in [0, 1]; 0 when none is reachable. */
	public double coverage(Set<Long> reachable) {
		if (reachable.isEmpty()) {
			return 0.0;
		}

		int seen = 0;
		for (int y = 0; y < this.height; y++) {
			for (int z = 0; z < this.length; z++) {
				for (int x = 0; x < this.width; x++) {
					if (this.seen[index(x, y, z)] && reachable.contains(key(x, y, z))) {
						seen++;
					}
				}
			}
		}

		return (double) seen / reachable.size();
	}

	/**
	 * Seen cells the body could stand in that border unseen space: the edge between what has
	 * been in view and what has not. Whether an unseen cell is walkable is unknown, so any
	 * unseen neighbour counts.
	 */
	public List<int[]> frontiers(SightGrid grid) {
		List<int[]> frontiers = new ArrayList<>();
		for (int y = 0; y < this.height; y++) {
			for (int z = 0; z < this.length; z++) {
				for (int x = 0; x < this.width; x++) {
					if (!this.seen[index(x, y, z)] || !walkable(grid, x, y, z)) {
						continue;
					}

					for (int[] side : SIDES) {
						int nx = x + side[0];
						int nz = z + side[1];
						if (inside(nx, y, nz) && !this.seen[index(nx, y, nz)]) {
							frontiers.add(new int[] {x, y, z});
							break;
						}
					}
				}
			}
		}

		return frontiers;
	}

	/** Whether the body could stand with its feet in {@code (x, y, z)}: air there and above, a block below. */
	public static boolean walkable(SightGrid grid, int x, int y, int z) {
		return !grid.solid(x, y, z) && !grid.solid(x, y + 1, z) && grid.solid(x, y - 1, z);
	}

	/**
	 * The walkable cells reachable from {@code start} within {@code core}'s extent, stepping
	 * to a side neighbour up to {@value #STEP_UP} block up or {@value #MAX_DROP} down. This
	 * uses the true scene, so it serves measurements, never the teacher's decisions.
	 */
	public static Set<Long> reachable(SightGrid grid, BlockBox core, int[] start) {
		int width = core.maxX() - core.minX() + 1;
		int height = core.maxY() - core.minY() + 1;
		int length = core.maxZ() - core.minZ() + 1;
		Set<Long> found = new HashSet<>();
		Deque<int[]> queue = new ArrayDeque<>();
		int[] first = ground(grid, start[0], start[1], start[2], height);
		if (first == null) {
			return found;
		}

		found.add(key(first[0], first[1], first[2]));
		queue.add(first);
		while (!queue.isEmpty()) {
			int[] cell = queue.removeFirst();
			for (int[] side : SIDES) {
				int x = cell[0] + side[0];
				int z = cell[2] + side[1];
				if (x < 0 || z < 0 || x >= width || z >= length) {
					continue;
				}

				for (int y = cell[1] + STEP_UP; y >= cell[1] - MAX_DROP; y--) {
					if (y >= 0 && y < height && walkable(grid, x, y, z)) {
						if (found.add(key(x, y, z))) {
							queue.add(new int[] {x, y, z});
						}

						break;
					}
				}
			}
		}

		return found;
	}

	/** The walkable cell at or just below {@code (x, y, z)}, where a body standing there has its feet. */
	private static int[] ground(SightGrid grid, int x, int y, int z, int height) {
		for (int at = Math.min(y + STEP_UP, height - 1); at >= Math.max(0, y - MAX_DROP); at--) {
			if (walkable(grid, x, at, z)) {
				return new int[] {x, at, z};
			}
		}

		return null;
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.height && z < this.length;
	}

	private int index(int x, int y, int z) {
		return (y * this.length + z) * this.width + x;
	}
}
