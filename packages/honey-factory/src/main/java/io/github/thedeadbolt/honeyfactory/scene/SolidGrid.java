package io.github.thedeadbolt.honeyfactory.scene;

/**
 * Which arena-relative cells of a scene plan hold a block once its template and delta are
 * applied. Levels 1–3 place only full opaque cubes, so an occupied cell is also a solid,
 * opaque one. Cells outside the arena core are empty.
 */
public final class SolidGrid {
	private final int width;
	private final int height;
	private final int length;
	private final boolean[] cells;

	private SolidGrid(int width, int height, int length) {
		this.width = width;
		this.height = height;
		this.length = length;
		this.cells = new boolean[width * height * length];
	}

	public static SolidGrid of(ScenePlan plan) {
		Template template = plan.template();
		SolidGrid grid = new SolidGrid(template.width(), template.height(), template.length());
		template.blocks().forEach(grid::apply);
		plan.delta().forEach(grid::apply);
		return grid;
	}

	private void apply(Placement placement) {
		this.cells[index(placement.x(), placement.y(), placement.z())] = !placement.state().equals(Placement.AIR);
	}

	public boolean solid(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.width && y < this.height && z < this.length && this.cells[index(x, y, z)];
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

	private int index(int x, int y, int z) {
		return (y * this.length + z) * this.width + x;
	}
}
