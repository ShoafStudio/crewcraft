package dev.agentcraft.client.dev;

import dev.agentcraft.AgentCraft;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;

/**
 * Runs work at the END of rendered frames (hooked from Minecraft.renderFrame TAIL, after the
 * frame was presented). Everything here runs on the render thread. Registration is thread-safe.
 */
public final class FrameScheduler {
	private static final ConcurrentLinkedQueue<Waiter> INCOMING = new ConcurrentLinkedQueue<>();
	private static final List<Waiter> ACTIVE = new ArrayList<>();
	private static volatile long frame;
	private static volatile long lastFrameNanos;
	private static final long START_NANOS = System.nanoTime();
	/** Frame age (ms) above which the render thread counts as stalled (dev.ping {@code stalled}). */
	public static final long STALL_MS = 5_000;

	private FrameScheduler() {
	}

	/** Number of frames rendered since startup. */
	public static long frame() {
		return frame;
	}

	/**
	 * Milliseconds since the render thread last finished a frame (since startup if no frame yet).
	 * Readable from any thread; this is how a hung render thread is detected.
	 */
	public static long msSinceLastFrame() {
		long last = lastFrameNanos;
		return (System.nanoTime() - (last == 0 ? START_NANOS : last)) / 1_000_000L;
	}

	/** True when the render thread has rendered before but has not finished a frame for {@link #STALL_MS}. */
	public static boolean stalled() {
		return frame > 0 && msSinceLastFrame() >= STALL_MS;
	}

	/** Completes (on the render thread, at frame end) after {@code frames} more frames were rendered. */
	public static CompletableFuture<Void> afterFrames(int frames) {
		CompletableFuture<Void> f = new CompletableFuture<>();
		INCOMING.add(new Waiter(f, () -> true, Math.max(1, frames), 0, Long.MAX_VALUE, null));
		return f;
	}

	/**
	 * Completes when {@code condition} has been true at {@code stableFrames} consecutive frame ends
	 * (and at least {@code minFrames} frames have passed). Fails with TimeoutException after
	 * {@code timeoutMs}. The condition is evaluated on the render thread.
	 */
	public static CompletableFuture<Void> when(BooleanSupplier condition, int minFrames, int stableFrames, long timeoutMs, String what) {
		CompletableFuture<Void> f = new CompletableFuture<>();
		INCOMING.add(new Waiter(f, condition, Math.max(0, minFrames), Math.max(1, stableFrames), System.currentTimeMillis() + timeoutMs, what));
		return f;
	}

	/** Runs {@code task} at the end of the next rendered frame. */
	public static CompletableFuture<Void> atFrameEnd(Runnable task) {
		return afterFrames(1).thenRun(task);
	}

	public static void onFrameEnd(Minecraft mc) {
		frame++;
		lastFrameNanos = System.nanoTime();
		Waiter w;
		while ((w = INCOMING.poll()) != null) {
			w.startFrame = frame;
			ACTIVE.add(w);
		}
		if (ACTIVE.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		// Iterate over a snapshot: completing a future may register new waiters.
		List<Waiter> snapshot = new ArrayList<>(ACTIVE);
		for (Waiter waiter : snapshot) {
			try {
				if (waiter.poll(now)) {
					ACTIVE.remove(waiter);
				}
			} catch (Throwable t) {
				AgentCraft.LOGGER.warn("DevBridge frame waiter failed", t);
				ACTIVE.remove(waiter);
				waiter.future.completeExceptionally(t);
			}
		}
		for (Iterator<Waiter> it = ACTIVE.iterator(); it.hasNext(); ) {
			if (it.next().future.isDone()) {
				it.remove();
			}
		}
	}

	private static final class Waiter {
		final CompletableFuture<Void> future;
		final BooleanSupplier condition;
		final int minFrames;
		final int stableFrames;
		final long deadline;
		final String what;
		long startFrame;
		int stable;

		Waiter(CompletableFuture<Void> future, BooleanSupplier condition, int minFrames, int stableFrames, long deadline, String what) {
			this.future = future;
			this.condition = condition;
			this.minFrames = minFrames;
			this.stableFrames = stableFrames;
			this.deadline = deadline;
			this.what = what;
		}

		/** @return true when finished (completed or failed). */
		boolean poll(long now) {
			if (future.isDone()) {
				return true;
			}
			long elapsedFrames = frame - startFrame + 1;
			if (stableFrames == 0) {
				// pure frame count
				if (elapsedFrames >= minFrames) {
					future.complete(null);
					return true;
				}
				return false;
			}
			if (condition.getAsBoolean()) {
				stable++;
			} else {
				stable = 0;
			}
			if (stable >= stableFrames && elapsedFrames >= minFrames) {
				future.complete(null);
				return true;
			}
			if (now > deadline) {
				future.completeExceptionally(new TimeoutException("timed out waiting for " + (what == null ? "condition" : what)));
				return true;
			}
			return false;
		}
	}
}
