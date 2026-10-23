package io.github.thedeadbolt.honeyfactory.contract;

/** An inclusive, axis-aligned box of block positions. */
public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
	public BlockBox {
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			throw new IllegalArgumentException("Box minimum exceeds maximum: " + this);
		}
	}

	public static BlockBox of(int[] min, int[] max) {
		return new BlockBox(min[0], min[1], min[2], max[0], max[1], max[2]);
	}

	public boolean contains(int x, int y, int z) {
		return x >= this.minX && x <= this.maxX && y >= this.minY && y <= this.maxY && z >= this.minZ && z <= this.maxZ;
	}

	public boolean contains(BlockBox other) {
		return contains(other.minX, other.minY, other.minZ) && contains(other.maxX, other.maxY, other.maxZ);
	}

	public boolean intersects(BlockBox other) {
		return this.minX <= other.maxX && other.minX <= this.maxX
			&& this.minY <= other.maxY && other.minY <= this.maxY
			&& this.minZ <= other.maxZ && other.minZ <= this.maxZ;
	}

	public BlockBox offset(int dx, int dy, int dz) {
		return new BlockBox(this.minX + dx, this.minY + dy, this.minZ + dz, this.maxX + dx, this.maxY + dy, this.maxZ + dz);
	}

	public int[] min() {
		return new int[] {this.minX, this.minY, this.minZ};
	}

	public int[] max() {
		return new int[] {this.maxX, this.maxY, this.maxZ};
	}

	public long volume() {
		return (long) (this.maxX - this.minX + 1) * (this.maxY - this.minY + 1) * (this.maxZ - this.minZ + 1);
	}

	/** Chunk coordinates {@code [minChunkX, minChunkZ, maxChunkX, maxChunkZ]} covering the box. */
	public int[] chunkRange() {
		return new int[] {this.minX >> 4, this.minZ >> 4, this.maxX >> 4, this.maxZ >> 4};
	}
}
