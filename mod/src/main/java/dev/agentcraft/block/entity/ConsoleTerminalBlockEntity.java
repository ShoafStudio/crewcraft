package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Console terminal. Use opens the command console. Drawn by client console.ConsoleTerminalRenderer (e.g. last command / prompt on the screen). */
public class ConsoleTerminalBlockEntity extends StationBlockEntity {
	public ConsoleTerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CONSOLE_TERMINAL, pos, state);
	}
}
