package dev.agentcraft.block;

import dev.agentcraft.block.entity.MonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Agent monitor: connectable screen panel ({@link PanelBlock}) with a {@code lit} state. Its block
 * entity carries the binding (agent id) and the monitor feature's BER draws the log on the screen
 * plane z = 12/16 (north-facing model), inside the bezel.
 */
public class MonitorBlock extends PanelBlock {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public MonitorBlock(BlockBehaviour.Properties properties) {
		super(properties, 5);
	}

	@Override
	protected BlockState defaultConnections(BlockState state) {
		return super.defaultConnections(state).setValue(LIT, true);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(LIT);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MonitorBlockEntity(pos, state);
	}

	public static int light(BlockState state) {
		return state.getValue(LIT) ? 7 : 0;
	}

}
