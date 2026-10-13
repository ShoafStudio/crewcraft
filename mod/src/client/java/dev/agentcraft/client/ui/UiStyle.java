package dev.agentcraft.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Colour and metric tokens of the Warm Studio UI, read from the synced art files
 * {@code assets/agentcraft/gui/ui-style.json} (monitor/paper/status/agents/ink_ui/metrics, see
 * assets-src/ui-style.md) and {@code assets/agentcraft/palette.json} ({@code colors.*}, {@code ui.*}).
 * Never hard-code a colour in a screen or renderer: ask here.
 *
 * <pre>
 * UiStyle.color("paper.text")       // 0xFF1F1E1D (ARGB, opaque)
 * UiStyle.color("monitor.diff_add")
 * UiStyle.status("working")         // status palette by family: idle thinking working waiting error done
 * UiStyle.agentOnDark("kit")        // nameplate/HUD name colour; agentOnLight for paper GUIs
 * UiStyle.metric("metrics.gui_panel_padding", 8)
 * </pre>
 */
public final class UiStyle {
	public static final int CREAM = 0xFFF4EFE6;
	public static final int PAPER = 0xFFE9E1D3;
	public static final int CLAY = 0xFFD97757;
	public static final int CLAY_DARK = 0xFFB4553A;
	public static final int WALNUT = 0xFF3B2A20;
	public static final int BRASS = 0xFFC9A227;
	public static final int SAGE = 0xFF8FA98B;
	public static final int INK = 0xFF1F1E1D;
	public static final int TEAL = 0xFF2FA3A0;

	private static final Map<String, Integer> COLORS = new HashMap<>();
	private static final Map<String, Double> NUMBERS = new HashMap<>();

	static {
		load("/assets/agentcraft/gui/ui-style.json", "");
		load("/assets/agentcraft/palette.json", "palette.");
	}

	private UiStyle() {
	}

	/** Opaque ARGB colour for a token path, e.g. "paper.text", "palette.colors.clay", "palette.ui.panel". */
	public static int color(String path) {
		Integer c = COLORS.get(path);
		if (c == null) {
			AgentCraft.LOGGER.debug("UiStyle: unknown colour token {}", path);
			return 0xFFFF00FF;
		}
		return c;
	}

	public static int color(String path, int fallback) {
		Integer c = COLORS.get(path);
		return c == null ? fallback : c;
	}

	/** Status colour by family (idle, thinking, working, waiting, error, done). */
	public static int status(String family) {
		return color("status." + family, 0xFF9C9488);
	}

	/** Agent name colour on dark surfaces (nameplates, HUD, console). Unknown agent: cream. */
	public static int agentOnDark(String agentId) {
		Integer c = COLORS.get("agents." + agentId + ".text_on_dark");
		if (c != null) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnDark() : CREAM;
	}

	/** Agent name colour on paper GUIs. Unknown agent: ink. */
	public static int agentOnLight(String agentId) {
		Integer c = COLORS.get("agents." + agentId + ".text_on_light");
		if (c != null) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnLight() : INK;
	}

	public static int metric(String path, int fallback) {
		Double d = NUMBERS.get(path);
		return d == null ? fallback : (int) Math.round(d);
	}

	/** {@code argb} with its alpha replaced (0..255). */
	public static int withAlpha(int argb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
	}

	private static void load(String resource, String prefix) {
		try (InputStream in = UiStyle.class.getResourceAsStream(resource)) {
			if (in == null) {
				AgentCraft.LOGGER.warn("UiStyle: {} missing (run assets-src/sync.py)", resource);
				return;
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			walk(root, prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1));
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("UiStyle: could not read {}", resource, e);
		}
	}

	private static void walk(JsonElement el, String path) {
		if (el.isJsonObject()) {
			for (var e : el.getAsJsonObject().entrySet()) {
				walk(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey());
			}
		} else if (el.isJsonPrimitive()) {
			var p = el.getAsJsonPrimitive();
			if (p.isString()) {
				int c = Cast.parseColor(p.getAsString(), -1);
				if (c != -1 && p.getAsString().startsWith("#")) {
					COLORS.put(path, 0xFF000000 | c);
				}
			} else if (p.isNumber()) {
				NUMBERS.put(path, p.getAsDouble());
			}
		}
	}
}
