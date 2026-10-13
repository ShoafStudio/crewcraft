package dev.agentcraft.client.ui;

import dev.agentcraft.AgentCraft;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Crash guard for client tick, render and HUD handlers. An exception thrown out of an
 * {@code END_CLIENT_TICK}, level-render or HUD callback takes down the client and, in singleplayer,
 * the integrated server with it. Each handler runs through {@link #run}: a failure is logged with its
 * stack trace the first time per kind (then again, as a one-line count, at 10, 100, 1000, ... failures)
 * and the game keeps running; the handler simply runs again next time.
 *
 * <p>{@link VirtualMachineError}s (out of memory and the like) are rethrown: the game cannot
 * meaningfully continue after those.
 */
public final class Guard {
	private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

	private Guard() {
	}

	/** Runs {@code r}; any failure but a {@link VirtualMachineError} is logged and swallowed. */
	public static void run(String kind, Runnable r) {
		try {
			r.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			report(kind, t);
		}
	}

	/** Like {@link #run}, returning {@code fallback} when the call fails. */
	public static <T> T call(String kind, Supplier<T> s, T fallback) {
		try {
			return s.get();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			report(kind, t);
			return fallback;
		}
	}

	private static void report(String kind, Throwable t) {
		int n = COUNTS.merge(kind, 1, Integer::sum);
		try {
			if (n == 1) {
				AgentCraft.LOGGER.error("The AgentCraft {} handler failed; the game keeps running", kind, t);
			} else if (isPowerOfTen(n)) {
				AgentCraft.LOGGER.error("The AgentCraft {} handler has failed {} times (latest: {})", kind, n, t.toString());
			}
		} catch (RuntimeException ignored) {
			// a broken logger must not undo the guard
		}
	}

	private static boolean isPowerOfTen(int n) {
		while (n >= 10 && n % 10 == 0) {
			n /= 10;
		}
		return n == 1;
	}
}
