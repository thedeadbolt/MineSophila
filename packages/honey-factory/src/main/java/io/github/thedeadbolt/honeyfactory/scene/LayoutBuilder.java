package io.github.thedeadbolt.honeyfactory.scene;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Collects a generated layout's placements as columns standing on the platform. Cells
 * outside the arena are dropped silently, as are blocks above its build height, so a
 * layout drawn for the default arena also fits a smaller one. The aim cell is never filled.
 */
final class LayoutBuilder {
	/** Draws of a free cell before giving up. */
	static final int CELL_TRIES = 32;

	private final int width;
	private final int length;
	private final int height;
	private final int[] aim;
	private final boolean[] used;
	private final List<Placement> placements = new ArrayList<>();

	LayoutBuilder(int width, int length, int height, int[] aim) {
		this.width = width;
		this.length = length;
		this.height = height;
		this.aim = aim;
		this.used = new boolean[width * length];
	}

	/** Whether {@code (x, z)} is inside the footprint, unused and at least {@code clearance} cells (Chebyshev) from the aim. */
	boolean free(int x, int z, int clearance) {
		return inside(x, z) && !this.used[z * this.width + x] && Math.max(Math.abs(x - this.aim[0]), Math.abs(z - this.aim[2])) >= clearance;
	}

	/**
	 * A free cell at least {@code clearance} from the aim, drawn as {@code nextInt(width)},
	 * {@code nextInt(length)} up to {@value #CELL_TRIES} times, or null when none was free.
	 */
	int @Nullable [] freeCell(SeededRandom random, int clearance) {
		for (int attempt = 0; attempt < CELL_TRIES; attempt++) {
			int x = random.nextInt(this.width);
			int z = random.nextInt(this.length);
			if (free(x, z, clearance)) {
				return new int[] {x, z};
			}
		}

		return null;
	}

	/** Stacks {@code blocks} blocks of {@code state} on the platform at {@code (x, z)}, from the ground up. */
	void column(int x, int z, int blocks, String state) {
		column(x, z, PlatformGenerator.GROUND_Y, blocks, state);
	}

	/** Stacks {@code blocks} blocks of {@code state} at {@code (x, z)} from {@code fromY} up. */
	void column(int x, int z, int fromY, int blocks, String state) {
		if (!inside(x, z) || (x == this.aim[0] && z == this.aim[2] && fromY <= this.aim[1])) {
			return;
		}

		this.used[z * this.width + x] = true;
		for (int y = fromY; y < fromY + blocks && y < this.height; y++) {
			this.placements.add(new Placement(x, y, z, state));
		}
	}

	List<Placement> placements() {
		return List.copyOf(this.placements);
	}

	private boolean inside(int x, int z) {
		return x >= 0 && z >= 0 && x < this.width && z < this.length;
	}
}
