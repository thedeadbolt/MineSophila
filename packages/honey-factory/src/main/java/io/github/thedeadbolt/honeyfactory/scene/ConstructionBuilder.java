package io.github.thedeadbolt.honeyfactory.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Collects a level-4 layout block by block on the platform. A later block replaces an
 * earlier one at the same cell but keeps that cell's place in the order. Cells outside the
 * arena, above its build height or in the aim cell are dropped silently, so a layout drawn
 * for the default arena also fits a smaller one. Floor cells ({@code y = 0}) may be
 * replaced, for ground blocks under plants.
 *
 * <p>{@link #placements()} gives every wooden fence its connections: a side connects when
 * the neighbouring cell holds a wooden fence or a full cube ({@link BlockShapes#fullCube}).
 * The target never counts, so both target classes share one layout.
 */
final class ConstructionBuilder {
	private final int width;
	private final int length;
	private final int height;
	private final int[] aim;
	private final Map<Integer, String> cells = new LinkedHashMap<>();

	ConstructionBuilder(int width, int length, int height, int[] aim) {
		this.width = width;
		this.length = length;
		this.height = height;
		this.aim = aim;
	}

	boolean inside(int x, int z) {
		return x >= 0 && z >= 0 && x < this.width && z < this.length;
	}

	/** Whether the ground-level cell {@code (x, 1, z)} is inside, empty, not the aim and at least {@code clearance} cells (Chebyshev) from it. */
	boolean free(int x, int z, int clearance) {
		return inside(x, z) && empty(x, PlatformGenerator.GROUND_Y, z) && Math.max(Math.abs(x - this.aim[0]), Math.abs(z - this.aim[2])) >= clearance;
	}

	/** Whether {@code (x, y, z)} holds no generated block; the floor template does not count. */
	boolean empty(int x, int y, int z) {
		return !this.cells.containsKey(index(x, y, z));
	}

	/** The generated state at {@code (x, y, z)}, or null. */
	@Nullable String at(int x, int y, int z) {
		return inside(x, z) && y >= 0 && y < this.height ? this.cells.get(index(x, y, z)) : null;
	}

	void set(int x, int y, int z, String state) {
		if (!inside(x, z) || y < 0 || y >= this.height || (x == this.aim[0] && y == this.aim[1] && z == this.aim[2])) {
			return;
		}

		this.cells.put(index(x, y, z), state);
	}

	void remove(int x, int y, int z) {
		this.cells.remove(index(x, y, z));
	}

	/** Stacks {@code blocks} blocks of {@code state} at {@code (x, z)} from {@code fromY} up. */
	void column(int x, int z, int fromY, int blocks, String state) {
		for (int y = fromY; y < fromY + blocks; y++) {
			set(x, y, z, state);
		}
	}

	/** A plant on ground at floor level: the floor cell becomes {@code ground}. Skipped when the cell above is taken. */
	void plant(int x, int z, String ground, String plant) {
		if (!inside(x, z) || !empty(x, PlatformGenerator.GROUND_Y, z) || (x == this.aim[0] && z == this.aim[2])) {
			return;
		}

		set(x, 0, z, ground);
		set(x, PlatformGenerator.GROUND_Y, z, plant);
	}

	/**
	 * A free ground-level cell at least {@code clearance} from the aim, drawn as
	 * {@code nextInt(width)}, {@code nextInt(length)} up to {@value LayoutBuilder#CELL_TRIES}
	 * times, or null when none was free.
	 */
	int @Nullable [] freeCell(SeededRandom random, int clearance) {
		for (int attempt = 0; attempt < LayoutBuilder.CELL_TRIES; attempt++) {
			int x = random.nextInt(this.width);
			int z = random.nextInt(this.length);
			if (free(x, z, clearance)) {
				return new int[] {x, z};
			}
		}

		return null;
	}

	/** Every generated block in order, wooden fences connected to their neighbours. */
	List<Placement> placements() {
		List<Placement> placements = new ArrayList<>(this.cells.size());
		this.cells.forEach((index, state) -> {
			int x = index % this.width;
			int z = index / this.width % this.length;
			int y = index / (this.width * this.length);
			placements.add(new Placement(x, y, z, woodenFence(state) ? connected(state, x, y, z) : state));
		});
		return List.copyOf(placements);
	}

	private String connected(String fence, int x, int y, int z) {
		return BlockShapes.blockId(fence)
			+ "[east=" + connects(x + 1, y, z)
			+ ",north=" + connects(x, y, z - 1)
			+ ",south=" + connects(x, y, z + 1)
			+ ",waterlogged=false"
			+ ",west=" + connects(x - 1, y, z) + "]";
	}

	private boolean connects(int x, int y, int z) {
		String neighbour = at(x, y, z);
		return neighbour != null && (woodenFence(neighbour) || BlockShapes.fullCube(neighbour));
	}

	private static boolean woodenFence(String state) {
		return BlockPalettes.FENCES.contains(BlockShapes.blockId(state));
	}

	private int index(int x, int y, int z) {
		return (y * this.length + z) * this.width + x;
	}
}
