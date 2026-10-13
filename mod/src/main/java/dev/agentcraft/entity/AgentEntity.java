package dev.agentcraft.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;

/**
 * An AgentCraft agent NPC. Agents are <b>client-side only</b> (architecture A, see mod/DEV.md):
 * the client creates one {@code ClientAgentEntity} per Foreman agent in its own level and moves it
 * itself from the Foreman state. This common base exists only because entity types are registered
 * in the shared registry. The type is {@code noSummon}/{@code noSave}; a server-side instance (from
 * a stray command or old save) removes itself on its first tick.
 *
 * <p>It extends {@link Avatar} so the vanilla player model renderer ({@code AvatarRenderer}) can
 * draw it with both skin layers and slim/wide arms.
 */
public class AgentEntity extends Avatar {
	/** Every skin overlay layer on (hat, jacket, sleeves, trousers); no cape. */
	public static final byte ALL_LAYERS_NO_CAPE = (byte) (PlayerModelPart.JACKET.getMask() | PlayerModelPart.LEFT_SLEEVE.getMask()
		| PlayerModelPart.RIGHT_SLEEVE.getMask() | PlayerModelPart.LEFT_PANTS_LEG.getMask() | PlayerModelPart.RIGHT_PANTS_LEG.getMask()
		| PlayerModelPart.HAT.getMask());

	public AgentEntity(EntityType<? extends AgentEntity> type, Level level) {
		super(type, level);
		this.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_LAYERS_NO_CAPE);
		this.setNoGravity(true);
	}

	@Override
	public ResolvableProfile getProfile() {
		return ResolvableProfile.Static.EMPTY;
	}

	@Override
	public void tick() {
		if (!level().isClientSide()) {
			// Agents only exist on the client. Never let one live on the server.
			discard();
			return;
		}
		super.tick();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(Entity entity) {
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean shouldShowName() {
		// Nameplates are drawn by the agents feature, not vanilla's name tag.
		return false;
	}
}
