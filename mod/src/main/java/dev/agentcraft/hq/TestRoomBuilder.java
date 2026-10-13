package dev.agentcraft.hq;

import dev.agentcraft.Cast;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.GlowStripBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * TEMPORARY functional test room (Phase 2). A walled 25x25 room around the origin with every
 * station, plus a sample row of all 16 custom blocks beside vanilla reference blocks north of it.
 * Phase 3's real HQ builder replaces it (same anchor names, see {@link AnchorNames}).
 *
 * <pre>
 *   north (-Z)   block sample row z=-29..-23 (outside)              cam_blockrow
 *   z=-13        wall
 *   z=-7         desk island: 6 monitors facing south, agents stand at z=-5.4 facing north
 *   west wall    library shelves (z -7..-1), task wall 6x3 (z 3..8)
 *   east wall    terminals (z -6, -4), merge stations (z -1, 0)
 *   centre       goal atrium (-3..-2, 0..1), meeting table (3..4, 0..1)
 *   south        decision podium (0,7) facing north + user spots, test bench lamps (3..8, 7),
 *                lounge (-7..-2, 9..11); everyone there faces north into the room (cam_agents)
 *   z=13         wall with the entrance gap x -1..1
 * </pre>
 */
public final class TestRoomBuilder implements HqBuilder {
	public static final String ID = "test";
	private static final int FLOOR = 64;
	private static final int FEET = 65;
	private static final int R = 12; // interior half-size

	private final List<BlockPos> panels = new ArrayList<>();

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String description() {
		return "temporary functional test room + block sample row (Phase 2)";
	}

	@Override
	public void build(ServerLevel level, Anchors.Builder a) {
		panels.clear();
		clear(level);
		room(level);
		desks(level, a);
		library(level, a);
		taskWall(level, a);
		eastWall(level, a);
		commons(level, a);
		sampleRow(level, a);
		for (BlockPos p : panels) {
			BlockState s = level.getBlockState(p);
			if (s.getBlock() instanceof PanelBlock panel) {
				level.setBlock(p, panel.connect(s, level, p), Block.UPDATE_CLIENTS);
			}
		}
		a.bounds(-R - 1, FLOOR - 1, -R - 1, R + 1, FEET + 5, R + 1);
		a.spot(AnchorNames.ENTRANCE, 0, FEET, 11, 180);
		a.put(AnchorNames.SPAWN, 0.5, FEET, 11.0, 180, 0);
		a.cameraLookAt("overview", 0.5, 84, 26, 0.5, 65, 0);
		a.cameraLookAt("room", 0.5, 71, 15, 0.5, 65.5, -1);
	}

	// ------------------------------------------------------------------ structure

	private static void clear(ServerLevel level) {
		fill(level, -16, FEET, -32, 16, FEET + 10, 16, Blocks.AIR.defaultBlockState());
		fill(level, -16, FLOOR, -32, 16, FLOOR, 16, Blocks.GRASS_BLOCK.defaultBlockState());
	}

	private static void room(ServerLevel level) {
		for (int x = -R; x <= R; x++) {
			for (int z = -R; z <= R; z++) {
				boolean border = Math.abs(x) == R || Math.abs(z) == R;
				set(level, x, FLOOR, z, (border ? ModBlocks.TERRACOTTA_TILE : ModBlocks.OAK_PARQUET).defaultBlockState());
			}
		}
		for (int i = -R - 1; i <= R + 1; i++) {
			wall(level, i, -R - 1);
			wall(level, -R - 1, i);
			wall(level, R + 1, i);
			if (Math.abs(i) > 1) { // entrance gap in the south wall
				wall(level, i, R + 1);
			}
		}
		// corner pillars
		for (int[] c : new int[][] {{-R - 1, -R - 1}, {R + 1, -R - 1}, {-R - 1, R + 1}, {R + 1, R + 1}, {-2, R + 1}, {2, R + 1}}) {
			for (int y = FEET; y <= FEET + 2; y++) {
				set(level, c[0], y, c[1], ModBlocks.PLASTER_FRAME.defaultBlockState());
			}
		}
	}

