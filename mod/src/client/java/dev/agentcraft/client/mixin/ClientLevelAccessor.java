package dev.agentcraft.client.mixin;

import java.util.Deque;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {
	@Accessor("lightUpdateQueue")
	Deque<Runnable> agentcraft$getLightUpdateQueue();
}
