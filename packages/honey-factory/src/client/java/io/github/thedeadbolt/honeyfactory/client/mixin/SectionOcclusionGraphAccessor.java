package io.github.thedeadbolt.honeyfactory.client.mixin;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes whether the section occlusion graph, which decides which compiled sections are
 * drawn, is current. A camera move schedules a full rebuild on a background thread; until
 * it is done and its sections are applied to the frustum, frames are drawn with the graph
 * of the previous position and can leave out sections now in view.
 */
@Mixin(SectionOcclusionGraph.class)
public interface SectionOcclusionGraphAccessor {
	@Accessor("needsFullUpdate")
	boolean honeyFactory$needsFullUpdate();

	@Accessor("fullUpdateTask")
	@Nullable Future<?> honeyFactory$fullUpdateTask();

	@Accessor("needsFrustumUpdate")
	AtomicBoolean honeyFactory$needsFrustumUpdate();
}