	private static void wall(ServerLevel level, int x, int z) {
		set(level, x, FLOOR, z, ModBlocks.WALNUT_PANEL.defaultBlockState());
		set(level, x, FEET, z, ModBlocks.WALNUT_PANEL.defaultBlockState());
		set(level, x, FEET + 1, z, ModBlocks.WALNUT_TRIM.defaultBlockState());
	}

	// ------------------------------------------------------------------ stations

	private void desks(ServerLevel level, Anchors.Builder a) {
		BlockState slab = Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
		for (int x = -6; x <= 6; x++) {
			set(level, x, FEET, -7, slab);
		}
		List<String> ids = Cast.ids();
		for (int i = 0; i < 6; i++) {
			int mx = -5 + 2 * i;
			String id = i < ids.size() ? ids.get(i) : "agent" + i;
			BlockPos m = new BlockPos(mx, FEET + 1, -7);
			set(level, m, ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, Direction.SOUTH).setValue(MonitorBlock.LIT, true));
			panels.add(m);
			bind(level, m, id);
			a.put(AnchorNames.desk(id), mx + 0.5, FEET, -5.4, 180, 0);
			// a desk chair at the desk spot (agents sit on a seat block at desk_<id>; its back faces away from the desk)
			set(level, mx, FEET, -6, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
			a.put(AnchorNames.monitor(id), mx + 0.5, FEET + 1.5, -6.75, 0, 0);
			a.cameraLookAt("desk_" + id, mx + 0.5, FEET + 1.75, -2.6, mx + 0.5, FEET + 1.4, -6.75);
		}
		for (int x = -6; x <= 6; x += 2) {
			set(level, x, FEET + 1, -7, (x % 4 == 0 ? Blocks.POTTED_FERN : Blocks.POTTED_AZALEA).defaultBlockState());
		}
		a.cameraLookAt("desks_back", 0.5, FEET + 3, 0, 0.5, FEET + 1, -7);
	}

