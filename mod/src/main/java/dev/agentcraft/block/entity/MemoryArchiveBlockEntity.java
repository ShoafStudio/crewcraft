package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Memory archive shelf. Binding: memory scope it shows ("shared" or an agent id; empty = all). Use opens the library screen. Drawn by client library.MemoryArchiveRenderer. */
public class MemoryArchiveBlockEntity extends StationBlockEntity {
	public MemoryArchiveBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MEMORY_ARCHIVE, pos, state);
	}
}
