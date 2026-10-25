package io.github.thedeadbolt.honeyfactory.client.runtime;

import io.github.thedeadbolt.honeyfactory.control.MotorCommand;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Player input driven by a control step's command: fractional forward and sideways
 * impulses through the input vector, as an analogue stick would give, and jump. The keys
 * follow the impulses' signs, so sprinting and sneaking stay off.
 */
final class MotorInput extends ClientInput {
	private MotorCommand command = MotorCommand.IDLE;

	void set(MotorCommand command) {
		this.command = command;
	}

	@Override
	public void tick() {
		double forward = this.command.forward();
		double strafe = this.command.strafe();
		this.keyPresses = new Input(forward > 0.0, forward < 0.0, strafe < 0.0, strafe > 0.0, this.command.jump(), false, false);
		// Minecraft's x impulse is positive to the left.
		this.moveVector = new Vec2((float) -strafe, (float) forward);
	}
}
