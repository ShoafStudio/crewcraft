package dev.agentcraft.block;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A connectable wall panel (monitor, task board). {@code up/down/left/right} are true when the
 * neighbour on that side, as seen by a viewer looking at the panel, is the same block with the same
 * facing; the bezel on that side is then omitted so N x M panels read as one surface.
 * {@code left = facing.getClockWise()}, {@code right = facing.getCounterClockWise()}
 * (assets-src/README.md block contract).
 */
public abstract class PanelBlock extends BaseEntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	public static final BooleanProperty UP = BlockStateProperties.UP;
	public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
	public static final BooleanProperty LEFT = BooleanProperty.create("left");
	public static final BooleanProperty RIGHT = BooleanProperty.create("right");

	private final Map<Direction, VoxelShape> shapes;

	/** @param depthPx panel thickness at the back of the block, in pixels (monitor 5 incl. bezel, task board 3). */
	protected PanelBlock(BlockBehaviour.Properties properties, double depthPx) {
		super(properties);
		this.shapes = Shapes.rotateHorizontal(Block.box(0, 0, 16 - depthPx, 16, 16, 16));
		this.registerDefaultState(defaultConnections(this.stateDefinition.any().setValue(FACING, Direction.NORTH)));
	}

	protected BlockState defaultConnections(BlockState state) {
		return state.setValue(UP, false).setValue(DOWN, false).setValue(LEFT, false).setValue(RIGHT, false);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, UP, DOWN, LEFT, RIGHT);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState state = this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		return connect(state, context.getLevel(), context.getClickedPos());
	}

	/** Recomputes all four connection flags from the neighbours. */
	public BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
		Direction facing = state.getValue(FACING);
		return state
			.setValue(UP, connects(state, level.getBlockState(pos.above())))
			.setValue(DOWN, connects(state, level.getBlockState(pos.below())))
			.setValue(LEFT, connects(state, level.getBlockState(pos.relative(facing.getClockWise()))))
			.setValue(RIGHT, connects(state, level.getBlockState(pos.relative(facing.getCounterClockWise()))));
	}

	protected boolean connects(BlockState self, BlockState other) {
		return other.is(this) && other.getValue(FACING) == self.getValue(FACING);
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction dir,
		BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		Direction facing = state.getValue(FACING);
		boolean c = connects(state, neighbourState);
		if (dir == Direction.UP) {
			return state.setValue(UP, c);
		} else if (dir == Direction.DOWN) {
			return state.setValue(DOWN, c);
		} else if (dir == facing.getClockWise()) {
			return state.setValue(LEFT, c);
		} else if (dir == facing.getCounterClockWise()) {
			return state.setValue(RIGHT, c);
		}
		return state;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return shapes.get(state.getValue(FACING));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	/**
	 * The bottom-left block of the connected panel this block belongs to, as seen by a viewer (walks
	 * down and left while connected). BERs use it to draw a multi-block surface once, from its origin.
	 */
	public static BlockPos origin(BlockGetter level, BlockPos pos, BlockState state) {
		Direction left = state.getValue(FACING).getClockWise();
		BlockPos p = pos;
		BlockState s = state;
		for (int i = 0; i < 64 && s.getBlock() instanceof PanelBlock && s.getValue(DOWN); i++) {
			p = p.below();
			s = level.getBlockState(p);
		}
		for (int i = 0; i < 64 && s.getBlock() instanceof PanelBlock && s.getValue(LEFT); i++) {
			p = p.relative(left);
			s = level.getBlockState(p);
		}
		return p;
	}

	/** Width x height (in blocks) of the connected panel whose bottom-left origin is {@code origin}. */
	public static int[] extent(BlockGetter level, BlockPos origin, BlockState originState) {
		Direction right = originState.getValue(FACING).getCounterClockWise();
		int w = 1;
		int h = 1;
		BlockState s = originState;
		BlockPos p = origin;
		while (w < 64 && s.getBlock() instanceof PanelBlock && s.getValue(RIGHT)) {
			p = p.relative(right);
			s = level.getBlockState(p);
			w++;
		}
		s = originState;
		p = origin;
		while (h < 64 && s.getBlock() instanceof PanelBlock && s.getValue(UP)) {
			p = p.above();
			s = level.getBlockState(p);
			h++;
		}
		return new int[] {w, h};
	}
}
