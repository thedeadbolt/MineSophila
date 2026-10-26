package io.github.thedeadbolt.honeyfactory.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the global settings uniform, which holds the camera's block and offset that
 * terrain shaders place sections with. The game writes it only in a full frame render, so a
 * capture that renders the level alone must write it itself.
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform honeyFactory$globalSettingsUniform();
}
