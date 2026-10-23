package io.github.thedeadbolt.honeyfactory.client.mixin;

import io.github.thedeadbolt.honeyfactory.world.ArenaDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In the dataset arena, rebuilds changed sections synchronously in the frame that finds
 * them. Asynchronous rebuilds leave the previous mesh on screen until a worker finishes,
 * so a capture taken right after a scene reset could show blocks that no longer exist.
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
}
