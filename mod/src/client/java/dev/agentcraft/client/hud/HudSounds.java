package dev.agentcraft.client.hud;

import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Goal;
import dev.agentcraft.client.foreman.Protocol.GoalStatus;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * Subtle vanilla UI sounds: a soft bell when a decision opens, a chime when a task is done, a
 * two-note chime when the goal completes. Live upserts only (never on a (re)connect snapshot), at
 * most one sound per kind per 1.5 s. Off when the game was started muted ({@code AGENTCRAFT_MUTE=1},
 * the dev/QA default) or with {@code /sound off} in the console. The {@link #log()} counters let QA
 * check what would have played even while muted.
 */
public final class HudSounds {
	private static boolean enabled = true;
	private static long lastBell;
	private static long lastChime;
	private static int bells;
	private static int chimes;
	private static @Nullable String lastEvent;

	private HudSounds() {
	}

	public static void init() {
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onDecision(@Nullable Decision previous, Decision decision) {
				if (decision.isOpen() && (previous == null || !previous.isOpen())) {
					bell("decision " + decision.id());
				}
			}

			@Override
			public void onTask(@Nullable Task previous, Task task) {
				if (task.status() == TaskStatus.DONE && previous != null && previous.status() != TaskStatus.DONE) {
					chime("task " + task.id() + " done", false);
				}
			}

			@Override
			public void onGoal(@Nullable Goal previous, Goal goal) {
				if (goal.status() == GoalStatus.DONE && previous != null && previous.status() != GoalStatus.DONE) {
					chime("goal " + goal.id() + " done", true);
				}
			}
		});
	}

	/** True when the game itself was started muted (AGENTCRAFT_MUTE=1). */
	public static boolean forcedMute() {
		return ClientEnv.MUTE;
	}

	public static boolean enabled() {
		return enabled && !forcedMute();
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static int bells() {
		return bells;
	}

	public static int chimes() {
		return chimes;
	}

	public static @Nullable String lastEvent() {
		return lastEvent;
	}

	public static void bell(String why) {
		long now = System.currentTimeMillis();
		if (now - lastBell < 1500) {
			return;
		}
		lastBell = now;
		bells++;
		lastEvent = "bell: " + why;
		play(true, false);
	}

	public static void chime(String why, boolean big) {
		long now = System.currentTimeMillis();
		if (now - lastChime < 1500 && !big) {
			return;
		}
		lastChime = now;
		chimes++;
		lastEvent = "chime: " + why;
		play(false, big);
	}

	private static void play(boolean bell, boolean big) {
		if (!enabled()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		var sm = mc.getSoundManager();
		if (bell) {
			sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.19f, 0.35f));
		} else if (big) {
			sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0f, 0.4f));
			sm.play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 0.6f));
		} else {
			sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.5f, 0.25f));
		}
	}

	public static String log() {
		return "bells=" + bells + " chimes=" + chimes + (lastEvent != null ? " last=" + lastEvent : "");
	}
}
