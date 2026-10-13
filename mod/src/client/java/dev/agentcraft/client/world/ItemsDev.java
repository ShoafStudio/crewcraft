package dev.agentcraft.client.world;

import dev.agentcraft.block.ModItems;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.FrameScheduler;
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;

/** {@code dev.screen {open:"creative_agentcraft"}}: the creative inventory on the AgentCraft tab (QA of block items). */
public final class ItemsDev {
	private ItemsDev() {
	}

	public static void init() {
		DevBridge.registerScreen("creative_agentcraft", mc -> {
			if (mc.player == null || mc.getConnection() == null) {
				throw new DevBridge.DevException("not in a world yet");
			}
			CreativeModeInventoryScreen screen = new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(),
				mc.options.operatorItemsTab().get());
			FrameScheduler.afterFrames(1).thenRun(() -> BuiltInRegistries.CREATIVE_MODE_TAB.get(ModItems.TAB)
				.ifPresent(tab -> ((FabricCreativeModeInventoryScreen) screen).setSelectedTab(tab.value())));
			return screen;
		});
	}
}
