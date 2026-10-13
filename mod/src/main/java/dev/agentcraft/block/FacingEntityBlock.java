package dev.agentcraft.block;

import java.util.Map;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** {@link FacingBlock} with a block entity (stations whose content a BER draws). */
public class FacingEntityBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

	private final @Nullable Map<Direction, VoxelShape> shapes;
	private final BiFunction<BlockPos, BlockState, BlockEntity> factory;

	public FacingEntityBlock(BlockBehaviour.Properties properties, @Nullable VoxelShape northShape,
		BiFunction<BlockPos, BlockState, BlockEntity> factory) {
		super(properties);
		this.shapes = northShape == null ? null : Shapes.rotateHorizontal(northShape);
		this.factory = factory;
		this.registerDefaultState(initialState(this.stateDefinition.any().setValue(FACING, Direction.NORTH)));
	}

	/** Default values of the extra properties of subclasses. */
	protected BlockState initialState(BlockState state) {
		return state;
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
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return factory.apply(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes == null ? Shapes.block() : shapes.get(state.getValue(FACING));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return shapes != null;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
