package dev.agentcraft.client.world;

import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.Anchors;
import java.util.concurrent.CompletableFuture;

/**
 * DevBridge access to the anchor layout, so QA scenes reference positions by name:
 * {@code dev.anchors {prefix?}} -> {@code {layout, revision, bounds, anchors:{name:{x,y,z,yaw,pitch}}}}.
 * {@code dev.camera {anchor:"cam_desks_front"}} (handled in DevCommands) puts the camera on an anchor.
 */
public final class AnchorsDev {
	private AnchorsDev() {
	}

	public static void init() {
		DevBridge.register("dev.anchors", 5_000,
			"{prefix?} -> {layout, revision, bounds, anchors:{name:{x,y,z,yaw,pitch}}}. cam_* = eye position + view; others = feet/surface"
				+ " (see AnchorNames). Use with dev.camera {anchor}",
			(req, mc) -> {
				String prefix = Fields.of(req).optStr("prefix", "");
				Anchors.Layout layout = Anchors.current();
				JsonObject o = new JsonObject();
				o.addProperty("layout", layout.name());
				o.addProperty("revision", layout.revision());
				JsonObject full = Anchors.toJson(layout);
				o.add("bounds", full.get("bounds"));
				JsonObject anchors = new JsonObject();
				for (Anchor a : layout.anchors().values()) {
					if (a.name().startsWith(prefix)) {
						anchors.add(a.name(), Anchors.anchorJson(a));
					}
				}
				o.add("anchors", anchors);
				o.addProperty("count", anchors.size());
				return CompletableFuture.completedFuture(o);
			});
	}
}
