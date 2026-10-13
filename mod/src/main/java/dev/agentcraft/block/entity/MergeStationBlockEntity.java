package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Merge Station. Binding: empty = the oldest open merge decision. Drawn by client diff.MergeStationRenderer. */
public class MergeStationBlockEntity extends StationBlockEntity {
	public MergeStationBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MERGE_STATION, pos, state);
	}
}
