package io.github.thedeadbolt.honeyfactory.scene;

import io.github.thedeadbolt.honeyfactory.contract.BlockLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable base geometry restored before a scene's block delta. Identified by ID,
 * version and the SHA-256 of its own blocks in arena-relative {@code layout-v1} form.
 */
public record Template(String id, int version, int width, int length, int height, List<Placement> blocks) {
	public Template {
		blocks = List.copyOf(blocks);
		for (Placement block : blocks) {
			if (block.x() < 0 || block.y() < 0 || block.z() < 0 || block.x() >= width || block.y() >= height || block.z() >= length) {
				throw new IllegalArgumentException("Template block outside its " + width + "x" + height + "x" + length + " box: " + block);
			}
		}
	}

	/** A flat floor covering the whole footprint at relative y = 0. */
	public static Template platform(int width, int length, int height, String floorState) {
		List<Placement> floor = new ArrayList<>(width * length);
		for (int z = 0; z < length; z++) {
			for (int x = 0; x < width; x++) {
				floor.add(new Placement(x, 0, z, floorState));
			}
		}

		return new Template("platform", 1, width, length, height, floor);
	}

	public String sha256() {
		return BlockLayout.sha256(
			new int[] {0, 0, 0},
			new int[] {this.width - 1, this.height - 1, this.length - 1},
			this.blocks.stream().map(block -> new BlockLayout.Block(block.x(), block.y(), block.z(), block.state())).toList()
		);
	}
}
