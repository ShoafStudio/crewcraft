package dev.agentcraft.block;

import dev.agentcraft.block.entity.DecisionPodiumBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;

/** Lectern-like podium; {@code open=true} lights the desk paper, lens and bell (a decision is waiting). */
public class DecisionPodiumBlock extends FacingEntityBlock {
	public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

	public DecisionPodiumBlock(BlockBehaviour.Properties properties) {
		super(properties,
			Shapes.or(Block.box(2, 0, 2, 14, 2, 14), Block.box(4, 2, 4, 12, 11, 12), Block.box(0.5, 11, 1, 15.5, 15, 16)),
			DecisionPodiumBlockEntity::new);
	}

	@Override
	protected BlockState initialState(BlockState state) {
		return state.setValue(OPEN, false);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(OPEN);
	}

	public static int light(BlockState state) {
		return state.getValue(OPEN) ? 9 : 0;
	}
}
