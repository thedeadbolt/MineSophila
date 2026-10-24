package io.github.thedeadbolt.honeyfactory.client.mixin;

import java.util.List;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the fog environments, which hold the client's eased fog state. */
@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
	@Accessor("FOG_ENVIRONMENTS")
	static List<FogEnvironment> honeyFactory$fogEnvironments() {
		throw new AssertionError();
	}
}
