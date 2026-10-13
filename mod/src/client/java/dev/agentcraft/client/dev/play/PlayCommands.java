package dev.agentcraft.client.dev.play;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.dev.FrameScheduler;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Registers {@code dev.play}, {@code dev.play.pose|status|stop} and {@code dev.window}. Reference: mod/DEV.md "Shot playback". */
public final class PlayCommands {
	private PlayCommands() {
	}

	public static void register() {
		DevBridge.register("dev.play", req -> {
			double dur = 5;
			try {
				dur = Fields.of(req).optNum("duration", 5, 0.05, 600);
			} catch (RuntimeException ignored) {
				// reported by the handler
			}
			return (long) (dur * 1000) + 120_000;
		},
			"{name?, duration (s), camera:{type:keys, keys:[{t,x,y,z,yaw,pitch|lookAt,fov?,ease?}|{t,anchor}], easeEnds?:true, lookAt?, fov?}"
				+ " | {type:orbit, center:{x,z}, radius, y, from, to, lookAt?, fov?, ease?:inOut}, timeline?:[{t, inject|patch|say|cmd,...}],"
				+ " showHud?:false, holdEndMs?:300, foreman?:{hold?:true when there is a timeline, release?:reconnect|replay|drop}, log?:true}"
				+ " - play a shot in real time (for a screen recorder): the camera follows the path every frame, events run at their t."
				+ " Replies when done with fps/frame-time numbers and the camera-vs-path error",
			PlayCommands::play);
		DevBridge.register("dev.play.pose", 5_000,
			"{camera:{...as dev.play}, t?:0, duration?:5} -> {pose:{x,y,z,yaw,pitch,fov}} - where a camera path is at time t (eye position);"
				+ " tools put the camera there with dev.camera (teleport + chunk loading) before playing",
			(req, mc) -> {
				Fields f = Fields.of(req);
				double duration = f.optNum("duration", 5, 0.05, 600);
				CameraPath path = CameraPath.parse(f.obj("camera"), duration, mc.options.fov().get());
				CameraPath.Pose p = path.at(f.optNum("t", 0, 0, 1e6));
				JsonObject pose = new JsonObject();
				pose.addProperty("x", p.x());
				pose.addProperty("y", p.y());
				pose.addProperty("z", p.z());
				pose.addProperty("yaw", p.yaw());
				pose.addProperty("pitch", p.pitch());
				pose.addProperty("fov", p.fov());
				JsonObject o = new JsonObject();
				o.add("pose", pose);
				o.addProperty("start", path.start);
				o.addProperty("end", path.end);
				return CompletableFuture.completedFuture(o);
			});
		DevBridge.register("dev.play.status", 5_000, "{} -> {active, name, started, elapsed, duration, frames, eventsRun, events}",
			(req, mc) -> CompletableFuture.completedFuture(ShotPlayer.status()));
		DevBridge.register("dev.play.stop", 5_000, "{} - stop the playing shot (its dev.play replies ok:false)",
			(req, mc) -> {
				JsonObject o = new JsonObject();
				o.addProperty("stopped", ShotPlayer.stop("stopped (dev.play.stop)"));
				return CompletableFuture.completedFuture(o);
			});
		DevBridge.register("dev.window", 15_000,
			"{width, height} - resize the game window (windowed mode, e.g. 2560x1440 for a full-monitor capture); replies with the"
				+ " window and framebuffer size once a frame was rendered at the new size",
			(req, mc) -> {
				Fields f = Fields.of(req);
				int w = f.optInt("width", 1920, 320, 7680);
				int h = f.optInt("height", 1080, 240, 4320);
				return DevBridge.onClient(mc, () -> {
					mc.getWindow().setWindowed(w, h);
					return null;
				}).thenCompose(v -> FrameScheduler.when(() -> {
					Window win = mc.getWindow();
					return mc.gameRenderer.mainRenderTarget().width == win.getWidth() && mc.gameRenderer.mainRenderTarget().height == win.getHeight();
				}, 3, 3, 5_000, "the window to resize")).thenApply(v -> {
					Window win = mc.getWindow();
					JsonObject o = new JsonObject();
					o.addProperty("width", win.getScreenWidth());
					o.addProperty("height", win.getScreenHeight());
					o.addProperty("framebufferWidth", win.getWidth());
					o.addProperty("framebufferHeight", win.getHeight());
					o.addProperty("renderWidth", mc.gameRenderer.mainRenderTarget().width);
					o.addProperty("renderHeight", mc.gameRenderer.mainRenderTarget().height);
					return o;
				});
			});
	}

	static CompletableFuture<JsonObject> play(JsonObject req, Minecraft mc) {
		Fields f = Fields.of(req);
		String name = f.optStr("name", "shot");
		if (!name.matches("[A-Za-z0-9_-]{1,80}")) {
			throw new DevException("field 'name' must be 1-80 letters, digits, _ or - (got '" + name + "')");
		}
		double duration = f.num("duration", 0.05, 600);
		boolean showHud = f.optBool("showHud", false);
		long holdEndMs = f.optLong("holdEndMs", 300, 0, 60_000);
		boolean writeLog = f.optBool("log", true);
		CameraPath path = CameraPath.parse(f.obj("camera"), duration, mc.options.fov().get());
		Timeline timeline = Timeline.parse(req.get("timeline"), duration);
		Fields fm = f.optObj("foreman");
		boolean hold = fm != null ? fm.optBool("hold", true) : !timeline.events.isEmpty();
		String release = fm != null ? fm.optStr("release", "reconnect").toLowerCase(Locale.ROOT) : "reconnect";
		if (!Set.of("reconnect", "replay", "drop").contains(release)) {
			throw new DevException("field 'foreman.release' must be reconnect|replay|drop (got '" + release + "')");
		}
		JsonObject spec = req.deepCopy();
		spec.remove("id");
		spec.remove("type");
		ShotPlayer.Session s = new ShotPlayer.Session(name, duration, path, timeline, showHud, hold, release, holdEndMs, writeLog, spec);
		return DevBridge.onClient(mc, () -> ShotPlayer.start(mc, s)).thenCompose(fut -> fut);
	}
}
