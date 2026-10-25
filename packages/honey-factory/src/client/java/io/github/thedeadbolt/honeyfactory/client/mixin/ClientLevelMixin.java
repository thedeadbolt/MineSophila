package io.github.thedeadbolt.honeyfactory.client.mixin;

import io.github.thedeadbolt.honeyfactory.client.runtime.TickStepper;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the tick stepper holds, skips the local player's tick unless a step permits it.
 * Vanilla's tick freeze never freezes players, so without this the body would keep
 * falling and coasting between steps.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
	private void honeyFactory$holdPlayer(Entity entity, CallbackInfo callback) {
		if (TickStepper.get().skipsTick(entity)) {
			callback.cancel();
		}
	}
}
