package dev.agentcraft.client.dev.play;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ClientEnv;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.DevCamera;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanFeature;
import dev.agentcraft.client.mixin.CameraAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Real-time shot playback behind {@code dev.play} (see mod/DEV.md "Shot playback"): a camera path
 * and a timeline of events, played on the wall clock so a screen recorder (OBS) captures it.
 *
 * <p>Per loop iteration, on the render thread:
 * <ol>
 * <li>{@link #beforeTick} ({@code Minecraft.runTick} HEAD, before the client ticks): the first call
 * starts the clock (t = 0); then every timeline event with {@code t <= elapsed} runs, so its effect
 * is in this frame;</li>
 * <li>{@link #beforeRender} ({@code Minecraft.renderFrame} HEAD, after the ticks): the path is
 * sampled at the time the frame will be presented (previous frame end + the average frame interval,
 * so camera steps are as even as the display's frame pacing) and the player is snapped there with its previous position and
 * rotation equal to the current ones, so {@code Camera.alignWithEntity} lands exactly on the path
 * (no tick interpolation, no jitter); the FOV is pinned to the path's FOV;</li>
 * <li>{@link #afterRender} ({@code renderFrame} TAIL): frame timing and the camera actually rendered
 * are logged; after the end pose has been held for {@code holdEndMs} the shot ends.</li>
 * </ol>
 * The HUD is hidden while playing (unless {@code showHud}); spectator mode (set by the tool's
 * {@code dev.camera}) shows no hand.
 */
public final class ShotPlayer {
	private static volatile Session session;

	private ShotPlayer() {
	}

	static final class Session {
		final String name;
		final double duration;
		final CameraPath path;
		final Timeline timeline;
		final boolean showHud;
		final boolean holdForeman;
		final String release;
		final long holdEndNanos;
		final boolean writeLog;
		final JsonObject spec;
		final CompletableFuture<JsonObject> result = new CompletableFuture<>();

		long t0;
		boolean pathDone;
		long endAt;
		int nextEvent;
		volatile String stopReason;
		boolean savedHudHidden;
		String gameMode;
		CameraPath.Pose pose;
		double poseT;
		long lastFrameEnd;
		double lastElapsed;
		// per-frame log (growable)
		int n;
		double[] ft = new double[1024];
		double[] gap = new double[1024];
		double[][] req = new double[1024][];
		double[][] cam = new double[1024][];
		final List<JsonObject> events = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();

		Session(String name, double duration, CameraPath path, Timeline timeline, boolean showHud, boolean holdForeman, String release,
			long holdEndMs, boolean writeLog, JsonObject spec) {
			this.name = name;
			this.duration = duration;
			this.path = path;
			this.timeline = timeline;
			this.showHud = showHud;
			this.holdForeman = holdForeman;
			this.release = release;
			this.holdEndNanos = holdEndMs * 1_000_000L;
			this.writeLog = writeLog;
			this.spec = spec;
		}

		void grow() {
			if (n == ft.length) {
				int m = n * 2;
				ft = Arrays.copyOf(ft, m);
				gap = Arrays.copyOf(gap, m);
				req = Arrays.copyOf(req, m);
				cam = Arrays.copyOf(cam, m);
			}
		}
	}

	/** True while a shot is playing (including its end hold). */
	public static boolean active() {
		return session != null;
	}

	/** Render thread: start a shot (validated by {@link PlayCommands}). Returns its result future. */
	static CompletableFuture<JsonObject> start(Minecraft mc, Session s) {
		if (session != null) {
			throw new DevException("a shot is already playing ('" + session.name + "'); dev.play.stop stops it");
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			throw new DevException("not in a world yet");
		}
		if (mc.isPaused()) {
			throw new DevException("the game is paused (" + (mc.gui.screen() == null ? "?" : mc.gui.screen().getClass().getSimpleName())
				+ " open); close it first");
		}
		s.gameMode = mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : null;
		if (!p.isSpectator()) {
			s.warnings.add("player is in " + s.gameMode + " mode, not spectator: the hand may show and the player may collide (dev.camera mode spectator first)");
		}
		s.savedHudHidden = mc.gui.hud.isHidden();
		if (mc.gui.hud.isHidden() == s.showHud) {
			mc.gui.hud.toggle();
		}
		if (s.holdForeman) {
			Foreman.state().setHold(true);
		}
		session = s;
		AgentCraft.LOGGER.info("dev.play '{}': {} s, {} timeline events", s.name, s.duration, s.timeline.events.size());
		return s.result;
	}

	static boolean stop(String reason) {
		Session s = session;
		if (s == null) {
			return false;
		}
		s.stopReason = reason;
		return true;
	}

	static JsonObject status() {
		Session s = session;
		JsonObject o = new JsonObject();
		o.addProperty("active", s != null);
		if (s != null) {
			o.addProperty("name", s.name);
			o.addProperty("started", s.t0 != 0);
			o.addProperty("elapsed", s.t0 == 0 ? 0 : (System.nanoTime() - s.t0) / 1e9);
			o.addProperty("duration", s.duration);
			o.addProperty("frames", s.n);
			o.addProperty("eventsRun", s.nextEvent);
			o.addProperty("events", s.timeline.events.size());
		}
		return o;
	}

	// ------------------------------------------------------------------ frame hooks (render thread)

	public static void beforeTick(Minecraft mc) {
		Session s = session;
		if (s == null) {
			return;
		}
		if (s.stopReason != null || mc.level == null || mc.player == null) {
			end(mc, s, s.stopReason != null ? s.stopReason : "left the world");
			return;
		}
		long now = System.nanoTime();
		if (s.t0 == 0) {
			// t = 0 is when the first frame of the shot will be presented
			s.t0 = presentTime(now);
		}
		runEvents(s, Math.max(0, (now - s.t0) / 1e9));
	}

	public static void beforeRender(Minecraft mc) {
		Session s = session;
		if (s == null || s.t0 == 0 || mc.player == null) {
			return;
		}
		long now = System.nanoTime();
		// sample the path at the time this frame will be shown, not when its work starts (see presentTime)
		double elapsed = Math.max(s.lastElapsed, (presentTime(now) - s.t0) / 1e9);
		s.lastElapsed = elapsed;
		double t = Math.min(elapsed, s.duration);
		if (elapsed >= s.duration && !s.pathDone) {
			s.pathDone = true;
			s.endAt = now + s.holdEndNanos;
		}
		CameraPath.Pose pose = s.path.at(t);
		s.pose = pose;
		s.poseT = t;
		applyPose(mc, pose, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
	}

	private static void applyPose(Minecraft mc, CameraPath.Pose pose, float pt) {
		LocalPlayer p = mc.player;
		Camera cam = mc.gameRenderer.mainCamera();
		CameraAccessor ca = (CameraAccessor) cam;
		// the camera adds its own smoothed eye height (lerped with the partial tick) to the feet position
		float eye = Mth.lerp(pt, ca.agentcraft$eyeHeightOld(), ca.agentcraft$eyeHeight());
		if (eye <= 0) {
			eye = p.getEyeHeight();
		}
		float yaw = (float) pose.yaw();
		float pitch = (float) Math.max(-90, Math.min(90, pose.pitch()));
		p.setDeltaMovement(Vec3.ZERO);
		// snapTo also sets the previous position/rotation: whatever the partial tick, the camera is exactly here
		p.snapTo(pose.x(), pose.y() - eye, pose.z(), yaw, pitch);
		p.setYHeadRot(yaw);
		p.yHeadRotO = yaw;
		p.setYBodyRot(yaw);
		p.yBodyRotO = yaw;
		if (mc.getCameraEntity() != p) {
			mc.setCameraEntity(p);
		}
		DevCamera.pinFov((float) pose.fov());
	}

	/** Frame pacing, tracked on every frame (playing or not): end of the last frame and the smoothed frame interval. */
	private static long lastEnd;
	private static double avgGap;

	/**
	 * When a frame started at {@code now} will be presented: the previous frame's end + the average
	 * frame interval, kept within one interval of {@code now} (so a hitch still advances by the real
	 * time that passed). Frame ends (right after present) are as regular as the display; frame starts
	 * jitter by a couple of ms (ticks, packets, GC), which would make the camera steps uneven.
	 */
	private static long presentTime(long now) {
		if (lastEnd == 0 || avgGap <= 0) {
			return now;
		}
		long gap = (long) avgGap;
		return Math.max(now - gap, Math.min(lastEnd + gap, now + gap));
	}

	public static void afterRender(Minecraft mc) {
		long now = System.nanoTime();
		long frameGap = lastEnd == 0 ? 0 : now - lastEnd;
		if (frameGap > 2_000_000L && frameGap < 100_000_000L) {
			// smoothed: a hitch moves it a little, a refresh-rate change within ~10 frames
			avgGap = avgGap == 0 ? frameGap : avgGap * 0.9 + frameGap * 0.1;
		}
		lastEnd = now;
		Session s = session;
		if (s == null || s.pose == null) {
			return;
		}
		s.grow();
		int i = s.n++;
		Camera cam = mc.gameRenderer.mainCamera();
		Vec3 cp = cam.position();
		CameraPath.Pose r = s.pose;
		s.ft[i] = s.poseT;
		s.gap[i] = s.lastFrameEnd == 0 ? 0 : (now - s.lastFrameEnd) / 1e6;
		s.req[i] = new double[] {r.x(), r.y(), r.z(), r.yaw(), r.pitch(), r.fov()};
		s.cam[i] = new double[] {cp.x, cp.y, cp.z, cam.yRot(), cam.xRot(), cam.getFov()};
		s.lastFrameEnd = now;
		if (s.pathDone && now >= s.endAt) {
			end(mc, s, null);
		}
	}

	// ------------------------------------------------------------------ timeline

	private static void runEvents(Session s, double elapsed) {
		List<Timeline.Event> evs = s.timeline.events;
		while (s.nextEvent < evs.size() && evs.get(s.nextEvent).t() <= elapsed) {
			Timeline.Event e = evs.get(s.nextEvent++);
			JsonObject log = new JsonObject();
			log.addProperty("t", e.t());
			log.addProperty("at", Math.round(elapsed * 1000) / 1000.0);
			log.addProperty("event", e.label());
			s.events.add(log);
			CompletableFuture<JsonObject> f;
			try {
				f = execute(e.body());
			} catch (Throwable t) {
				f = CompletableFuture.failedFuture(t);
			}
			if (f.isCompletedExceptionally()) {
				String err = errorOf(f);
				log.addProperty("ok", false);
				log.addProperty("error", err);
				// a choreography step that cannot run spoils the take: stop now
				s.stopReason = "timeline event '" + e.label() + "' at t=" + e.t() + " failed: " + err;
				return;
			}
			f.whenComplete((res, err) -> {
				synchronized (log) {
					log.addProperty("ok", err == null);
					if (err != null) {
						log.addProperty("error", DevBridge.describeError(err));
					}
				}
			});
		}
	}

	private static String errorOf(CompletableFuture<?> f) {
		try {
			f.join();
			return "?";
		} catch (Throwable t) {
			return DevBridge.describeError(t);
		}
	}

	private static CompletableFuture<JsonObject> execute(JsonObject body) {
		Fields f = Fields.of(body);
		for (String key : new String[] {"inject", "patch", "say"}) {
			if (f.has(key)) {
				JsonObject w = new JsonObject();
				w.add(key.equals("inject") ? "message" : key, body.get(key));
				return CompletableFuture.completedFuture(ForemanFeature.applyInjected(ForemanFeature.injectMessage(Fields.of(w))));
			}
		}
		String cmd = f.nonBlank("cmd");
		JsonObject req = body.deepCopy();
		req.remove("t");
		req.remove("cmd");
		req.remove("msPerChar");
		req.remove("jitter");
		req.remove("seed");
		if (cmd.equals("dev.command")) {
			// the event's own 'cmd' names the DevBridge command; the server command goes in 'command'
			JsonElement c = req.remove("command");
			if (c != null) {
				req.add("cmd", c);
			}
		}
		return DevBridge.invoke(cmd, req);
	}

	// ------------------------------------------------------------------ end

	private static void end(Minecraft mc, Session s, String error) {
		session = null;
		if (mc.gui.hud.isHidden() != s.savedHudHidden) {
			mc.gui.hud.toggle();
		}
		if (s.holdForeman && Foreman.state().isHeld()) {
			int held = Foreman.state().releaseHold(s.release.equals("replay"));
			if (s.release.equals("reconnect")) {
				Foreman.link().reconnectNow();
			}
			s.warnings.add("Foreman hold released (" + s.release + "; " + held + " live messages were held)");
		}
		if (error != null) {
			AgentCraft.LOGGER.warn("dev.play '{}' stopped: {}", s.name, error);
			s.result.completeExceptionally(new DevException("shot '" + s.name + "' stopped at t=" + round(s.poseT, 3) + ": " + error));
			return;
		}
		JsonObject summary = summary(mc, s);
		if (s.writeLog) {
			try {
				Path dir = ClientEnv.shotsDir().resolve("play");
				Files.createDirectories(dir);
				Path file = dir.resolve(s.name + ".frames.json");
				Files.writeString(file, DevBridge.GSON.toJson(framesJson(s)));
				summary.addProperty("frameLog", file.toString());
			} catch (Exception e) {
				s.warnings.add("could not write the frame log: " + e);
			}
		}
		s.result.complete(summary);
	}

	private static JsonObject summary(Minecraft mc, Session s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.name);
		o.addProperty("duration", s.duration);
		o.addProperty("frames", s.n);
		var w = mc.getWindow();
		o.addProperty("resolution", w.getWidth() + "x" + w.getHeight());
		o.addProperty("camera", s.path.kind);
		// frame pacing during the path (the end hold excluded)
		int k = 0;
		double[] gaps = new double[s.n];
		double first = -1;
		double last = 0;
		for (int i = 1; i < s.n; i++) {
			if (s.ft[i - 1] < s.duration) {
				gaps[k++] = s.gap[i];
			}
		}
		for (int i = 0; i < s.n; i++) {
			if (first < 0) {
				first = s.ft[i];
			}
			if (s.ft[i] <= s.duration) {
				last = s.ft[i];
			}
		}
		double[] g = Arrays.copyOf(gaps, k);
		Arrays.sort(g);
		JsonObject perf = new JsonObject();
		int pathFrames = k + 1;
		double span = last - Math.max(0, first);
		perf.addProperty("pathFrames", pathFrames);
		perf.addProperty("fps", span > 0 ? round(k / span, 1) : 0);
		perf.addProperty("frameMsMedian", k > 0 ? round(g[k / 2], 2) : 0);
		perf.addProperty("frameMsP99", k > 0 ? round(g[Math.min(k - 1, (int) Math.ceil(k * 0.99) - 1)], 2) : 0);
		perf.addProperty("frameMsMax", k > 0 ? round(g[k - 1], 2) : 0);
		int over20 = 0;
		int over33 = 0;
		for (double d : g) {
			if (d > 20) {
				over20++;
			}
			if (d > 33.4) {
				over33++;
			}
		}
		perf.addProperty("framesOver20ms", over20);
		perf.addProperty("framesOver33ms", over33);
		perf.addProperty("firstFrameT", round(Math.max(0, first), 4));
		// path time between consecutive frames (should match the frame interval: even camera steps)
		double stepMin = Double.MAX_VALUE;
		double stepMax = 0;
		for (int i = 1; i < s.n; i++) {
			if (s.ft[i] < s.duration) {
				double d = (s.ft[i] - s.ft[i - 1]) * 1000;
				stepMin = Math.min(stepMin, d);
				stepMax = Math.max(stepMax, d);
			}
		}
		perf.addProperty("pathStepMsMin", stepMax > 0 ? round(stepMin, 2) : 0);
		perf.addProperty("pathStepMsMax", round(stepMax, 2));
		o.add("perf", perf);
		double maxPos = 0;
		double maxRot = 0;
		double maxFov = 0;
		for (int i = 0; i < s.n; i++) {
			double[] r = s.req[i];
			double[] c = s.cam[i];
			maxPos = Math.max(maxPos, Math.max(Math.abs(r[0] - c[0]), Math.max(Math.abs(r[1] - c[1]), Math.abs(r[2] - c[2]))));
			maxRot = Math.max(maxRot, Math.max(Math.abs(Mth.wrapDegrees(r[3] - c[3])), Math.abs(r[4] - c[4])));
			maxFov = Math.max(maxFov, Math.abs(r[5] - c[5]));
		}
		JsonObject cam = new JsonObject();
		cam.addProperty("maxPosError", maxPos);
		cam.addProperty("maxRotError", maxRot);
		cam.addProperty("maxFovError", maxFov);
		o.add("cameraVsPath", cam);
		JsonArray evs = new JsonArray();
		for (JsonObject e : s.events) {
			synchronized (e) {
				JsonObject c = e.deepCopy();
				if (!c.has("ok")) {
					c.add("ok", JsonNull.INSTANCE); // still running (e.g. a server command)
				}
				evs.add(c);
			}
		}
		o.add("events", evs);
		JsonArray warn = new JsonArray();
		s.warnings.forEach(warn::add);
		o.add("warnings", warn);
		return o;
	}

	private static JsonObject framesJson(Session s) {
		JsonObject o = new JsonObject();
		o.addProperty("name", s.name);
		o.addProperty("duration", s.duration);
		o.add("spec", s.spec);
		JsonArray arr = new JsonArray();
		for (int i = 0; i < s.n; i++) {
			JsonObject f = new JsonObject();
			f.addProperty("i", i);
			f.addProperty("t", round(s.ft[i], 5));
			f.addProperty("gapMs", round(s.gap[i], 3));
			f.add("cam", arr6(s.cam[i]));
			f.add("req", arr6(s.req[i]));
			arr.add(f);
		}
		o.add("frames", arr);
		return o;
	}

	private static JsonArray arr6(double[] v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(round(d, 5));
		}
		return a;
	}

	static double round(double v, int digits) {
		double m = Math.pow(10, digits);
		return Math.round(v * m) / m;
	}

	static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
