package dev.agentcraft.client.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.console.ConsoleCommands.Completion;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.ui.Guard;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Command console: {@link ConsoleScreen} on the console key ({@code `}, rebindable in Controls), on
 * Enter while looking at a console terminal, or a right-click on one. Input language:
 * {@link ConsoleCommands} (docs/protocol.md "Console mapping"); sending + acks: {@link ConsoleActions}.
 * The terminal's leaning screen shows the prompt, the last command and what is waiting
 * ({@link ConsoleTerminalRenderer}).
 *
 * <p>QA: {@code dev.screen {open:"console"}} then {@code dev.type "@ju"} (autocomplete + ghost);
 * {@code dev.console {prefill?, submit?}} opens it with text (and presses Enter);
 * {@code dev.console.parse {text}} shows what an input would do without sending it.
 */
public final class ConsoleFeature {
	private ConsoleFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.CONSOLE_TERMINAL, ctx -> new ConsoleTerminalRenderer());
		Keys.ensureRegistered();
		DevBridge.registerScreen("console", mc -> ConsoleScreen.forDev(null));
		dev.agentcraft.client.world.StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> open(null, false));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("console.tick", () -> {
			if (mc.player == null) {
				return;
			}
			while (Keys.console.consumeClick()) {
				if (mc.gui.screen() == null) {
					open(null, true);
				}
			}
			while (Keys.terminal.consumeClick()) {
				if (mc.gui.screen() == null && lookingAtTerminal(mc)) {
					open(null, false);
				}
			}
		}));
		registerDev();
	}

	public static void open(String prefill, boolean byKey) {
		Minecraft mc = Minecraft.getInstance();
		ConsoleScreen s = new ConsoleScreen(prefill);
		if (byKey) {
			s.openedByKey();
		}
		mc.gui.setScreen(s);
	}

	private static boolean lookingAtTerminal(Minecraft mc) {
		HitResult hit = mc.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || mc.level == null) {
			return false;
		}
		BlockState st = mc.level.getBlockState(((BlockHitResult) hit).getBlockPos());
		return st.getBlock() == ModBlocks.CONSOLE_TERMINAL;
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.console", 15_000,
			"{prefill?: text, conversation?: agentId|all, submit?: bool} - open the console (with text in the input; submit presses Enter) and report its state", (req, mc) -> {
				Fields f = Fields.of(req);
				String prefill = f.has("prefill") ? f.str("prefill") : null;
				boolean submit = f.optBool("submit", false);
				boolean open = f.optBool("open", true);
				return DevBridge.onClient(mc, () -> {
					if (open && !(mc.gui.screen() instanceof ConsoleScreen)) {
						mc.gui.setScreen(ConsoleScreen.forDev(prefill));
					} else if (prefill != null && mc.gui.screen() instanceof ConsoleScreen cs) {
						cs.setValue(prefill);
					}
					if(f.has("conversation")&&mc.gui.screen() instanceof ConsoleScreen cs)cs.selectConversation(f.str("conversation"));
                    if (submit && mc.gui.screen() instanceof ConsoleScreen cs) {
						cs.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
					}
					return state(mc);
				});
			});
		DevBridge.register("dev.console.parse", 10_000, "{text} - what the console would do with this input (nothing is sent)", (req, mc) -> {
			String text = Fields.of(req).str("text");
			return DevBridge.onClient(mc, () -> {
				JsonObject o = new JsonObject();
				var intent = ConsoleCommands.parse(text, Foreman.state());
				for (Map.Entry<String, Object> e : ConsoleCommands.toMap(intent).entrySet()) {
					o.add(e.getKey(), DevBridge.GSON.toJsonTree(e.getValue()));
				}
				o.addProperty("describe", ConsoleCommands.describe(intent, Foreman.state()));
				JsonArray comps = new JsonArray();
				for (Completion c : ConsoleCommands.complete(text, text.length(), Foreman.state())) {
					comps.add(c.replacement());
				}
				o.add("completions", comps);
				return o;
			});
		});
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		if (mc.gui.screen() instanceof ConsoleScreen cs) {
			o.addProperty("open", true);
			o.addProperty("value", cs.value());
            o.addProperty("conversation",cs.conversation());
            o.add("layout",DevBridge.GSON.toJsonTree(cs.layoutState()));
			o.addProperty("ghost", cs.ghost());
			JsonArray comps = new JsonArray();
			for (Completion c : cs.completions()) {
				comps.add(c.label());
			}
			o.add("completions", comps);
			o.addProperty("repoChooser", cs.repoChooserOpen());
			var intent = cs.intent();
			o.addProperty("intent", intent == null ? null : ConsoleCommands.describe(intent, Foreman.state()));
		} else {
			o.addProperty("open", false);
		}
		o.add("actions", DevBridge.GSON.toJsonTree(ConsoleActions.stats()));
		o.addProperty("historySize", ConsoleLog.history().size());
		o.addProperty("draft", ConsoleLog.draft());
		o.addProperty("lines", ConsoleLog.lines().size());
		return o;
	}
}
