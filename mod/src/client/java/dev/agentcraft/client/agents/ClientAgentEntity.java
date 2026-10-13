package dev.agentcraft.client.agents;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.entity.AgentEntity;
import dev.agentcraft.entity.ModEntities;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.animal.parrot.Parrot;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client-only agent NPC. Created and removed by {@link AgentManager}; never exists on the
 * server. It skips all of LivingEntity's physics/AI: each tick it only advances its
 * {@link AgentMotion}, updates the vanilla walk animation from the real distance moved, and runs
 * the {@link AgentHooks} tickers. That advance runs from the entity tick or, when another mod skips
 * ticking entities out of view (Entity Culling's tickCulling), from {@link AgentManager}'s client tick
 * ({@link TickGate}: never both in one tick).
 */
public class ClientAgentEntity extends AgentEntity implements ClientAvatarEntity {
	private final ClientAvatarState avatarState = new ClientAvatarState();
	private final AgentView view;
	private final AgentMotion motion = new AgentMotion();
	private final AgentLife life;
	private final TickGate gate = new TickGate();
	private PlayerSkin skin;
	private float headYaw;
	private float headPitch;
	private boolean headSet;

	public ClientAgentEntity(ClientLevel level, String agentId, PlayerSkin skin) {
		super(ModEntities.AGENT, level);
		this.view = new AgentView(agentId);
		this.skin = skin;
		this.life = new AgentLife(this);
	}

	/** Posture, head look, particles and speech of this agent (client thread). */
	public AgentLife life() {
		return life;
	}

	/** Absolute head yaw and pitch for this tick (set by {@link AgentLife}; vanilla interpolates). */
	void setHeadLook(float yaw, float pitch) {
		headYaw = yaw;
		headPitch = pitch;
		headSet = true;
		this.yHeadRot = yaw;
		this.setXRot(pitch);
	}

	public String agentId() {
		return view.id;
	}

	public AgentView view() {
		return view;
	}

	public AgentMotion motion() {
		return motion;
	}

	void setSkin(PlayerSkin skin) {
		this.skin = skin;
	}

	/** Teleport (no interpolation) to a feet position with a body yaw. */
	void snapTo(Vec3 p, float yaw) {
		this.snapTo(p.x, p.y, p.z, yaw, 0f);
		this.setOldPosAndRot();
		this.yBodyRot = yaw;
		this.yBodyRotO = yaw;
		this.yHeadRot = yaw;
		this.yHeadRotO = yaw;
		this.headYaw = yaw;
		this.setDeltaMovement(Vec3.ZERO);
	}

	/**
	 * Dev only ({@code dev.agents.freezeEntityTick}): skip the entity tick like Entity Culling's
	 * tickCulling does for entities out of view, so the catch-up path can be exercised without that mod.
	 */
	static volatile boolean freezeEntityTick;

	public TickGate gate() {
		return gate;
	}

	@Override
	public void tick() {
		// commonTick() already stored the previous position/rotation and counted the tick.
		if (freezeEntityTick) {
			return;
		}
		gate.fromEntityTick(AgentManager.get().clock(), this::advance);
	}

	/**
	 * End-of-client-tick catch-up from {@link AgentManager}: advances only if the entity tick did not run
	 * this tick (another mod culled it). Returns whether it advanced.
	 */
	boolean catchUp(long tick) {
		return gate.catchUp(tick, () -> {
			this.setOldPosAndRot(); // what commonTick() does first; a no-op if a culler already did it
			advance();
		});
	}

	/** One tick of walking, animation and life. Runs once per client tick via {@link #gate}. */
	private void advance() {
		this.yBodyRotO = this.yBodyRot;
		this.yHeadRotO = this.yHeadRot;
		this.xRotO = this.getXRot();
		Vec3 before = position();
		Vec3 after = motion.step(before);
		if (!after.equals(before)) {
			this.setPos(after);
		}
		float yaw = motion.yaw();
		this.setYRot(yaw);
		this.yBodyRot = yaw;
		this.yHeadRot = headSet ? headYaw : yaw;
		this.setDeltaMovement(after.subtract(before));
		this.calculateEntityAnimation(false);
		avatarState.tick(position(), getDeltaMovement());
		try {
			life.tick(net.minecraft.client.Minecraft.getInstance());
		} catch (Throwable e) {
			AgentCraft.LOGGER.warn("agent life failed", e);
		}
		for (AgentHooks.Ticker t : AgentHooks.TICKERS) {
			try {
				t.tick(this);
			} catch (Throwable e) {
				AgentCraft.LOGGER.warn("agent ticker failed", e);
			}
		}
	}

	@Override
	public ClientAvatarState avatarState() {
		return avatarState;
	}

	@Override
	public PlayerSkin getSkin() {
		return skin;
	}

	@Override
	public Parrot.@Nullable Variant getParrotVariantOnShoulder(boolean left) {
		return null;
	}

	@Override
	public boolean showExtraEars() {
		return false;
	}
}
