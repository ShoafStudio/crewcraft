package dev.agentcraft.layout;

import java.util.List;

/**
 * The anchor naming contract shared by every HQ builder (writes them) and every consumer (agent
 * motion, renderers, QA scenes via {@code dev.anchors}). Phase 3's real HQ builder must write at
 * least the required names below; extra names are fine.
 *
 * <pre>
 * desk_&lt;agentId&gt;      feet spot at the agent's desk, facing the monitor          (one per cast agent)
 * monitor_&lt;agentId&gt;   centre of that agent's monitor screen, yaw = screen front
 * &lt;station&gt;           shared station spot: library, terminal, testbench, mergestation, meeting, lounge, user
 * &lt;station&gt;_2.._N     extra slots at the same station (agents spread over them, see {@link #slot})
 * task_wall           centre of the Task Wall surface, yaw = front
 * decision_podium     the podium block (centre top), yaw = front
 * goal_atrium         centre of the Goal Atrium floor
 * entrance            just inside the main door, facing in
 * spawn               where the player should start
 * cam_&lt;name&gt;          QA camera points (eye position + yaw/pitch), e.g. cam_overview, cam_blockrow
 * </pre>
 */
public final class AnchorNames {
	public static final String DESK_PREFIX = "desk_";
	public static final String MONITOR_PREFIX = "monitor_";
	public static final String CAM_PREFIX = "cam_";

	public static final String LIBRARY = "library";
	public static final String TERMINAL = "terminal";
	public static final String TESTBENCH = "testbench";
	public static final String MERGESTATION = "mergestation";
	public static final String MEETING = "meeting";
	public static final String LOUNGE = "lounge";
	public static final String USER = "user";

	public static final String TASK_WALL = "task_wall";
	public static final String DECISION_PODIUM = "decision_podium";
	public static final String GOAL_ATRIUM = "goal_atrium";
	public static final String ENTRANCE = "entrance";
	public static final String SPAWN = "spawn";

	/** Protocol {@code Agent.station} values that map to shared station anchors (desk is per agent). */
	public static final List<String> SHARED_STATIONS = List.of(LIBRARY, TERMINAL, TESTBENCH, MERGESTATION, MEETING, LOUNGE, USER);

	private AnchorNames() {
	}

	public static String desk(String agentId) {
		return DESK_PREFIX + agentId;
	}

	public static String monitor(String agentId) {
		return MONITOR_PREFIX + agentId;
	}

	/** Slot n (1-based) of a shared station: 1 = the station name itself, then {@code station_2}, ... */
	public static String slot(String station, int n) {
		return n <= 1 ? station : station + "_" + n;
	}
}
