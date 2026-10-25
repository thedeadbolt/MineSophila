package io.github.thedeadbolt.honeyfactory.client.mixin;

import io.github.thedeadbolt.honeyfactory.client.runtime.TickStepper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the tick stepper holds, ignores mouse turns of the local player. The mouse turns
 * the player every frame, outside its tick, so it would otherwise move a held body.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void honeyFactory$holdRotation(double yaw, double pitch, CallbackInfo callback) {
		if (TickStepper.get().held() && (Object) this == Minecraft.getInstance().player) {
			callback.cancel();
		}
	}
}
