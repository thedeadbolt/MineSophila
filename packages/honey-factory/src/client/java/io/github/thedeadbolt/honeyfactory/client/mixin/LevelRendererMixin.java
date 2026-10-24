package io.github.thedeadbolt.honeyfactory.client.mixin;

import io.github.thedeadbolt.honeyfactory.client.capture.CaptureService;
import io.github.thedeadbolt.honeyfactory.client.capture.TargetMaskRenderer;
import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the dataset arena, rebuilds changed sections synchronously in the frame that finds
 * them. Asynchronous rebuilds leave the previous mesh on screen until a worker finishes,
 * so a capture taken right after a scene reset could show blocks that no longer exist.
 *
 * <p>Captures leave particles out. They are random and transient, and opaque ones write
 * the scene depth the target masks are tested against, so a falling leaf in front of the
 * target would remove label pixels that no scene geometry hides.
 *
 * <p>As the translucent stages begin, the scene depth is handed to the target masks; see
 * {@link TargetMaskRenderer}.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Redirect(
		method = "compileSections",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileAsync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V")
	)
	private void honeyFactory$compileArenaSynchronously(SectionRenderDispatcher.RenderSection section, RenderSectionRegion region) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.level.dimension().equals(ArenaDimension.LEVEL)) {
			section.compileSync(region);
		} else {
			section.compileAsync(region);
		}
	}

	@Redirect(
		method = "submitFeatures",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
	)
	private void honeyFactory$skipParticlesInCaptures(ParticlesRenderState particles, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!CaptureService.capturing()) {
			particles.submit(collector, camera);
		}
	}

	@Inject(method = "executeOit", at = @At("HEAD"))
	private void honeyFactory$retainSceneDepth(CallbackInfo callback) {
		TargetMaskRenderer.retainSceneDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
	}
}
