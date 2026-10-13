package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Decision Podium. Binding: empty = the oldest open decision. Drawn by client decisions.DecisionPodiumRenderer. */
public class DecisionPodiumBlockEntity extends StationBlockEntity {
	public DecisionPodiumBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.DECISION_PODIUM, pos, state);
	}
}
