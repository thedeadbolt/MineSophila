package io.github.thedeadbolt.honeyfactory.client.mixin;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes which sections need rebuilding. The extractor rebuilds only sections in view, so
 * a section the camera has not looked at since its blocks changed keeps its old mesh until
 * a frame looks at it.
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
	@Accessor("sectionUpdateTracker")
	@Nullable SectionUpdateTracker honeyFactory$sectionUpdateTracker();
}
