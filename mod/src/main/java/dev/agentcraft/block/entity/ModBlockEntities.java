package dev.agentcraft.block.entity;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.ModBlocks;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Block entity types of the stations whose content is dynamic (drawn by a client BER from the
 * Foreman state). Each type has its own small class so feature specialists can add fields to
 * theirs without touching the others.
 */
public final class ModBlockEntities {
	public static final BlockEntityType<MonitorBlockEntity> MONITOR = register("monitor", MonitorBlockEntity::new, ModBlocks.MONITOR);
	public static final BlockEntityType<TaskBoardBlockEntity> TASK_BOARD = register("task_board", TaskBoardBlockEntity::new, ModBlocks.TASK_BOARD);
	public static final BlockEntityType<DecisionPodiumBlockEntity> DECISION_PODIUM =
		register("decision_podium", DecisionPodiumBlockEntity::new, ModBlocks.DECISION_PODIUM);
	public static final BlockEntityType<MergeStationBlockEntity> MERGE_STATION =
		register("merge_station", MergeStationBlockEntity::new, ModBlocks.MERGE_STATION);
	public static final BlockEntityType<StatusLampBlockEntity> STATUS_LAMP = register("status_lamp", StatusLampBlockEntity::new, ModBlocks.STATUS_LAMP);
	public static final BlockEntityType<ConsoleTerminalBlockEntity> CONSOLE_TERMINAL =
		register("console_terminal", ConsoleTerminalBlockEntity::new, ModBlocks.CONSOLE_TERMINAL);
	public static final BlockEntityType<MemoryArchiveBlockEntity> MEMORY_ARCHIVE =
		register("memory_archive", MemoryArchiveBlockEntity::new, ModBlocks.MEMORY_ARCHIVE);

	private ModBlockEntities() {
	}

	public static void init() {
		// class init registers everything
	}

	private static <T extends BlockEntity> BlockEntityType<T> register(String name, BlockEntityType.BlockEntitySupplier<T> factory, Block block) {
		return Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, AgentCraft.id(name), new BlockEntityType<>(factory, Set.of(block)));
	}
}
