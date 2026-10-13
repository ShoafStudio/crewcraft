package dev.agentcraft.block;

import dev.agentcraft.AgentCraft;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** One block item per AgentCraft block, plus the "AgentCraft" creative tab (itemGroup.agentcraft). */
public final class ModItems {
	private static final List<Item> ITEMS = new ArrayList<>();
	public static final ResourceKey<CreativeModeTab> TAB = ResourceKey.create(Registries.CREATIVE_MODE_TAB, AgentCraft.id("agentcraft"));

	private ModItems() {
	}

	public static void init() {
		for (Block block : ModBlocks.all()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BuiltInRegistries.BLOCK.getKey(block));
			BlockItem item = new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix());
			item.registerBlocks(Item.BY_BLOCK, item);
			ITEMS.add(Registry.register(BuiltInRegistries.ITEM, key, item));
		}
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TAB, FabricCreativeModeTab.builder()
			.title(Component.translatable("itemGroup.agentcraft"))
			.icon(() -> new ItemStack(ModBlocks.MONITOR))
			.displayItems((params, output) -> ITEMS.forEach(output::accept))
			.build());
	}

	public static List<Item> all() {
		return List.copyOf(ITEMS);
	}
}
