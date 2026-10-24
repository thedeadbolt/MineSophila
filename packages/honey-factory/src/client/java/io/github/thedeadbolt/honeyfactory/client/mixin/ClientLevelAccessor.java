package io.github.thedeadbolt.honeyfactory.client.mixin;

import java.util.Deque;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the client's queue of received light data. Chunk and light packets are applied
 * from it a few per frame, and only then are their sections marked for rebuilding, so a
 * scene is not lit as the server lit it until the queue is empty.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	@Accessor("lightUpdateQueue")
	Deque<Runnable> honeyFactory$lightUpdateQueue();
}
