package io.github.thedeadbolt.honeyfactory.client.mixin;

import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes how strongly rain thickens the atmospheric fog. Minecraft eases it towards the
 * current weather by a fifth per tick, separately from the rain level, so fog keeps
 * changing for a second or two after the weather does.
 */
@Mixin(AtmosphericFogEnvironment.class)
public interface AtmosphericFogAccessor {
	@Accessor("rainFogMultiplier")
	float honeyFactory$rainFogMultiplier();
}
