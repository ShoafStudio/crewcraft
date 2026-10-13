package dev.agentcraft.client.agents;

import dev.agentcraft.client.ui.WorldUi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Nameplate declutter: makes sure agent plates never cover each other on screen. Runs once per
 * frame on the client thread at the end of level extraction (Fabric
 * {@code LevelExtractionEvents.END_EXTRACTION}), after every agent's {@link AgentRenderState} was
 * extracted and before anything is submitted, and writes the result into those states.
 *
 * <ol>
 *   <li>Project every visible plate (in front of the camera, line of sight to the plate or head) to
 *       a screen rectangle. Plates scale exactly with 1/depth because they are camera-facing.</li>
 *   <li>Low-value plates (idle, done, off shift, Foreman offline: {@link AgentView#plateWeight()}
 *       &le; 2) collapse to the name-only pill when their full plate would overlap another plate.
 *       They expand again only after the full plate has had room (plus a margin) for a moment, so
 *       they don't flicker. The plate under the crosshair (or whose agent is targeted) always shows
 *       in full.</li>
 *   <li>Plates are placed in priority order (crosshair, waiting, error, working/thinking, then all
 *       low-value plates as one tier; within a tier nearest first, with a bonus for plates that
 *       already sit at their natural spot). Each takes the lowest lift above its agent's head that clears every plate placed
 *       before it. Lifts animate (fast up, calm down; a camera cut snaps); a lifted plate draws a
 *       leader line to its head. A crowd taller than {@value #MAX_LIFT_PLATES} plates hides its
 *       low-value plates; plates nearer than {@value #HIDE_NEARER} blocks are hidden.</li>
 *   <li>Every plate gets a small depth nudge by rank ({@link WorldUi#billboard} homothety: same look,
 *       nearer depth), so while two plates still overlap (mid-animation, same distance) the
 *       higher-ranked one hides the other completely. Plates are opaque ({@link WorldUi.Layer#SOLID}),
 *       so a covered plate's text can't ghost through.</li>
 * </ol>
 */
public final class PlateLayout {
	/** Screen pixels kept free between two plates. */
	private static final float GAP = 2f;
	/** Extra free screen pixels a compact plate needs around its full size before it expands again. */
	private static final float HYSTERESIS = 8f;
	/** Seconds a compact plate must have had room before it expands. */
	private static final float EXPAND_DELAY = 0.35f;
	/** Lift animation rates (1/s, exponential): rise fast to get out of the way, settle back down calmly. */
	private static final float RISE_RATE = 30f;
	private static final float FALL_RATE = 14f;
	/** Target changes up to this many plate px per frame are followed directly (no animation lag). */
	private static final float MAX_DRIFT_PER_FRAME = 4f;
	/** Depth nudge per rank, as a fraction of the camera distance (~1000x the polygon offset of text). */
	public static final float NUDGE_PER_RANK = 0.0015f;
	private static final int MAX_RANKS = 24;
	/** A plate already at its natural spot sorts as if it were this much nearer (blocks), so plates don't swap. */
	private static final float STICKY_DEPTH = 0.75f;
	/** Lifts are capped at this many full plate heights; a low-value plate that would need more is hidden. */
	private static final float MAX_LIFT_PLATES = 8f;
	/** Plates nearer to the camera than this (blocks) are hidden: the agent is in your face and the plate would fill the screen. */
	public static final float HIDE_NEARER = 1.3f;
	/** Seated agents nearer than this (blocks) show no plate: their own monitor names them. */
	public static final float NEAR_DESK = 6.5f;
	/** A camera jump (blocks / degrees in one frame) counts as a cut: plates snap instead of animating. */
	private static final double CUT_DISTANCE = 1.5;
	private static final float CUT_DEGREES = 25f;
	/**
	 * Near plates stop growing on screen at this size (screen px per plate px, as a multiple of the
	 * GUI scale): a plate is never drawn bigger than ~1.1x the GUI's own text, however close its
	 * agent stands (at 2 blocks a full-size plate plus its "!" used to fill the upper middle of the
	 * view and push other plates off screen). Kicks in below ~6 blocks at 1080p / FOV 70.
	 */
	private static final float NEAR_MAX_GUI = 1.1f;
	/** Plates (with their stack) stay this far below the top edge of the screen (fraction of its height). */
	private static final float TOP_MARGIN = 0.012f;
	/**
	 * A plate that does not fit on screen above its natural spot may come down by up to this much
	 * (blocks, at the agent): over the head to about the shoulders, rather than being cut off.
	 */
	private static final float DOWN_MAX = 0.8f;
	/** Max gaps cut into one leader line. */
	private static final int MAX_GAPS = 4;

	/** Per-agent layout memory across frames. */
	static final class Track {
		long frame = Long.MIN_VALUE;
		float lift;
		float targetLift;
		float prevTarget;
		boolean compact;
		float freeFor;
		boolean grounded = true;
		int rank = -1;
		boolean focused;
		boolean hasRect;
		float rx0, ry0, rx1, ry1;
		long visTick = Long.MIN_VALUE;
		boolean visible = true;
		float nudge;
		float scale = 1f;
		float depth;
		int weight;
		boolean capped;
		/** Leader-line gaps (plate-space y pairs) handed to the render state; reused. */
		final float[] gaps = new float[2 * MAX_GAPS];
		int gapCount;

		/** Not laid out this frame (hidden, behind a wall): forget the lift, take no space. */
		void clear() {
			hasRect = false;
			lift = 0;
			targetLift = 0;
			grounded = true;
			rank = -1;
		}
	}

	private static final class Item {
		AgentRenderState s;
		Track t;
		float sx, sy, k, f, depth, sortKey;
		int weight;
		boolean focused, compactable, compact, fresh, hidden;
		float x0, y0, x1, y1;

		/** Width of the full plate or of what stacks on it (speech bubble), whichever is wider (px). */
		float fullW() {
			return Math.max(s.plateFull.width(), s.stackWidth);
		}

		float fullX0() {
			return sx - fullW() * k / 2f;
		}

		float fullX1() {
			return sx + fullW() * k / 2f;
		}

		float fullY0() {
			return sy - (s.plateFull.height() + s.stackHeight) * k;
		}
	}

	private static final Map<String, Track> TRACKS = new HashMap<>();
	private static final List<Item> ITEMS = new ArrayList<>();
	/** Reused items (the layout runs every frame: no allocation once warmed up). */
	private static final List<Item> POOL = new ArrayList<>();
	private static final Comparator<Item> PRIORITY = Comparator.<Item>comparingInt(i -> -i.weight).thenComparingDouble(i -> i.sortKey)
		.thenComparing(i -> i.s.agentId);
	private static final Vector4f V = new Vector4f();
	private static float[] candidates = new float[16];
	private static long lastNanos;
	private static long frame;
	private static boolean snapNext;
	private static int overlaps;
	/** "a/b" agent id pairs that overlapped in the last frame (QA), reused. */
	private static final StringBuilder OVERLAP_PAIRS = new StringBuilder();
	private static String overlapPairs = "";
	private static int laidOut;
	private static float layoutMicros;
	private static @Nullable Vec3 lastCamPos;
	private static float lastCamYaw;
	private static float lastCamPitch;

	private PlateLayout() {
	}

	private static Item item(int index) {
		while (POOL.size() <= index) {
			POOL.add(new Item());
		}
		return POOL.get(index);
	}

	/** Skip the lift animation and the expand delay once (QA: settle before a screenshot). */
	public static void snapNextFrame() {
		snapNext = true;
	}

	/** Pairs of drawn plates that overlapped on screen in the last frame (QA; 0 when settled). */
	public static int overlaps() {
		return overlaps;
	}

	/** Which plates overlapped in the last frame ("rowan/wren kit/tove"), "" when none (QA). */
	public static String overlapPairs() {
		return overlapPairs;
	}

	/** Plates that took part in the last layout (visible ones). */
	public static int laidOut() {
		return laidOut;
	}

	/** The last layout of one agent's plate, or null if it was not laid out (QA). */
	static @Nullable Track track(String agentId) {
		Track t = TRACKS.get(agentId);
		return t == null || t.frame != frame || !t.hasRect ? null : t;
	}

	/** Mean cost of one layout pass in microseconds (exponential average; QA). */
	public static float layoutMicros() {
		return layoutMicros;
	}

	/** Client thread, once per frame, after entity extraction. */
	public static void layout(LevelRenderState level) {
		long start = System.nanoTime();
		try {
			run(level, start);
		} finally {
			RESERVED.clear();
		}
		layoutMicros += ((System.nanoTime() - start) / 1000f - layoutMicros) * 0.05f;
	}

	// ------------------------------------------------------------------ reserved screen space
	// (additive, Phase 3 console/decisions fix round: other billboards that plates must clear)

	/** A camera-facing billboard that is not a nameplate; see {@link #reserve}. */
	private record Reservation(double x, double y, double z, float width, float height, float blocksPerPx) {
	}

	private static final List<Reservation> RESERVED = new ArrayList<>();
	/** Screen rects (x0, y0, x1, y1) of this frame's reservations. */
	private static float[] reservedRects = new float[16];
	private static int reservedCount;
	private static int reservedOverlaps;

	/**
	 * Reserve screen space for a camera-facing billboard that is not a nameplate (the decision
	 * podium's speech bubble), so plates lift to clear it exactly as they clear each other (with a
	 * leader line to the head). Call it every frame the billboard shows, during level extraction (a
	 * block entity renderer's extract step runs before {@code END_EXTRACTION}); it holds for that frame
	 * only. {@code x, y, z}: world position of the billboard's bottom centre (it extends upwards);
	 * {@code width, height}: its size in its own pixels; {@code blocksPerPx}: the world size of one of
	 * those pixels at that position.
	 */
	public static void reserve(double x, double y, double z, float width, float height, float blocksPerPx) {
		if (RESERVED.size() < 32) {
			RESERVED.add(new Reservation(x, y, z, width, height, blocksPerPx));
		}
	}

	/** Reserved billboards laid out in the last frame (QA). */
	public static int reservedCount() {
		return reservedCount;
	}

	/** Drawn plates that overlapped a reserved billboard on screen in the last frame (QA; 0 when settled). */
	public static int reservedOverlaps() {
		return reservedOverlaps;
	}

	private static void projectReservations(CameraRenderState cam, float f, int w, int h) {
		reservedCount = 0;
		for (Reservation r : RESERVED) {
			V.set((float) (r.x() - cam.pos.x), (float) (r.y() - cam.pos.y), (float) (r.z() - cam.pos.z), 1f);
			cam.viewRotationMatrix.transform(V);
			float depth = -V.z;
			if (depth < 0.05f) {
				continue;
			}
			cam.projectionMatrix.transform(V);
			if (V.w <= 1e-6f) {
				continue;
			}
			float sx = (V.x / V.w * 0.5f + 0.5f) * w;
			float sy = (0.5f - V.y / V.w * 0.5f) * h;
			float k = r.blocksPerPx() * f / depth;
			if (reservedRects.length < (reservedCount + 1) * 4) {
				reservedRects = java.util.Arrays.copyOf(reservedRects, reservedRects.length * 2);
			}
			int o = reservedCount * 4;
			reservedRects[o] = sx - r.width() * k / 2f;
			reservedRects[o + 1] = sy - r.height() * k;
			reservedRects[o + 2] = sx + r.width() * k / 2f;
			reservedRects[o + 3] = sy;
			reservedCount++;
		}
	}

	private static void run(LevelRenderState level, long now) {
		Minecraft mc = Minecraft.getInstance();
		float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		frame++;
		boolean snap = snapNext;
		snapNext = false;
		ITEMS.clear();
		overlaps = 0;
		laidOut = 0;
		overlapPairs = "";
		reservedCount = 0;
		reservedOverlaps = 0;
		ClientLevel lvl = mc.level;
		int w = mc.getWindow().getWidth();
		int h = mc.getWindow().getHeight();
		CameraRenderState cam = level.cameraRenderState;
		if (lvl == null || w <= 0 || h <= 0 || !cam.initialized) {
			return;
		}
		float f = cam.projectionMatrix.m11() * h * 0.5f;
		float kMax = (float) mc.getWindow().getGuiScale() * NEAR_MAX_GUI;
		projectReservations(cam, f, w, h);
		if (lastCamPos == null || lastCamPos.distanceToSqr(cam.pos) > CUT_DISTANCE * CUT_DISTANCE
			|| Math.abs(Mth.wrapDegrees(cam.yRot - lastCamYaw)) > CUT_DEGREES || Math.abs(cam.xRot - lastCamPitch) > CUT_DEGREES) {
			snap = true; // camera cut (dev.camera, teleport): lay out from scratch, no slide from the old view
		}
		lastCamPos = cam.pos;
		lastCamYaw = cam.yRot;
		lastCamPitch = cam.xRot;
		long tick = lvl.getGameTime();
		boolean screenOpen = mc.gui.screen() != null;
		BlockPos camBlock = BlockPos.containing(cam.pos);
		boolean camInSolid = lvl.getBlockState(camBlock).isSolidRender();

		for (EntityRenderState es : level.entityRenderStates) {
			if (!(es instanceof AgentRenderState s) || s.plateFull == null || s.plateCompact == null) {
				continue;
			}
			Track t = TRACKS.computeIfAbsent(s.agentId, id -> new Track());
			boolean fresh = t.frame != frame - 1;
			t.frame = frame;
			V.set((float) (s.x - cam.pos.x), (float) (s.y + s.plateBase - cam.pos.y), (float) (s.z - cam.pos.z), 1f);
			cam.viewRotationMatrix.transform(V);
			float depth = -V.z;
			if (depth < HIDE_NEARER) {
				// behind the camera, or so close the plate would fill the screen
				s.plate = null;
				t.clear();
				continue;
			}
			if (screenOpen || !visible(lvl, cam.pos, camBlock, camInSolid, s, t, tick)) {
				// a screen is open (the console sits over the world: no plates peeking out behind it), or
				// the agent's body is hidden (behind a shelf, a wall): no orphan plate floating over it
				s.plate = null;
				t.clear();
				continue;
			}
			if (s.atMonitor && depth < NEAR_DESK) {
				// seated at its desk, seen up close: its monitor already says who and what (header), and
				// the plate would sit right on top of the monitor's text
				s.plate = null;
				t.clear();
				continue;
			}
			cam.projectionMatrix.transform(V);
			if (V.w <= 1e-6f) {
				continue;
			}
			Item it = item(ITEMS.size());
			it.s = s;
			it.compact = false;
			it.hidden = false;
			it.sortKey = 0;
			it.t = t;
			it.fresh = fresh;
			it.depth = depth;
			it.sx = (V.x / V.w * 0.5f + 0.5f) * w;
			it.sy = (0.5f - V.y / V.w * 0.5f) * h;
			it.k = WorldUi.PX * s.plateScale * f / depth;
			if (it.k > kMax) {
				// near agent: shrink the plate (and its stack) so it is no bigger on screen than kMax
				s.plateScale *= kMax / it.k;
				it.k = kMax;
			}
			it.f = f;
			boolean underCrosshair = !screenOpen && t.hasRect && w * 0.5f >= t.rx0 && w * 0.5f <= t.rx1 && h * 0.5f >= t.ry0 && h * 0.5f <= t.ry1;
			it.focused = s.plateCrosshair || underCrosshair;
			// low-value plates (idle, done, off shift, offline) form one tier: among them the nearest wins
			it.weight = it.focused ? 100 : s.plateWeight <= 2 ? 0 : s.plateWeight;
			it.compactable = !it.focused && s.plateWeight <= 2 && s.plateCompact != s.plateFull;
			ITEMS.add(it);
		}
		int n = ITEMS.size();
		laidOut = n;
		if (n == 0) {
			return;
		}

		// 1. collapse low-value plates whose full plate would overlap another plate
		for (Item a : ITEMS) {
			Track t = a.t;
			if (!a.compactable) {
				a.compact = false;
			} else {
				boolean blocked = false;
				boolean crowded = false;
				for (Item b : ITEMS) {
					if (b == a) {
						continue;
					}
					blocked |= overlap(a.fullX0(), a.fullY0(), a.fullX1(), a.sy, b.fullX0(), b.fullY0(), b.fullX1(), b.sy, GAP);
					crowded |= overlap(a.fullX0(), a.fullY0(), a.fullX1(), a.sy, b.fullX0(), b.fullY0(), b.fullX1(), b.sy, GAP + HYSTERESIS);
				}
				if (blocked || (t.compact && crowded)) {
					t.freeFor = 0;
					a.compact = true;
				} else if (t.compact && !snap && !a.fresh) {
					t.freeFor += dt;
					a.compact = t.freeFor < EXPAND_DELAY;
				} else {
					a.compact = false;
				}
			}
			t.compact = a.compact;
			t.focused = a.focused;
			a.sortKey = a.depth - (t.grounded ? STICKY_DEPTH : 0f);
		}

		// 2. place in priority order, each at the lowest free lift that keeps it on screen
		ITEMS.sort(PRIORITY);
		int ranks = Math.min(n, MAX_RANKS);
		float topMargin = Math.max(4f, h * TOP_MARGIN);
		for (int i = 0; i < n; i++) {
			Item a = ITEMS.get(i);
			AgentRenderState s = a.s;
			Track t = a.t;
			Nameplate.Data d = a.compact ? s.plateCompact : s.plateFull;
			// the plate plus whatever stacks on it (speech bubble, "!" marker) is one block of space
			float pw = Math.max(d.width(), s.stackWidth) * a.k;
			float ph = (d.height() + s.stackHeight) * a.k;
			a.x0 = a.sx - pw / 2f;
			a.x1 = a.sx + pw / 2f;
			a.y1 = a.sy;
			a.y0 = a.sy - ph;
			// allowed lifts (screen px): up to the cap and never past the top of the screen; a plate
			// whose head is on screen may come down (over its head) instead of being cut off
			boolean headOnScreen = a.sy > 0 && a.sy < h;
			float hi = Math.min(MAX_LIFT_PLATES * s.plateFull.height() * a.k, headOnScreen ? a.y0 - topMargin : Float.MAX_VALUE);
			float lo = headOnScreen ? Math.min(0f, -DOWN_MAX * a.f / a.depth) : 0f;
			float lift = findLift(a, i, lo, hi);
			t.capped = Float.isNaN(lift);
			if (t.capped) {
				if (a.weight == 0) {
					// no free spot on screen: the least important plates step out instead of overlapping
					a.hidden = true;
					s.plate = null;
					t.clear();
					continue;
				}
				// an important plate that cannot be placed free: as close to its free spot as the screen allows
				// (it overlaps; the rank nudge keeps it cleanly in front of / behind the other one)
				float up = findLift(a, i, 0f, Float.MAX_VALUE);
				lift = Math.max(lo, Math.min(hi, Float.isNaN(up) ? 0f : up));
			}
			a.y0 -= lift;
			a.y1 -= lift;
			t.prevTarget = t.targetLift;
			t.targetLift = lift / a.k;
			t.grounded = Math.abs(lift) < 0.5f;
			if (a.fresh || snap || crosses(a, i, t.lift * a.k, lift, ph)) {
				t.lift = t.targetLift;
			} else {
				// feed-forward: follow a target that drifts a little each frame (a plate growing as its agent
				// walks closer) without lag; only real jumps animate
				float drift = t.targetLift - t.prevTarget;
				if (Math.abs(drift) < MAX_DRIFT_PER_FRAME) {
					t.lift += drift;
				}
				float rate = t.targetLift > t.lift ? RISE_RATE : FALL_RATE;
				t.lift += (t.targetLift - t.lift) * (1f - (float) Math.exp(-dt * rate));
				if (Math.abs(t.targetLift - t.lift) < 0.75f) {
					t.lift = t.targetLift;
				}
				t.lift = Math.max(lo / a.k, t.lift);
				// a slide never runs through a plate placed before it (e.g. when a bubble pops up on a
				// neighbour): if the animated spot overlaps one while the target is free, jump
				if (!t.capped && overlapsPlaced(a.x0, a.sy - ph - t.lift * a.k, a.x1, a.sy - t.lift * a.k, i)) {
					t.lift = t.targetLift;
				}
			}
			t.rank = i;
			t.nudge = NUDGE_PER_RANK * Math.max(0, ranks - 1 - i);
			if (t.lift < -0.5f) {
				// pulled down over its own agent: sit clearly in front of the head (same look on screen)
				t.nudge = Math.max(t.nudge, Math.min(0.45f, 0.4f / a.depth));
			}
			t.scale = s.plateScale;
			t.depth = a.depth;
			t.weight = a.weight;
			s.plate = d;
			s.plateLift = t.lift;
			s.plateNudge = t.nudge;
			s.leaderGaps = null;
			s.leaderGapCount = 0;
			// the rectangle as drawn this frame (current, animated lift)
			float drawn = t.lift * a.k;
			t.rx0 = a.x0;
			t.rx1 = a.x1;
			t.ry0 = a.sy - ph - drawn;
			t.ry1 = a.sy - drawn;
			t.hasRect = true;
		}
		int shown = 0;
		StringBuilder pairs = OVERLAP_PAIRS;
		pairs.setLength(0);
		for (int i = 0; i < n; i++) {
			Item ai = ITEMS.get(i);
			if (ai.hidden) {
				continue;
			}
			shown++;
			Track p = ai.t;
			for (int j = i + 1; j < n; j++) {
				Item aj = ITEMS.get(j);
				if (aj.hidden) {
					continue;
				}
				Track q = aj.t;
				if (overlap(p.rx0, p.ry0, p.rx1, p.ry1, q.rx0, q.ry0, q.rx1, q.ry1, -0.5f)) {
					overlaps++;
					if (pairs.length() < 200) {
						pairs.append(pairs.isEmpty() ? "" : " ").append(ai.s.agentId).append('/').append(aj.s.agentId);
					}
				}
			}
			for (int r = 0; r < reservedCount; r++) {
				int o = r * 4;
				if (overlap(p.rx0, p.ry0, p.rx1, p.ry1, reservedRects[o], reservedRects[o + 1], reservedRects[o + 2], reservedRects[o + 3], -0.5f)) {
					reservedOverlaps++;
				}
			}
			leaderGaps(ai, i, n);
		}
		if (!pairs.isEmpty()) {
			overlapPairs = pairs.toString();
		}
		laidOut = shown;
		if ((frame & 1023) == 0) {
			TRACKS.values().removeIf(t -> frame - t.frame > 1024);
		}
	}

	/**
	 * Would sliding item {@code i} from lift {@code from} to lift {@code to} (screen px) carry it right
	 * across a plate placed before it (from above it to below it, or the reverse)? Then it jumps
	 * instead: swapping places through another plate reads worse than a quick cut. Moving up out of
	 * a plate's way (the usual case) still slides, behind the plate that took its spot.
	 */
	private static boolean crosses(Item a, int placed, float from, float to, float ph) {
		if (Math.abs(to - from) < 1f) {
			return false;
		}
		float curY0 = a.sy - ph - from;
		float curY1 = a.sy - from;
		float tgtY0 = a.sy - ph - to;
		float tgtY1 = a.sy - to;
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (p.hidden || !(a.x0 < p.x1 && a.x1 > p.x0)) {
				continue;
			}
			boolean curAbove = curY1 <= p.y0 + 1f;
			boolean curBelow = curY0 >= p.y1 - 1f;
			boolean tgtAbove = tgtY1 <= p.y0 + 1f;
			boolean tgtBelow = tgtY0 >= p.y1 - 1f;
			if (curAbove && tgtBelow || curBelow && tgtAbove) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The preferred free lift (screen px) in {@code [lo, hi]} at which item {@code i} clears the
	 * {@code i} items placed before it, or NaN when there is none. Preference: the lowest lift at or
	 * above the natural spot (0); only when nothing up there fits on screen, the smallest step down
	 * (negative lifts, pulling the plate over its own head). Candidates are the natural spot (or the
	 * nearest allowed lift to it), just above and just below every plate it overlaps horizontally.
	 */
	private static float findLift(Item a, int placed, float lo, float hi) {
		if (hi < lo) {
			return Float.NaN;
		}
		if (candidates.length < 2 * placed + reservedCount + 1) {
			candidates = new float[2 * placed + reservedCount + 8];
		}
		int m = 0;
		candidates[m++] = key(Math.max(lo, Math.min(hi, 0f)));
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (!p.hidden && a.x0 < p.x1 + GAP && a.x1 > p.x0 - GAP) {
				float above = a.y1 - (p.y0 - GAP);
				float below = a.y0 - (p.y1 + GAP);
				if (above >= lo && above <= hi) {
					candidates[m++] = key(above);
				}
				if (below >= lo && below <= hi) {
					candidates[m++] = key(below);
				}
			}
		}
		for (int r = 0; r < reservedCount; r++) {
			int o = r * 4;
			if (a.x0 < reservedRects[o + 2] + GAP && a.x1 > reservedRects[o] - GAP) {
				float l = a.y1 - (reservedRects[o + 1] - GAP);
				if (l >= lo && l <= hi) {
					candidates[m++] = key(l);
				}
			}
		}
		java.util.Arrays.sort(candidates, 0, m);
		for (int c = 0; c < m; c++) {
			float l = unkey(candidates[c]);
			if (free(a.x0, a.y0 - l, a.x1, a.y1 - l, placed)) {
				return l;
			}
		}
		return Float.NaN;
	}

	/** Sort key for a lift: lifts >= 0 ascending first, then negative lifts closest to 0 first. */
	private static float key(float lift) {
		return lift >= 0f ? lift : 1e7f - lift;
	}

	private static float unkey(float key) {
		return key < 1e7f ? key : -(key - 1e7f);
	}

	/** Does the rectangle overlap the rectangle of a plate placed before item {@code placed}, as drawn this frame? */
	private static boolean overlapsPlaced(float x0, float y0, float x1, float y1, int placed) {
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (!p.hidden && p.t.hasRect && overlap(x0, y0, x1, y1, p.t.rx0, p.t.ry0, p.t.rx1, p.t.ry1, -0.5f)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Cut the parts of item {@code i}'s leader line that run across another plate or bubble, so the
	 * line passes behind them instead of being drawn over their text. Plate-space y pairs (top,
	 * bottom) go to {@link AgentRenderState#leaderGaps}.
	 */
	private static void leaderGaps(Item a, int i, int n) {
		Track t = a.t;
		t.gapCount = 0;
		if (t.lift <= 3f) {
			return;
		}
		float lineTop = a.sy - t.lift * a.k;
		float lineBottom = a.sy - a.k;
		float lx = a.sx;
		int g = 0;
		for (int j = 0; j < n && g < MAX_GAPS; j++) {
			if (j == i) {
				continue;
			}
			Item b = ITEMS.get(j);
			Track q = b.t;
			if (b.hidden || !q.hasRect || lx < q.rx0 - 1f || lx > q.rx1 + 1f || q.ry1 <= lineTop || q.ry0 >= lineBottom) {
				continue;
			}
			float y0 = (Math.max(q.ry0, lineTop) - a.sy) / a.k - 1.5f;
			float y1 = (Math.min(q.ry1, lineBottom) - a.sy) / a.k + 1.5f;
			// insertion by top edge
			int at = g;
			while (at > 0 && t.gaps[2 * (at - 1)] > y0) {
				t.gaps[2 * at] = t.gaps[2 * (at - 1)];
				t.gaps[2 * at + 1] = t.gaps[2 * (at - 1) + 1];
				at--;
			}
			t.gaps[2 * at] = y0;
			t.gaps[2 * at + 1] = y1;
			g++;
		}
		t.gapCount = g;
		if (g > 0) {
			a.s.leaderGaps = t.gaps;
			a.s.leaderGapCount = g;
		}
	}

	private static boolean free(float x0, float y0, float x1, float y1, int placed) {
		for (int j = 0; j < placed; j++) {
			Item p = ITEMS.get(j);
			if (!p.hidden && overlap(x0, y0, x1, y1, p.x0, p.y0, p.x1, p.y1, GAP)) {
				return false;
			}
		}
		for (int r = 0; r < reservedCount; r++) {
			int o = r * 4;
			if (overlap(x0, y0, x1, y1, reservedRects[o], reservedRects[o + 1], reservedRects[o + 2], reservedRects[o + 3], GAP)) {
				return false;
			}
		}
		return true;
	}

	/** Do the rectangles come closer than {@code gap} on both axes (negative gap = must really overlap)? */
	private static boolean overlap(float ax0, float ay0, float ax1, float ay1, float bx0, float by0, float bx1, float by1, float gap) {
		return ax0 < bx1 + gap && ax1 > bx0 - gap && ay0 < by1 + gap && ay1 > by0 - gap;
	}

	/** Line of sight from the camera to the plate or the head, cached per game tick. */
	private static boolean visible(ClientLevel level, Vec3 cam, BlockPos camBlock, boolean camInSolid, AgentRenderState s, Track t, long tick) {
		if (t.visTick == tick) {
			return t.visible;
		}
		t.visTick = tick;
		// the body must show (head or chest), not just the air above it: a plate whose agent stands
		// behind a bookshelf would float over nothing
		double head = Math.min(1.6, s.plateBase - 0.45);
		t.visible = clear(level, cam, camBlock, camInSolid, new Vec3(s.x, s.y + head, s.z))
			|| clear(level, cam, camBlock, camInSolid, new Vec3(s.x, s.y + head - 0.5, s.z));
		return t.visible;
	}

	/**
	 * Is nothing solid between {@code target} and the camera? Cast from the target towards the camera.
	 * A spectator camera inside a wall sees through the wall it stands in (vanilla culls it), so then
	 * hits within 1.5 blocks of the camera don't count; the camera's own block never counts.
	 */
	private static boolean clear(ClientLevel level, Vec3 cam, BlockPos camBlock, boolean camInSolid, Vec3 target) {
		BlockHitResult hit = level.clip(new ClipContext(target, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(camBlock)
			|| camInSolid && hit.getLocation().distanceToSqr(cam) < 1.5 * 1.5;
	}
}
