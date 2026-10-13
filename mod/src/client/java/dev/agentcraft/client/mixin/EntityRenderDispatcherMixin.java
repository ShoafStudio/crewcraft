package dev.agentcraft.client.mixin;

import dev.agentcraft.client.agents.AgentRenderState;
import dev.agentcraft.client.agents.AgentRenderer;
import dev.agentcraft.client.agents.ClientAgentEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes agents to {@link AgentRenderer}. Vanilla picks renderers per entity type, and sends every
 * {@link AvatarRenderState} to the <em>player</em> renderer at submit time; agents need their own
 * (slim or wide by skin, custom nameplate), so the entity lookup picks the variant by skin model and
 * the state lookup returns the renderer that extracted the state.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
		at = @At("HEAD"), cancellable = true)
	private void agentcraft$agentRenderer(Entity entity, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (entity instanceof ClientAgentEntity agent) {
			AgentRenderer r = AgentRenderer.forModel(agent.getSkin().model());
			if (r != null) {
				cir.setReturnValue(r);
			}
		}
	}

	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
		at = @At("HEAD"), cancellable = true)
	private void agentcraft$agentStateRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (state instanceof AgentRenderState s && s.renderer != null) {
			cir.setReturnValue(s.renderer);
		}
	}

	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/client/renderer/entity/player/AvatarRenderer;",
		at = @At("HEAD"), cancellable = true)
	private void agentcraft$agentAvatarRenderer(AvatarRenderState state, CallbackInfoReturnable<AvatarRenderer<?>> cir) {
		if (state instanceof AgentRenderState s && s.renderer != null) {
			cir.setReturnValue(s.renderer);
		}
	}
}
