package dev.agentcraft.client.agents;

/**
 * Lets an agent advance at most once per client tick, whichever path gets there first.
 *
 * <p>An agent normally advances in its entity tick ({@link ClientAgentEntity#tick}). Mods that skip
 * ticking entities the player can't see (Entity Culling's {@code tickCulling}, on by default) would
 * freeze it there, so {@link AgentManager} also offers every agent a catch-up at the end of each client
 * tick. Both paths {@link #claim} the same tick number; only the first one advances. Pure: no Minecraft
 * state.
 */
public final class TickGate {
	private long last = Long.MIN_VALUE;
	private long byEntity;
	private long byCatchUp;

	/** True (and the tick is taken) if nothing advanced on {@code tick} yet. */
	public boolean claim(long tick) {
		if (tick == last) {
			return false;
		}
		last = tick;
		return true;
	}

	/** The entity-tick path: claims {@code tick} and runs {@code advance} if it was free. */
	public boolean fromEntityTick(long tick, Runnable advance) {
		if (!claim(tick)) {
			return false;
		}
		byEntity++;
		advance.run();
		return true;
	}

	/** The catch-up path (end of the client tick): runs {@code advance} only if the entity tick did not. */
	public boolean catchUp(long tick, Runnable advance) {
		if (!claim(tick)) {
			return false;
		}
		byCatchUp++;
		advance.run();
		return true;
	}

	/** Advances done by the entity tick. */
	public long byEntity() {
		return byEntity;
	}

	/** Advances done by the end-of-tick catch-up (non-zero only while something skipped the entity tick). */
	public long byCatchUp() {
		return byCatchUp;
	}
}
