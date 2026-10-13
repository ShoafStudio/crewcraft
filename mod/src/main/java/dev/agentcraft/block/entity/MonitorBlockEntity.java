package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Monitor screen. Binding: agent id whose log it streams (empty = the desk owner from the layout). Drawn by client monitor.MonitorRenderer from the panel origin. */
public class MonitorBlockEntity extends StationBlockEntity {
	public MonitorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MONITOR, pos, state);
	}
}
