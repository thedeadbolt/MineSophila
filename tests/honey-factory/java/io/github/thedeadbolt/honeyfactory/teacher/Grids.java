package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import java.util.HashSet;
import java.util.Set;

/** Small block worlds for teacher tests: a floor at y = 0 inside a core, plus any blocks added. */
final class Grids implements SightGrid {
	final BlockBox core;
	private final Set<Long> blocks = new HashSet<>();
	/** Blocks whose collision rises 1.5 high, such as fences. */
	private final Set<Long> tall = new HashSet<>();
	/** Blocks a body walks through, such as plants. */
	private final Set<Long> open = new HashSet<>();

	Grids(int width, int height, int length) {
		this.core = new BlockBox(0, 0, 0, width - 1, height - 1, length - 1);
		for (int x = 0; x < width; x++) {
			for (int z = 0; z < length; z++) {
				this.blocks.add(SeenMap.key(x, 0, z));
			}
		}
	}

	/** Adds a block column from y = 1 to {@code top}. */
	Grids wall(int x, int z, int top) {
		for (int y = 1; y <= top; y++) {
			this.blocks.add(SeenMap.key(x, y, z));
		}

		return this;
	}

	/** Fills columns {@code x0..x1 × z0..z1} from y = 1 to {@code top}: a raised platform whose top is {@code top}. */
	Grids platform(int x0, int z0, int x1, int z1, int top) {
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				wall(x, z, top);
			}
		}

		return this;
	}

	/** Adds a fence post at y = 1, 1.5 blocks high to a body. */
	Grids fence(int x, int z) {
		this.blocks.add(SeenMap.key(x, 1, z));
		this.tall.add(SeenMap.key(x, 1, z));
		return this;
	}

	/** Adds a plant at y = 1, which a body walks through. */
	Grids plant(int x, int z) {
		this.blocks.add(SeenMap.key(x, 1, z));
		this.open.add(SeenMap.key(x, 1, z));
		return this;
	}

	/** Removes any blocks above the floor in column {@code (x, z)}, for a start position. */
	Grids clear(int x, int z) {
		for (int y = 1; y < this.core.maxY() + 1; y++) {
			this.blocks.remove(SeenMap.key(x, y, z));
		}

		return this;
	}

	@Override
	public boolean solid(int x, int y, int z) {
		return this.blocks.contains(SeenMap.key(x, y, z));
	}

	@Override
	public boolean collides(int x, int y, int z) {
		return solid(x, y, z) && !this.open.contains(SeenMap.key(x, y, z));
	}

	@Override
	public boolean tall(int x, int y, int z) {
		return this.tall.contains(SeenMap.key(x, y, z));
	}

	@Override
	public boolean blocks(int x, int y, int z, double[] from, double[] to) {
		return solid(x, y, z);
	}
}
