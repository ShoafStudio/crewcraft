package dev.agentcraft.block;

import dev.agentcraft.block.entity.StatusLampBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * CI / agent status lamp. The {@code status} property selects the lit texture; its block entity
 * carries a binding ("agent:kit", "ci:REPO", "goal") that tells the client which state to show.
 */
public class StatusLampBlock extends BaseEntityBlock {
	public static final EnumProperty<LampStatus> STATUS = EnumProperty.create("status", LampStatus.class);

	public StatusLampBlock(BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(STATUS, LampStatus.OFF));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(STATUS);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new StatusLampBlockEntity(pos, state);
	}

	public static int light(BlockState state) {
		return state.getValue(STATUS) == LampStatus.OFF ? 0 : 12;
	}
}
