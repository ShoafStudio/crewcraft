package dev.agentcraft.client.world;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Run world changes driven by Foreman state (lamp status, podium open, merge station active, ...)
 * on the integrated server thread. Singleplayer only: AgentCraft's HQ is a local world. Keep the
 * work small and idempotent (only set a block state when it actually differs).
 *
 * <pre>
 * ServerTasks.run(level -> {
 *     BlockState s = level.getBlockState(pos);
 *     if (s.getValue(StatusLampBlock.STATUS) != wanted) level.setBlock(pos, s.setValue(StatusLampBlock.STATUS, wanted), Block.UPDATE_CLIENTS);
 * });
 * </pre>
 */
public final class ServerTasks {
	private ServerTasks() {
	}

	/** Queue {@code task} on the integrated server with the overworld; false when not in singleplayer. */
	public static boolean run(Consumer<ServerLevel> task) {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return false;
		}
		server.execute(() -> task.accept(server.overworld()));
		return true;
	}
}
