package dev.agentcraft.block;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 2 px light strip. {@code facing} = direction the lit face points (vanilla end_rod rotation
 * table); it sits against the opposite neighbour. {@code axis} = run direction on a floor/ceiling
 * (from the placer's horizontal facing); on walls it always runs horizontally and axis is ignored.
 */
public class GlowStripBlock extends Block {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

	private static final Map<Direction, VoxelShape> SHAPES_X = new EnumMap<>(Direction.class);
	private static final Map<Direction, VoxelShape> SHAPES_Z = new EnumMap<>(Direction.class);

	static {
		SHAPES_X.put(Direction.UP, Block.box(0, 0, 6, 16, 2, 10));
		SHAPES_Z.put(Direction.UP, Block.box(6, 0, 0, 10, 2, 16));
		SHAPES_X.put(Direction.DOWN, Block.box(0, 14, 6, 16, 16, 10));
		SHAPES_Z.put(Direction.DOWN, Block.box(6, 14, 0, 10, 16, 16));
		// Walls: runs horizontally along the wall, centred vertically, against the opposite neighbour.
		VoxelShape north = Block.box(0, 6, 14, 16, 10, 16);
		VoxelShape south = Block.box(0, 6, 0, 16, 10, 2);
		VoxelShape east = Block.box(0, 6, 0, 2, 10, 16);
		VoxelShape west = Block.box(14, 6, 0, 16, 10, 16);
		for (Map<Direction, VoxelShape> m : List.of(SHAPES_X, SHAPES_Z)) {
			m.put(Direction.NORTH, north);
			m.put(Direction.SOUTH, south);
			m.put(Direction.EAST, east);
			m.put(Direction.WEST, west);
		}
	}

	public GlowStripBlock(BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP).setValue(AXIS, Direction.Axis.X));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, AXIS);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction look = context.getHorizontalDirection();
		return this.defaultBlockState()
			.setValue(FACING, context.getClickedFace())
			.setValue(AXIS, look.getAxis() == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return (state.getValue(AXIS) == Direction.Axis.Z ? SHAPES_Z : SHAPES_X).get(state.getValue(FACING));
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		BlockState s = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
			s = s.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
		}
		return s;
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
	}
}
