package io.github.thedeadbolt.honeyfactory.client.runtime;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Player input driven by a step instead of the keyboard. The player polls it once per
 * player tick, so {@link #hold} holds forward for exactly that many ticks, after which
 * every input is released. Minecraft's auto-jump presses jump through {@link #makeJump},
 * which counts those presses.
 */
final class ScriptedInput extends ClientInput {
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

	private int remaining;
	private int ticked;
	private int held;
	private int autoJumps;

	/** Holds forward for the next {@code ticks} player ticks. */
	void hold(int ticks) {
		this.remaining = ticks;
	}

	/** Releases every input now. */
	void release() {
		this.remaining = 0;
		this.keyPresses = Input.EMPTY;
		this.moveVector = Vec2.ZERO;
	}

	/** Player ticks that have polled this input. */
	int ticked() {
		return this.ticked;
	}

	/** Player ticks forward has been held for. */
	int held() {
		return this.held;
	}

	/** Jumps auto-jump has made. */
	int autoJumps() {
		return this.autoJumps;
	}

	boolean released() {
		return this.remaining == 0 && this.keyPresses.equals(Input.EMPTY) && this.moveVector.equals(Vec2.ZERO);
	}

	@Override
	public void tick() {
		this.ticked++;
		if (this.remaining > 0) {
			this.remaining--;
			this.held++;
			this.keyPresses = FORWARD;
			this.moveVector = new Vec2(0.0F, 1.0F);
		} else {
			release();
		}
	}

	@Override
	public void makeJump() {
		this.autoJumps++;
		super.makeJump();
	}
}
