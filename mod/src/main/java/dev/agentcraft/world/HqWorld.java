package dev.agentcraft.world;

import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The "AgentCraft HQ" world: identity + rules. The client creates/loads it automatically
 * (see client AutoWorld); this class makes sure that whenever the HQ world runs, its rules are
 * the calm, building-friendly ones the HQ needs. Other worlds are never touched.
 */
public final class HqWorld {
	/** Folder name and level name of the HQ world. */
	public static final String LEVEL_NAME = "AgentCraft HQ";
	/** Surface Y of the superflat meadow: grass top is at y=64, so you stand at y=65. */
	public static final int SURFACE_Y = 64;
	/** Golden-hour-ish day time set once when the world is created. */
	public static final long INITIAL_TIME = 12000L;
	private static final String MARKER_FILE = "agentcraft-world.json";

	private HqWorld() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(HqWorld::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!isHq(server)) {
				return;
			}
			ServerPlayer player = handler.getPlayer();
			// DevBridge camera shots switch to spectator; normal play should resume in creative.
			if (player.gameMode() == GameType.SPECTATOR || player.gameMode() == GameType.SURVIVAL) {
				player.setGameMode(GameType.CREATIVE);
			}
		});
	}

	/** The HQ world: switches players to creative and rewrites game rules, so never a Hardcore world. */
	public static boolean isHq(MinecraftServer server) {
		return LEVEL_NAME.equals(server.getWorldData().getLevelName()) && !server.getWorldData().isHardcore();
	}

	private static void onServerStarted(MinecraftServer server) {
		if (!isHq(server)) {
			return;
		}
		applyRules(server);
		Path marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER_FILE);
		if (!Files.exists(marker)) {
			// First start of a fresh HQ world.
			run(server, "time set " + INITIAL_TIME);
			run(server, "weather clear");
			run(server, "setworldspawn 0 " + (SURFACE_Y + 1) + " 0");
			try {
				Files.writeString(marker, "{\"createdBy\":\"agentcraft\",\"created\":\"" + Instant.now() + "\"}\n", StandardCharsets.UTF_8);
			} catch (IOException e) {
				AgentCraft.LOGGER.warn("Could not write HQ world marker {}", marker, e);
			}
			AgentCraft.LOGGER.info("Initialised fresh HQ world (time {}, clear weather)", INITIAL_TIME);
		}
	}

	/** Idempotent; applied on every start so rule changes in code reach existing worlds. */
	public static void applyRules(MinecraftServer server) {
		GameRules rules = server.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.KEEP_INVENTORY, true, server);
		rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.SPAWN_WARDENS, false, server);
		rules.set(GameRules.MOB_GRIEFING, false, server);
		// Keep the built HQ exactly as built: no vine growth, no snow layers.
		rules.set(GameRules.SPREAD_VINES, false, server);
		rules.set(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, 0, server);
		rules.set(GameRules.RESPAWN_RADIUS, 0, server);
		rules.set(GameRules.LOCATOR_BAR, false, server);
		rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
		rules.set(GameRules.COMMAND_BLOCK_OUTPUT, false, server);
		rules.set(GameRules.LOG_ADMIN_COMMANDS, false, server);
		// Allow large /fill operations for the HQ builder.
		rules.set(GameRules.MAX_BLOCK_MODIFICATIONS, 1_000_000, server);
	}

	private static void run(MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
