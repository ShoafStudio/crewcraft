package dev.agentcraft.block;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/** {@code status} of a status lamp. Colours are the status palette (docs/visual-bar.md). */
public enum LampStatus implements StringRepresentable {
	OFF, IDLE, THINKING, WORKING, WAITING, ERROR, DONE;

	private final String serialized = name().toLowerCase(Locale.ROOT);

	@Override
	public String getSerializedName() {
		return serialized;
	}

	/** Lamp status for a protocol agent state (idle, thinking, reading, ..., done, error). */
	public static LampStatus forAgentState(String state) {
		if (state == null) {
			return OFF;
		}
		return switch (state) {
			case "idle" -> IDLE;
			case "thinking" -> THINKING;
			case "reading", "editing", "running", "testing" -> WORKING;
			case "waiting_user" -> WAITING;
			case "blocked", "error" -> ERROR;
			case "done" -> DONE;
			default -> OFF;
		};
	}

	/** Lamp status for a CI status (unknown, running, pass, fail). */
	public static LampStatus forCi(String ci) {
		if (ci == null) {
			return OFF;
		}
		return switch (ci) {
			case "running" -> WORKING;
			case "pass" -> DONE;
			case "fail" -> ERROR;
			default -> IDLE;
		};
	}
}