	private void library(ServerLevel level, Anchors.Builder a) {
		BlockState archive = ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.EAST);
		for (int z = -6; z <= -2; z++) {
			set(level, -R, FEET, z, archive);
			set(level, -R, FEET + 1, z, archive);
			bind(level, new BlockPos(-R, FEET, z), "shared");
		}
		BlockState catalog = ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
		set(level, -R, FEET, -7, catalog);
		set(level, -R, FEET, -1, catalog);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 1), -10.4, FEET, -3.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 2), -10.4, FEET, -5.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 3), -10.4, FEET, -1.6, 90, 0);
		a.cameraLookAt("library", -6.0, FEET + 2.2, -1.0, -11.5, FEET + 1, -4.0);
	}

	private void taskWall(ServerLevel level, Anchors.Builder a) {
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, Direction.EAST);
		for (int z = 3; z <= 8; z++) {
			for (int y = FEET; y <= FEET + 2; y++) {
				BlockPos p = new BlockPos(-R, y, z);
				set(level, p, board);
				panels.add(p);
			}
		}
		a.put(AnchorNames.TASK_WALL, -R + 0.125, FEET + 1.5, 6.0, -90, 0);
		a.cameraLookAt("task_wall", -4.0, FEET + 1.6, 6.0, -R, FEET + 1.5, 6.0);
	}

	private void eastWall(ServerLevel level, Anchors.Builder a) {
		BlockState terminal = ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST);
		set(level, R, FEET, -6, terminal);
		set(level, R, FEET, -4, terminal);
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 1), 10.6, FEET, -5.5, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 2), 10.6, FEET, -3.5, -90, 0);
		BlockState merge = ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST);
		set(level, R, FEET, -1, merge);
		set(level, R, FEET, 0, merge);
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 1), 10.6, FEET, 0.0, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 2), 10.6, FEET, 1.6, -90, 0);
		a.cameraLookAt("east_wall", 5.0, FEET + 2.2, -1.0, 11.5, FEET + 1, -2.5);
	}

	private void commons(ServerLevel level, Anchors.Builder a) {
		// goal atrium: glow inlay in the floor
		for (int x = -3; x <= -2; x++) {
			for (int z = 0; z <= 1; z++) {
				set(level, x, FLOOR, z, ModBlocks.GLOW_PANEL.defaultBlockState());
			}
		}
		a.put(AnchorNames.GOAL_ATRIUM, -2.0, FEET, 1.0, 180, 0);
		// meeting table (2x2) with four seats around it
		BlockState slab = Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
		for (int x = 3; x <= 4; x++) {
			for (int z = 0; z <= 1; z++) {
				set(level, x, FEET, z, slab);
			}
		}
		a.put(AnchorNames.slot(AnchorNames.MEETING, 1), 2.4, FEET, 1.0, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 2), 5.6, FEET, 1.0, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 3), 4.0, FEET, -0.6, 0, 0);
		a.put(AnchorNames.slot(AnchorNames.MEETING, 4), 4.0, FEET, 2.6, 180, 0);
		// decision podium facing north, user spots beside it; everyone in the south half faces north
		// into the room, and the user spot, test bench and lounge sit close together so one camera
		// (cam_agents) sees all their faces.
		BlockPos podium = new BlockPos(0, FEET, 7);
		set(level, podium, ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.NORTH)
			.setValue(DecisionPodiumBlock.OPEN, false));
		a.put(AnchorNames.DECISION_PODIUM, 0.5, FEET + 0.95, 7.5, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.USER, 1), -0.9, FEET, 8.2, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.USER, 2), 1.9, FEET, 8.2, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.USER, 3), 0.5, FEET, 9.4, 180, 0);
		a.cameraLookAt("podium", 0.5, FEET + 1.62, 4.4, 0.5, FEET + 1.0, 7.5);
		// test bench: one status lamp per agent, agents stand behind them facing north
		List<String> ids = Cast.ids();
		for (int i = 0; i < 6; i++) {
			BlockPos p = new BlockPos(3 + i, FEET, 7);
			set(level, p, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			bind(level, p, "agent:" + (i < ids.size() ? ids.get(i) : "agent" + i));
		}
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 1), 3.7, FEET, 8.6, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 2), 5.3, FEET, 8.6, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 3), 6.9, FEET, 8.6, 180, 0);
		// lounge: moss carpet, plants, two rows of three spots, facing north
		for (int x = -7; x <= -2; x++) {
			for (int z = 9; z <= 11; z++) {
				set(level, x, FEET, z, Blocks.MOSS_CARPET.defaultBlockState());
			}
		}
		set(level, -8, FEET, 11, Blocks.POTTED_FLOWERING_AZALEA.defaultBlockState());
		set(level, -8, FEET, 9, Blocks.POTTED_AZALEA.defaultBlockState());
		double[] lx = {-5.6, -4.2, -2.8};
		for (int i = 0; i < 6; i++) {
			a.put(AnchorNames.slot(AnchorNames.LOUNGE, i + 1), lx[i % 3], FEET + 0.0625, i < 3 ? 9.6 : 11.0, 180, 0);
		}
		// faces of the agents at the user spot, test bench and lounge (close-up)
		a.cameraLookAt("agents", -0.7, FEET + 1.9, 3.4, -0.7, FEET + 1.25, 8.8);
		// the south half, seen from the room centre
		a.cameraLookAt("commons", 0.0, FEET + 2.4, 1.6, -0.5, FEET + 1.0, 9.0);
	}

	// ------------------------------------------------------------------ verification row

	/** Every custom block next to vanilla reference blocks, north of the room (outside), on a birch floor. */
	private void sampleRow(ServerLevel level, Anchors.Builder a) {
		int zRow = -26;
		int zWall = -29;
		for (int x = -11; x <= 11; x++) {
			for (int z = zWall; z <= zRow + 3; z++) {
				set(level, x, FLOOR, z, Blocks.BIRCH_PLANKS.defaultBlockState());
			}
			for (int y = FEET; y <= FEET + 3; y++) {
				set(level, x, y, zWall, (x < 0 ? Blocks.CALCITE : ModBlocks.PLASTER_PANEL).defaultBlockState());
			}
		}
		Direction s = Direction.SOUTH;
		BlockState[] row = {
			Blocks.CALCITE.defaultBlockState(),
			ModBlocks.PLASTER_PANEL.defaultBlockState(),
			ModBlocks.PLASTER_FRAME.defaultBlockState(),
			Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(),
			ModBlocks.WALNUT_PANEL.defaultBlockState(),
			ModBlocks.WALNUT_TRIM.defaultBlockState(),
			Blocks.DARK_OAK_PLANKS.defaultBlockState(),
			ModBlocks.TERRACOTTA_TILE.defaultBlockState(),
			Blocks.TERRACOTTA.defaultBlockState(),
			ModBlocks.OAK_PARQUET.defaultBlockState(),
			Blocks.STRIPPED_OAK_LOG.defaultBlockState(),
			ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, s),
			ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, s),
			Blocks.BOOKSHELF.defaultBlockState(),
			ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, s).setValue(MergeStationBlock.ACTIVE, true),
			ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, s),
			ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(FacingEntityBlock.FACING, s).setValue(DecisionPodiumBlock.OPEN, true),
			Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, s),
			ModBlocks.GLOW_PANEL.defaultBlockState(),
		};
		for (int i = 0; i < row.length; i++) {
			set(level, -9 + i, FEET, zRow, row[i]);
		}
		// second course: every status lamp state on top of the first seven, a copper and a closed podium
		LampStatus[] lamps = LampStatus.values();
		for (int i = 0; i < lamps.length; i++) {
			set(level, -8 + i, FEET + 1, zRow, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, lamps[i]));
		}
		set(level, -9, FEET + 1, zRow, Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState());
		set(level, 0, FEET + 1, zRow, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, Direction.UP));
		set(level, 2, FEET + 1, zRow, ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, s));
		set(level, 7, FEET, zRow + 2, ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(FacingEntityBlock.FACING, s));
		// wall-mounted panels on the backdrop, facing south
		int zm = zWall + 1;
		for (int x = -8; x <= -7; x++) {
			for (int y = FEET + 1; y <= FEET + 2; y++) {
				panel(level, new BlockPos(x, y, zm), ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, s).setValue(MonitorBlock.LIT, true));
			}
		}
		panel(level, new BlockPos(-5, FEET + 1, zm), ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, s).setValue(MonitorBlock.LIT, false));
		panel(level, new BlockPos(-4, FEET + 2, zm), ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, s).setValue(MonitorBlock.LIT, true));
		for (int x = -2; x <= 0; x++) {
			for (int y = FEET + 1; y <= FEET + 2; y++) {
				panel(level, new BlockPos(x, y, zm), ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, s));
			}
		}
		panel(level, new BlockPos(2, FEET + 2, zm), ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, s));
		for (int x = 4; x <= 6; x++) {
			set(level, x, FEET + 2, zm, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, s));
		}
		set(level, 8, FEET + 3, zRow + 1, ModBlocks.GLOW_STRIP.defaultBlockState().setValue(GlowStripBlock.FACING, Direction.DOWN));
		set(level, 8, FEET + 4, zRow + 1, Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState());
		a.cameraLookAt("blockrow", 0.5, FEET + 3.3, -14.6, 0.5, FEET + 1.0, zRow - 0.5);
		a.cameraLookAt("blockrow_left", -4.5, FEET + 2.2, zRow + 5.5, -4.5, FEET + 1.0, zRow - 0.5);
		a.cameraLookAt("blockrow_right", 4.5, FEET + 2.2, zRow + 5.5, 4.5, FEET + 1.0, zRow - 0.5);
	}

	private void panel(ServerLevel level, BlockPos p, BlockState state) {
		set(level, p, state);
		panels.add(p);
	}

	// ------------------------------------------------------------------ helpers

	private static void bind(ServerLevel level, BlockPos pos, String binding) {
		if (level.getBlockEntity(pos) instanceof StationBlockEntity be) {
			be.setBinding(binding);
		}
	}

	private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
		set(level, new BlockPos(x, y, z), state);
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	private static void fill(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (BlockPos p : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
			if (!level.getBlockState(p).equals(state)) {
				level.setBlock(p, state, Block.UPDATE_CLIENTS);
			}
		}
	}
}
