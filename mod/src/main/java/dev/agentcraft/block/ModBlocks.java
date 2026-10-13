package dev.agentcraft.block;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.entity.ConsoleTerminalBlockEntity;
import dev.agentcraft.block.entity.MemoryArchiveBlockEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * All 16 AgentCraft blocks, registered per the block contract in assets-src/README.md
 * (properties, facing rule, luminance, shapes). Render layers need no registration in 26.x:
 * every baked quad picks solid/cutout/translucent from its sprite's transparency.
 */
public final class ModBlocks {
	private static final List<Block> ALL = new ArrayList<>();

	public static final Block MONITOR = register("monitor", MonitorBlock::new,
		panel().mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.COPPER).lightLevel(MonitorBlock::light));
	public static final Block TASK_BOARD = register("task_board", TaskBoardBlock::new,
		panel().mapColor(MapColor.WOOL).strength(1.0f).sound(SoundType.WOOD));
	public static final Block DECISION_PODIUM = register("decision_podium", DecisionPodiumBlock::new,
		nonOpaque().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).lightLevel(DecisionPodiumBlock::light));
	public static final Block MEMORY_ARCHIVE = register("memory_archive",
		p -> new FacingEntityBlock(p, null, MemoryArchiveBlockEntity::new),
		BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS).strength(1.5f).sound(SoundType.WOOD));
	public static final Block MEMORY_CATALOG = register("memory_catalog", FacingBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).instrument(NoteBlockInstrument.BASS).strength(1.5f).sound(SoundType.WOOD));
	public static final Block MERGE_STATION = register("merge_station", MergeStationBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD).lightLevel(MergeStationBlock::light));
	public static final Block STATUS_LAMP = register("status_lamp", StatusLampBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(0.8f).sound(SoundType.COPPER).lightLevel(StatusLampBlock::light));
	public static final Block CONSOLE_TERMINAL = register("console_terminal",
		p -> new FacingEntityBlock(p, Shapes.or(Block.box(0, 0, 0, 16, 9, 16), Block.box(1, 9, 8.5, 15, 16, 13.5)), ConsoleTerminalBlockEntity::new),
		nonOpaque().mapColor(MapColor.COLOR_BROWN).strength(2.0f).sound(SoundType.COPPER).lightLevel(s -> 6));
	public static final Block GLOW_PANEL = register("glow_panel", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(0.5f).sound(SoundType.GLASS).lightLevel(s -> 15));
	public static final Block GLOW_STRIP = register("glow_strip", GlowStripBlock::new,
		nonOpaque().mapColor(MapColor.QUARTZ).instabreak().noCollision().sound(SoundType.GLASS).lightLevel(s -> 12));
	public static final Block PLASTER_PANEL = register("plaster_panel", Block::new, plaster());
	public static final Block PLASTER_FRAME = register("plaster_frame", Block::new, plaster());
	public static final Block WALNUT_PANEL = register("walnut_panel", Block::new, wood(MapColor.COLOR_BROWN));
	public static final Block WALNUT_TRIM = register("walnut_trim", Block::new, wood(MapColor.COLOR_BROWN));
	public static final Block TERRACOTTA_TILE = register("terracotta_tile", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).instrument(NoteBlockInstrument.BASEDRUM).strength(1.25f, 4.2f)
			.sound(SoundType.DECORATED_POT));
	public static final Block OAK_PARQUET = register("oak_parquet", Block::new, wood(MapColor.WOOD));

	private ModBlocks() {
	}

	/** Forces class init (registration) from the common entrypoint. */
	public static void init() {
		AgentCraft.LOGGER.info("Registered {} AgentCraft blocks", ALL.size());
	}

	/** Every AgentCraft block in registration (creative tab) order. */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, AgentCraft.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
		ALL.add(block);
		return block;
	}

	private static boolean never(BlockState state, BlockGetter level, BlockPos pos) {
		return false;
	}

	private static boolean neverView(BlockState state, BlockGetter level, BlockPos pos, AABB box) {
		return false;
	}

	/** Thin or shaped blocks: no occlusion, never suffocate or conduct redstone. */
	private static BlockBehaviour.Properties nonOpaque() {
		return BlockBehaviour.Properties.of().noOcclusion().isRedstoneConductor(ModBlocks::never).isSuffocating(ModBlocks::never)
			.isViewBlocking(ModBlocks::neverView).isValidSpawn((s, l, p, t) -> false);
	}

	private static BlockBehaviour.Properties panel() {
		return nonOpaque();
	}

	private static BlockBehaviour.Properties plaster() {
		return BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).instrument(NoteBlockInstrument.BASEDRUM).strength(0.75f).sound(SoundType.CALCITE);
	}

	private static BlockBehaviour.Properties wood(MapColor color) {
		return BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASS).strength(2.0f, 3.0f).sound(SoundType.WOOD);
	}
}
