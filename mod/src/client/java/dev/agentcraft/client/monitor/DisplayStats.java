package dev.agentcraft.client.monitor;

import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.FrameScheduler;

/**
 * CPU cost of the in-world displays (render thread): time spent in the monitor and task wall
 * renderers' extract + submit, and how many layouts were rebuilt, averaged per rendered frame since
 * the last reset. Read with {@code dev.displays {stats: true, reset?: true}}.
 */
public final class DisplayStats {
	public enum Kind {
		MONITOR, BOARD
	}

	private static final long[] NANOS = new long[2];
	private static final long[] CALLS = new long[2];
	private static final long[] REBUILDS = new long[2];
	private static long startFrame = FrameScheduler.frame();
	private static long startNanos = System.nanoTime();

	private DisplayStats() {
	}

	public static void add(Kind k, long nanos) {
		NANOS[k.ordinal()] += nanos;
		CALLS[k.ordinal()]++;
	}

	public static void rebuilt(Kind k) {
		REBUILDS[k.ordinal()]++;
	}

	public static void reset() {
		java.util.Arrays.fill(NANOS, 0);
		java.util.Arrays.fill(CALLS, 0);
		java.util.Arrays.fill(REBUILDS, 0);
		startFrame = FrameScheduler.frame();
		startNanos = System.nanoTime();
	}

	public static JsonObject json() {
		long frames = Math.max(1, FrameScheduler.frame() - startFrame);
		JsonObject o = new JsonObject();
		o.addProperty("frames", frames);
		o.addProperty("seconds", Math.round((System.nanoTime() - startNanos) / 1e7) / 100.0);
		for (Kind k : Kind.values()) {
			JsonObject j = new JsonObject();
			int i = k.ordinal();
			j.addProperty("usPerFrame", Math.round(NANOS[i] / 1000.0 / frames * 10) / 10.0);
			j.addProperty("callsPerFrame", Math.round(CALLS[i] * 10.0 / frames) / 10.0);
			j.addProperty("rebuilds", REBUILDS[i]);
			o.add(k.name().toLowerCase(java.util.Locale.ROOT), j);
		}
		return o;
	}
}
