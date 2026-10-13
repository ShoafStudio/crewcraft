package dev.agentcraft.client.agents;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Extension points for Phase 3 (motion polish, animation, particles, speech bubbles). Register from
 * your feature's init; everything runs on the client thread.
 *
 * <pre>
 * AgentHooks.onTick(agent -> { if (agent.view().family.equals("thinking")) spawnSparkles(agent); });
 * AgentHooks.onExtract((agent, state, partialTick) -> state.headLookAtPlayer = ...);
 * AgentHooks.onSubmit((state, poseStack, collector, camera) -> drawSpeechBubble(state, ...));
 * </pre>
 */
public final class AgentHooks {
	@FunctionalInterface
	public interface Ticker {
		/** Once per client tick per agent, after its movement step. */
		void tick(ClientAgentEntity agent);
	}

	@FunctionalInterface
	public interface Extractor {
		/** Fill/adjust render state (pose, rotations, extra fields) while it is extracted. */
		void extract(ClientAgentEntity agent, AgentRenderState state, float partialTick);
	}

	@FunctionalInterface
	public interface Submitter {
		/** Submit extra geometry for an agent (pose stack at the entity origin, like the nameplate). */
		void submit(AgentRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.CameraRenderState camera);
	}

	static final List<Ticker> TICKERS = new CopyOnWriteArrayList<>();
	static final List<Extractor> EXTRACTORS = new CopyOnWriteArrayList<>();
	static final List<Submitter> SUBMITTERS = new CopyOnWriteArrayList<>();

	private AgentHooks() {
	}

	public static void onTick(Ticker t) {
		TICKERS.add(t);
	}

	public static void onExtract(Extractor e) {
		EXTRACTORS.add(e);
	}

	public static void onSubmit(Submitter s) {
		SUBMITTERS.add(s);
	}
}
