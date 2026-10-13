package dev.agentcraft.block;

import dev.agentcraft.block.entity.TaskBoardBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** Task Wall surface: connectable linen panel; the task wall feature's BER draws cards on z = 14/16. */
public class TaskBoardBlock extends PanelBlock {
	public TaskBoardBlock(BlockBehaviour.Properties properties) {
		super(properties, 3);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TaskBoardBlockEntity(pos, state);
	}
}
