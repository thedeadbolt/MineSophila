package io.github.thedeadbolt.honeyfactory.client.mixin;

import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a capture renders, uses the capture projection's vertical field of view instead
 * of panoramic mode's fixed 90 degrees. Camera.update passes this value to the projection
 * matrix with the window's aspect ratio, so the horizontal field of view stays exact.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void honeyFactory$captureFov(float partialTicks, CallbackInfoReturnable<Float> callback) {
		Float override = CaptureService.verticalFovOverride();
		if (override != null) {
			callback.setReturnValue(override);
		}
	}
}
