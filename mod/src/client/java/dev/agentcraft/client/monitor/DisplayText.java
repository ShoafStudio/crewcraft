package dev.agentcraft.client.monitor;

import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import org.jspecify.annotations.Nullable;

/**
 * Words shared by every in-world display, so the monitors, the Task Wall and the HUD connection
 * pill ({@code hud.ConnectionBanner}) always say the same thing about the Foreman link.
 */
public final class DisplayText {
	/** The link was live and dropped: the last known state stays on screen, dimmed. */
	public static final String OFFLINE = "Foreman offline";

	private DisplayText() {
	}

	/** Why there is nothing to show yet (no snapshot ever received): the HUD pill's wording. */
	public static String noData(@Nullable ForemanState s) {
		if (s == null || s.link().phase() == LinkStatus.Phase.DISABLED) {
			return "Foreman link off";
		}
		return "Foreman not running";
	}
}
