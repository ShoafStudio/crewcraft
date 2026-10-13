package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Status lamp. Binding: "agent:ID", "ci:REPO" or "goal". The client drives the lamp status and particles (hq.StatusLampRenderer). */
public class StatusLampBlockEntity extends StationBlockEntity {
	public StatusLampBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.STATUS_LAMP, pos, state);
	}
}
