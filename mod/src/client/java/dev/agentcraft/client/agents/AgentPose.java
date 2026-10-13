package dev.agentcraft.client.agents;

/**
 * Coarse body pose of an agent ({@link AgentView#pose}), kept by {@link AgentLife} for readers that
 * only need the big picture: WALK while walking, SIT while seated on a seat block, LEAN while bent
 * over work (test bench, terminal, merge station), STAND otherwise. The detailed posture (typing,
 * reading a book, thinking, ...) is {@link AgentLife#posture()}; {@link AgentModel} draws it.
 */
public enum AgentPose {
	STAND, WALK, SIT, LEAN
}
