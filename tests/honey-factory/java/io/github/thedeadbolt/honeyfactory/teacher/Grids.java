package io.github.thedeadbolt.honeyfactory.teacher;

import io.github.thedeadbolt.honeyfactory.contract.BlockBox;
import io.github.thedeadbolt.honeyfactory.scene.SightGrid;
import java.util.HashSet;
import java.util.Set;

/** Small block worlds for teacher tests: a floor at y = 0 inside a core, plus any blocks added. */
final class Grids implements SightGrid {
	final BlockBox core;
	private final Set<Long> blocks = new HashSet<>();

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

	@Override
	public boolean solid(int x, int y, int z) {
		return this.blocks.contains(SeenMap.key(x, y, z));
	}

	@Override
	public boolean blocks(int x, int y, int z, double[] from, double[] to) {
		return solid(x, y, z);
	}
}
