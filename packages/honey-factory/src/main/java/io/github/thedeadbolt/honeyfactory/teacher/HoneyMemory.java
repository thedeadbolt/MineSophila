package io.github.thedeadbolt.honeyfactory.teacher;

import org.jspecify.annotations.Nullable;

/** Where the teacher last saw the honey, and when. */
public final class HoneyMemory {
	private int @Nullable [] lastSeen;
	private int seenTick = -1;

	public void sawAt(int[] cell, int tick) {
		this.lastSeen = cell.clone();
		this.seenTick = tick;
	}

	/** The honey's cell when last seen, or null if never seen. */
	public int @Nullable [] lastSeen() {
		return this.lastSeen == null ? null : this.lastSeen.clone();
	}

	/** The tick it was last seen at, or -1. */
	public int seenTick() {
		return this.seenTick;
	}

	/** Ticks since it was last seen, or {@link Integer#MAX_VALUE} if never. */
	public int ticksSinceSeen(int tick) {
		return this.lastSeen == null ? Integer.MAX_VALUE : tick - this.seenTick;
	}
}
