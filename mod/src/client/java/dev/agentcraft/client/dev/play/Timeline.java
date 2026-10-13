package dev.agentcraft.client.dev.play;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.Fields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Choreography of a shot: events at time {@code t} (seconds since the first frame of the
 * playback). An event runs on the render thread at the start of the first frame (before that
 * frame's client ticks) whose elapsed time is {@code >= t}, so its effect shows in that frame.
 *
 * <pre>
 * {t, inject:{type:"agent.upsert", agent:{...}}}         Foreman message applied as if received
 * {t, patch:{agent:"marlow", set:{station:"desk", state:"editing"}}}    change a few fields of the current agent
 * {t, patch:{task:"t6", set:{status:"doing"}}}                          ... or task
 * {t, say:{agent:"marlow", text:"On it!", to?:"user"}}   speech bubble (agent.say, ts = now)
 * {t, cmd:"dev.screen", open:"console"}                   any DevBridge command with its fields
 * {t, cmd:"dev.type", text:"hello", msPerChar?:75, jitter?:0.3, seed?:1}
 *                                                          typed one character at a time (natural rhythm)
 * {t, cmd:"dev.key", key:"return"} / {t, cmd:"dev.command", command:"time set 13000"} / {t, cmd:"dev.agents", settle:true}
 * </pre>
 */
final class Timeline {
	record Event(int index, double t, String label, JsonObject body) {
	}

	final List<Event> events;

	private Timeline(List<Event> events) {
		this.events = events;
	}

	static Timeline parse(JsonElement raw, double duration) {
		List<Event> out = new ArrayList<>();
		if (raw == null || raw.isJsonNull()) {
			return new Timeline(out);
		}
		if (!raw.isJsonArray()) {
			throw new DevException("field 'timeline' must be an array of events");
		}
		JsonArray arr = raw.getAsJsonArray();
		for (int i = 0; i < arr.size(); i++) {
			if (!arr.get(i).isJsonObject()) {
				throw new DevException("timeline[" + i + "] must be an object");
			}
			JsonObject o = arr.get(i).getAsJsonObject();
			Fields f = Fields.of(o);
			String where = "timeline[" + i + "]";
			double t = f.num("t", 0, 1e6);
			if (t > duration + 1e-9) {
				throw new DevException(where + ".t=" + t + " is after the end of the shot (" + duration + " s)");
			}
			int kinds = (f.has("inject") ? 1 : 0) + (f.has("patch") ? 1 : 0) + (f.has("say") ? 1 : 0) + (f.has("cmd") ? 1 : 0);
			if (kinds != 1) {
				throw new DevException(where + " needs exactly one of inject | patch | say | cmd");
			}
			if (f.has("cmd")) {
				String cmd = f.nonBlank("cmd");
				if (!cmd.startsWith("dev.")) {
					throw new DevException(where + ".cmd must be a DevBridge command (dev.*), got '" + cmd + "'");
				}
				if (cmd.startsWith("dev.play") || cmd.equals("dev.quit") || cmd.equals("dev.camera")) {
					throw new DevException(where + ": " + cmd + " cannot run inside a shot");
				}
				if (cmd.equals("dev.command")) {
					f.nonBlank("command"); // the server command ('cmd' already names the DevBridge command)
				}
				if (cmd.equals("dev.type") && f.str("text").codePointCount(0, f.str("text").length()) > 1) {
					expandTyping(out, i, t, f);
					continue;
				}
			} else if (f.has("inject")) {
				Fields.of(f.obj("inject").json()).nonBlank("type");
			} else if (f.has("patch")) {
				Fields p = f.obj("patch");
				if (p.has("agent") == p.has("task")) {
					throw new DevException(where + ".patch needs exactly one of agent | task");
				}
				p.obj("set");
			} else {
				Fields s = f.obj("say");
				s.nonBlank("agent");
				s.str("text");
			}
			out.add(new Event(i, t, label(o), o));
		}
		out.sort(Comparator.comparingDouble(Event::t).thenComparingInt(Event::index));
		return new Timeline(out);
	}

	/**
	 * {@code dev.type} with more than one character: one event per character. The gap after each
	 * character is msPerChar x (1 + jitter x u), u a deterministic pseudo-random value in [-1, 1]
	 * from the seed; spaces and punctuation get a longer pause. Same seed, same rhythm.
	 */
	private static void expandTyping(List<Event> out, int index, double t, Fields f) {
		String text = f.str("text");
		double perChar = f.optNum("msPerChar", 75, 10, 2000) / 1000.0;
		double jitter = f.optNum("jitter", 0.3, 0, 0.9);
		long seed = f.optLong("seed", 1, 0, Long.MAX_VALUE);
		double at = t;
		int i = 0;
		int n = 0;
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			i += Character.charCount(cp);
			JsonObject body = new JsonObject();
			body.addProperty("t", at);
			body.addProperty("cmd", "dev.type");
			body.addProperty("text", ch);
			out.add(new Event(index, at, "dev.type '" + ch + "'", body));
			double u = hash01(seed, n++) * 2 - 1;
			double gap = perChar * (1 + jitter * u);
			if (cp == ' ' || ".,;:!?".indexOf(cp) >= 0) {
				gap *= 1.6;
			}
			at += gap;
		}
	}

	private static double hash01(long seed, int n) {
		long z = seed * 0x9E3779B97F4A7C15L + n * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return (z >>> 11) / (double) (1L << 53);
	}

	private static String label(JsonObject o) {
		if (o.has("cmd")) {
			return o.get("cmd").getAsString();
		}
		if (o.has("inject")) {
			return "inject " + o.getAsJsonObject("inject").get("type").getAsString();
		}
		if (o.has("patch")) {
			JsonObject p = o.getAsJsonObject("patch");
			return "patch " + (p.has("agent") ? "agent " + p.get("agent").getAsString() : "task " + p.get("task").getAsString());
		}
		return "say " + o.getAsJsonObject("say").get("agent").getAsString().toLowerCase(Locale.ROOT);
	}
}
