package dev.agentcraft.entity;

import dev.agentcraft.AgentCraft;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> AGENT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AgentCraft.id("agent"));

	/**
	 * Client-only agent NPC (see {@link AgentEntity}). The factory builds the common class, which
	 * discards itself on a server; the client constructs its own subclass directly.
	 */
	public static final EntityType<AgentEntity> AGENT = Registry.register(BuiltInRegistries.ENTITY_TYPE, AGENT_KEY,
		EntityType.Builder.<AgentEntity>of(AgentEntity::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.eyeHeight(1.62F)
			.vehicleAttachment(Avatar.DEFAULT_VEHICLE_ATTACHMENT)
			.clientTrackingRange(10)
			.noSummon()
			.noSave()
			.build(AGENT_KEY));

	private ModEntities() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(AGENT, LivingEntity.createLivingAttributes());
	}
}
