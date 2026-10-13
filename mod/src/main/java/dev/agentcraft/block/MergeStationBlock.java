package dev.agentcraft.block;

import dev.agentcraft.block.entity.MergeStationBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Review worktop with a brass merge inlay; {@code active=true} when a merge review is waiting. */
public class MergeStationBlock extends FacingEntityBlock {
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

	public MergeStationBlock(BlockBehaviour.Properties properties) {
		super(properties, null, MergeStationBlockEntity::new);
	}

	@Override
	protected BlockState initialState(BlockState state) {
		return state.setValue(ACTIVE, false);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(ACTIVE);
	}

	public static int light(BlockState state) {
		return state.getValue(ACTIVE) ? 6 : 0;
	}
}
