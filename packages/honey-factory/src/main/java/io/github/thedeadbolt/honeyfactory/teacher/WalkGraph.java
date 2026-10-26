package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the body could stand and step, as far as the teacher knows. Cells are
 * arena-relative. A block counts only once it has been seen; unseen space is planned as
 * open floor at the body's height, and every tick's new view corrects the plan. A drop is
 * planned only onto floor the teacher has seen: it never steps down blind, since it may not
 * climb back.
 */
public final class WalkGraph {
	/** A side step, its cost and whether it is diagonal. */
	private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
	private static final double DIAGONAL = Math.sqrt(2.0);
	private static final double CLIMB_COST = 0.5;
	private static final double DROP_COST = 0.2;
	/** Extra cost of a cell beside a known block, so paths keep the body's width off walls and corners. */
	private static final double WALL_COST = 0.6;

	private final SightGrid grid;
	private final SeenMap seen;
	private final int width;
	private final int height;
	private final int length;

	private WalkGraph(SightGrid grid, BlockBox core, SeenMap seen) {
		this.grid = grid;
		this.seen = seen;
		this.width = core.maxX() - core.minX() + 1;
		this.height = core.maxY() - core.minY() + 1;
		this.length = core.maxZ() - core.minZ() + 1;
	}

	public static WalkGraph of(SightGrid grid, BlockBox core, SeenMap seen) {
		return new WalkGraph(grid, core, seen);
	}

	/** One step to a neighbouring cell and what it costs. */
	public record Edge(int[] to, double cost) {
	}

	/** What the body would find entering a column from a given feet height, as far as the teacher has seen. */
	public enum Footing {
		/** Seen floor at the same height, one step up, or a drop of at most {@link SeenMap#MAX_DROP}. */
		SAFE,
		/** A block the body walks against; it stops or slides there, it does not fall. */
		BLOCKED,
		/** The floor is not seen yet. */
		UNKNOWN,
		/** Seen open space deeper than the body may drop. */
		LEDGE
	}

	/** A seen block in {@code (x, y, z)}; outside the arena counts as solid. */
	public boolean knownSolid(int x, int y, int z) {
		if (x < 0 || z < 0 || x >= this.width || z >= this.length || y < 0) {
			return true;
		}

		return y < this.height && this.seen.seen(x, y, z) && this.grid.solid(x, y, z);
	}

	/** Whether the body could stand with its feet in {@code (x, y, z)}: no known block there or above, and no known gap below. */
	public boolean walkable(int x, int y, int z) {
		if (y < 1 || y + 1 >= this.height || knownSolid(x, y, z) || knownSolid(x, y + 1, z)) {
			return false;
		}

		return !this.seen.seen(x, y - 1, z) || this.grid.solid(x, y - 1, z);
	}

	/** Whether the body could stand in {@code (x, y, z)} on floor the teacher has seen. */
	public boolean standable(int x, int y, int z) {
		return walkable(x, y, z) && this.seen.seen(x, y - 1, z) && this.grid.solid(x, y - 1, z);
	}

	/** What the body finds entering column {@code (x, z)} with its feet at {@code y}. */
	public Footing footing(int x, int y, int z) {
		if (!passable(x, y, z)) {
			return !knownSolid(x, y + 2, z) && standable(x, y + SeenMap.STEP_UP, z) ? Footing.SAFE : Footing.BLOCKED;
		}

		for (int drop = 0; drop <= SeenMap.MAX_DROP; drop++) {
			int feet = y - drop;
			if (feet < 1 || !this.seen.seen(x, feet - 1, z)) {
				return Footing.UNKNOWN;
			}

			if (this.grid.solid(x, feet - 1, z)) {
				return Footing.SAFE;
			}
		}

		return Footing.LEDGE;
	}

	/** Whether the body's height is clear in column {@code (x, z)} at feet height {@code y}. */
	public boolean passable(int x, int y, int z) {
		return !knownSolid(x, y, z) && !knownSolid(x, y + 1, z);
	}

	/**
	 * Steps from {@code cell} to each side and diagonal neighbour: level when the neighbour
	 * is walkable there, up one block (auto-jump) with head room, or down up to
	 * {@value SeenMap#MAX_DROP} onto seen floor. A diagonal needs both sides it passes clear.
	 */
	public List<Edge> edges(int[] cell) {
		List<Edge> edges = new ArrayList<>(8);
		int x = cell[0];
		int y = cell[1];
		int z = cell[2];
		for (int[] step : STEPS) {
			int nx = x + step[0];
			int nz = z + step[1];
			boolean diagonal = step[0] != 0 && step[1] != 0;
			if (diagonal && (!passable(x + step[0], y, z) || !passable(x, y, z + step[1]))) {
				continue;
			}

			double base = diagonal ? DIAGONAL : 1.0;
			if (walkable(nx, y, nz)) {
				edges.add(new Edge(new int[] {nx, y, nz}, base + nearWall(nx, y, nz)));
			} else if (!diagonal && !knownSolid(x, y + 2, z) && walkable(nx, y + SeenMap.STEP_UP, nz)) {
				edges.add(new Edge(new int[] {nx, y + SeenMap.STEP_UP, nz}, base + CLIMB_COST + nearWall(nx, y + SeenMap.STEP_UP, nz)));
			} else if (passable(nx, y, nz)) {
				for (int drop = 1; drop <= SeenMap.MAX_DROP; drop++) {
					if (standable(nx, y - drop, nz)) {
						edges.add(new Edge(new int[] {nx, y - drop, nz}, base + DROP_COST * drop + nearWall(nx, y - drop, nz)));
						break;
					}

					if (knownSolid(nx, y - drop, nz) || !this.seen.seen(nx, y - drop - 1, nz)) {
						break;
					}
				}
			}
		}

		return edges;
	}

	/** {@link #WALL_COST} when a known block stands beside {@code (x, y, z)} at the body's height, else 0. */
	private double nearWall(int x, int y, int z) {
		for (int[] step : STEPS) {
			if (!passable(x + step[0], y, z + step[1])) {
				return WALL_COST;
			}
		}

		return 0.0;
	}

	/**
	 * Whether a body of half-width {@code radius} could walk straight from {@code from} to
	 * {@code to} (arena-relative, feet height {@code y}) without touching a known block.
	 */
	public boolean straight(double[] from, double[] to, int y, double radius) {
		double dx = to[0] - from[0];
		double dz = to[2] - from[2];
		double length = Math.hypot(dx, dz);
		int steps = Math.max(1, (int) Math.ceil(length / 0.2));
		for (int i = 1; i <= steps; i++) {
			double px = from[0] + dx * i / steps;
			double pz = from[2] + dz * i / steps;
			for (double ox : new double[] {-radius, radius}) {
				for (double oz : new double[] {-radius, radius}) {
					if (!passable((int) Math.floor(px + ox), y, (int) Math.floor(pz + oz))) {
						return false;
					}
				}
			}
		}

		return true;
	}
}
