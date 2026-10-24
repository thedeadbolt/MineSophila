package io.github.thedeadbolt.honeyfactory.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which arena-relative cells of a scene plan hold a block once its template and delta are
 * applied, and what each block draws ({@link BlockShapes}). An occupied cell is
 * {@link #solid}; whether it hides what lies behind it is {@link #blocks}. Levels 1–3
 * place only full opaque cubes, so for them the two agree. Cells outside the arena core
 * are empty.
 */
public final class SolidGrid implements SightGrid {
	private final int width;
	private final int height;
	private final int length;
	/** Index into {@link #shapes} plus one per cell; 0 is air. */
	private final short[] cells;
	private final List<List<BlockShapes.Box>> shapes = new ArrayList<>();
	private final Map<String, Short> palette = new HashMap<>();

	private SolidGrid(int width, int height, int length) {
		this.width = width;
		this.height = height;
		this.length = length;
		this.cells = new short[width * height * length];
	}

	public static SolidGrid of(ScenePlan plan) {
		Template template = plan.template();
		SolidGrid grid = new SolidGrid(template.width(), template.height(), template.length());
		template.blocks().forEach(grid::apply);
		plan.delta().forEach(grid::apply);
		return grid;
	}

	private void apply(Placement placement) {
		this.cells[index(placement.x(), placement.y(), placement.z())] = placement.state().equals(Placement.AIR) ? 0 : paletteIndex(placement.state());
	}

	private short paletteIndex(String state) {
		return this.palette.computeIfAbsent(state, key -> {
			this.shapes.add(BlockShapes.boxes(key));
			return (short) this.shapes.size();
		});
	}

	@Override
	public boolean solid(int x, int y, int z) {
		return inside(x, y, z) && this.cells[index(x, y, z)] != 0;
	}

	/**
	 * Whether the drawn geometry of cell {@code (x, y, z)} crosses the segment from
	 * {@code from} to {@code to}; see-through blocks never do. Callers pass only cells the
	 * segment crosses, so a full cube always blocks.
	 */
	@Override
	public boolean blocks(int x, int y, int z, double[] from, double[] to) {
		if (!inside(x, y, z) || this.cells[index(x, y, z)] == 0) {
			return false;
		}

		List<BlockShapes.Box> boxes = this.shapes.get(this.cells[index(x, y, z)] - 1);
		if (boxes == BlockShapes.FULL) {
			return true;
		}

		for (BlockShapes.Box box : boxes) {
			if (crosses(from, to, x + box.minX() / 16.0, y + box.minY() / 16.0, z + box.minZ() / 16.0, x + box.maxX() / 16.0, y + box.maxY() / 16.0, z + box.maxZ() / 16.0)) {
				return true;
			}
		}

		return false;
	}

	/** Whether any solid cell overlaps the open box {@code (min, max)}; touching faces do not count. */
	public boolean intersects(double[] min, double[] max) {
		for (int y = (int) Math.floor(min[1]); y < max[1]; y++) {
			for (int z = (int) Math.floor(min[2]); z < max[2]; z++) {
				for (int x = (int) Math.floor(min[0]); x < max[0]; x++) {
					if (solid(x, y, z)) {
						return true;
					}
				}
			}
		}

		return false;
	}

	/** Slab test: whether the segment meets the closed box. */
	private static boolean crosses(double[] from, double[] to, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double[] min = {minX, minY, minZ};
		double[] max = {maxX, maxY, maxZ};
		double near = 0.0;
		double far = 1.0;
		for (int axis = 0; axis < 3; axis++) {
			double direction = to[axis] - from[axis];
			if (direction == 0.0) {
				if (from[axis] < min[axis] || from[axis] > max[axis]) {
					return false;
				}

				continue;
			}

			double t1 = (min[axis] - from[axis]) / direction;
			double t2 = (max[axis] - from[axis]) / direction;
			near = Math.max(near, Math.min(t1, t2));
			far = Math.min(far, Math.max(t1, t2));
			if (near > far) {
				return false;
			}
		}

		return true;
	}

	private boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.height && z < this.length;
	}

	private int index(int x, int y, int z) {
		return (y * this.length + z) * this.width + x;
	}
}
