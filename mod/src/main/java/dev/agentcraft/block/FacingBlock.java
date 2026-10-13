package dev.agentcraft.block;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Horizontal-facing block whose front faces the placing player (vanilla lectern/furnace rule:
 * facing = horizontal look direction, opposite). Optional custom shape, given for the
 * north-facing model and rotated for the other facings.
 */
public class FacingBlock extends HorizontalDirectionalBlock {
	private final @Nullable Map<Direction, VoxelShape> shapes;

	public FacingBlock(BlockBehaviour.Properties properties) {
		this(properties, null);
	}

	public FacingBlock(BlockBehaviour.Properties properties, @Nullable VoxelShape northShape) {
		super(properties);
		this.shapes = northShape == null ? null : Shapes.rotateHorizontal(northShape);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes == null ? Shapes.block() : shapes.get(state.getValue(FACING));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return shapes != null;
	}
}
