package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Task Wall panel. Binding: empty = all tasks (columns todo/doing/review/done/blocked), or a column/repo filter. Drawn by client taskwall.TaskBoardRenderer from the panel origin. */
public class TaskBoardBlockEntity extends StationBlockEntity {
	public TaskBoardBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.TASK_BOARD, pos, state);
	}
}
