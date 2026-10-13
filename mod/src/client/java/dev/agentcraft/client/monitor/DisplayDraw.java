package dev.agentcraft.client.monitor;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.ui.WorldUi;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.client.resources.metadata.gui.GuiSpriteScaling;
import net.minecraft.resources.Identifier;

/**
 * Drawing helpers for the in-world displays (monitors, task wall): flat colour rectangles and
 * gradients in "face pixel space" (see {@code StationRenderer.toFace}), opaque or translucent, and
 * opaque textured quads (agent portraits, kit sprites).
 *
 * <p>Opaque geometry uses a copy of the world-text pipeline without blending (like
 * {@code WorldUi.Layer.SOLID}): it is drawn in the solid pass with depth writes, so it never
 * ghosts, whatever the submit order. A whole screen's rectangles go into <b>one</b>
 * {@code submitCustomGeometry} call ({@link Rects}), so a busy wall costs a handful of nodes per
 * frame, not hundreds.
 *
 * <p>Depth: everything sits on one plane, so layers are separated by small explicit z steps towards
 * the viewer ({@link #Z_STEP} blocks; in face space -z faces the viewer).
 */
public final class DisplayDraw {
	/** One layer step towards the viewer, in blocks (face space z is not scaled). */
	public static final float Z_STEP = -0.0012f;
	private static final Identifier WHITE = AgentCraft.id("dynamic/displays_white");
	private static boolean whiteReady;
	private static RenderPipeline solidPipeline;
	private static final Map<Identifier, RenderType> SOLID = new HashMap<>();

	private DisplayDraw() {
	}

	private static void ensureWhite() {
		if (whiteReady) {
			return;
		}
		NativeImage img = new NativeImage(4, 4, false);
		img.fillRect(0, 0, 4, 4, 0xFFFFFFFF);
		Minecraft.getInstance().getTextureManager().register(WHITE, new DynamicTexture(() -> "agentcraft displays white", img));
		whiteReady = true;
	}

	/** Opaque (solid pass, depth-writing) quads sampling {@code texture} (texels under 10 % alpha are cut out). */
	public static RenderType solid(Identifier texture) {
		RenderType t = SOLID.get(texture);
		if (t == null) {
			if (solidPipeline == null) {
				solidPipeline = RenderPipeline.builder(RenderPipelines.WORLD_TEXT_SNIPPET)
					.withLocation(AgentCraft.id("pipeline/displays_solid"))
					.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
					.build();
			}
			t = RenderType.create("agentcraft_displays_solid",
				RenderSetup.builder(solidPipeline).withTexture("Sampler0", texture).useLightmap().createRenderSetup());
			SOLID.put(texture, t);
		}
		return t;
	}

	/** Opaque flat-colour quads. */
	public static RenderType fill() {
		ensureWhite();
		return solid(WHITE);
	}

	/** Translucent flat-colour quads (vanilla text pipeline, blended; drawn after all opaque geometry). */
	public static RenderType fillTranslucent() {
		ensureWhite();
		return RenderTypes.text(WHITE);
	}

	/** A quad with a vertical colour gradient (top, bottom) on the white texture. */
	public static void rect(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z, int top, int bottom, int light) {
		vc.addVertex(pose, x0, y0, z).setUv(0.5f, 0.5f).setColor(top).setLight(light);
		vc.addVertex(pose, x0, y1, z).setUv(0.5f, 0.5f).setColor(bottom).setLight(light);
		vc.addVertex(pose, x1, y1, z).setUv(0.5f, 0.5f).setColor(bottom).setLight(light);
		vc.addVertex(pose, x1, y0, z).setUv(0.5f, 0.5f).setColor(top).setLight(light);
	}

	/** A sprite of the GUI atlas drawn opaque, as a plain (unsliced) quad. */
	public static void submitSprite(PoseStack ps, SubmitNodeCollector c, Identifier sprite, float x, float y, float w, float h, float z, int argb,
		int light) {
		WorldUi.submitSprite(ps, c, WorldUi.Layer.SOLID, sprite, x, y, w, h, z, argb, light);
	}

	/** A whole standalone texture (e.g. an 8x8 portrait) drawn opaque. */
	public static void submitTexture(PoseStack ps, SubmitNodeCollector c, Identifier texture, float x, float y, float w, float h, float z, int argb,
		int light) {
		c.order(0).submitCustomGeometry(ps, solid(texture), (pose, vc) -> {
			vc.addVertex(pose, x, y, z).setUv(0, 0).setColor(argb).setLight(light);
			vc.addVertex(pose, x, y + h, z).setUv(0, 1).setColor(argb).setLight(light);
			vc.addVertex(pose, x + w, y + h, z).setUv(1, 1).setColor(argb).setLight(light);
			vc.addVertex(pose, x + w, y, z).setUv(1, 0).setColor(argb).setLight(light);
		});
	}

	/** Nine-slice a GUI-atlas kit sprite (border from its gui.scaling metadata) at depth {@code z}. */
	public static void nineSlice(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite s, float x, float y, float w, float h, float z, int argb,
		int light) {
		nineSlice(pose, vc, s, x, y, w, h, z, argb, light, 0);
	}

	/**
	 * Nine-slice with the sprite's last {@code trimBottom} texel rows left out (kit cards carry a
	 * translucent 1-texel drop shadow there, which an opaque layer turns into a hard ink line; draw a
	 * soft shadow separately instead). {@code h} is the height without the trimmed rows.
	 */
	public static void nineSlice(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite s, float x, float y, float w, float h, float z, int argb,
		int light, int trimBottom) {
		GuiSpriteScaling scaling = s.contents().<GuiMetadataSection>getAdditionalMetadata(GuiMetadataSection.TYPE)
			.orElse(GuiMetadataSection.DEFAULT).scaling();
		if (!(scaling instanceof GuiSpriteScaling.NineSlice ns)) {
			WorldUi.quad(pose, vc, x, y, x + w, y + h, z, s.getU0(), s.getV0(), s.getU1(), s.getV1(), argb, light);
			return;
		}
		float sw = ns.width();
		float sh = ns.height();
		float l = Math.min(ns.border().left(), w / 2);
		float r = Math.min(ns.border().right(), w / 2);
		float t = Math.min(ns.border().top(), h / 2);
		float b = Math.min(ns.border().bottom() - trimBottom, h / 2);
		float x1 = x + l, x2 = x + w - r, x3 = x + w;
		float y1 = y + t, y2 = y + h - b, y3 = y + h;
		float u1 = ns.border().left() / sw, u2 = 1 - ns.border().right() / sw;
		float v1 = ns.border().top() / sh, v2 = 1 - ns.border().bottom() / sh;
		float v3 = 1 - trimBottom / sh;
		slice(pose, vc, s, x, y, x1, y1, 0, 0, u1, v1, z, argb, light);
		slice(pose, vc, s, x1, y, x2, y1, u1, 0, u2, v1, z, argb, light);
		slice(pose, vc, s, x2, y, x3, y1, u2, 0, 1, v1, z, argb, light);
		slice(pose, vc, s, x, y1, x1, y2, 0, v1, u1, v2, z, argb, light);
		slice(pose, vc, s, x1, y1, x2, y2, u1, v1, u2, v2, z, argb, light);
		slice(pose, vc, s, x2, y1, x3, y2, u2, v1, 1, v2, z, argb, light);
		slice(pose, vc, s, x, y2, x1, y3, 0, v2, u1, v3, z, argb, light);
		slice(pose, vc, s, x1, y2, x2, y3, u1, v2, u2, v3, z, argb, light);
		slice(pose, vc, s, x2, y2, x3, y3, u2, v2, 1, v3, z, argb, light);
	}

	private static void slice(PoseStack.Pose pose, VertexConsumer vc, TextureAtlasSprite s, float x0, float y0, float x1, float y1, float u0, float v0,
		float u1, float v1, float z, int argb, int light) {
		if (x1 - x0 <= 0 || y1 - y0 <= 0) {
			return;
		}
		WorldUi.quad(pose, vc, x0, y0, x1, y1, z, s.getU(u0), s.getV(v0), s.getU(u1), s.getV(v1), argb, light);
	}

	/**
	 * A reusable list of flat rectangles (x0, y0, x1, y1, z, top colour, bottom colour, light) drawn
	 * in one custom-geometry node. Build it once per layout, draw it every frame.
	 */
	public static final class Rects {
		private float[] f = new float[8 * 16];
		private int[] c = new int[3 * 16];
		private int n;

		public Rects clear() {
			n = 0;
			return this;
		}

		public int size() {
			return n;
		}

		public Rects add(float x0, float y0, float x1, float y1, float z, int argb, int light) {
			return add(x0, y0, x1, y1, z, argb, argb, light);
		}

		public Rects add(float x0, float y0, float x1, float y1, float z, int top, int bottom, int light) {
			if (x1 <= x0 || y1 <= y0) {
				return this;
			}
			if (n * 8 + 8 > f.length) {
				f = java.util.Arrays.copyOf(f, f.length * 2);
				c = java.util.Arrays.copyOf(c, c.length * 2);
			}
			int i = n * 8;
			f[i] = x0;
			f[i + 1] = y0;
			f[i + 2] = x1;
			f[i + 3] = y1;
			f[i + 4] = z;
			int j = n * 3;
			c[j] = top;
			c[j + 1] = bottom;
			c[j + 2] = light;
			n++;
			return this;
		}

		/** Emit all rectangles (light override: {@code light >= 0} replaces each rect's own light). */
		public void emit(PoseStack.Pose pose, VertexConsumer vc, int alphaMul, int light) {
			for (int k = 0; k < n; k++) {
				int i = k * 8;
				int j = k * 3;
				int top = alphaMul >= 255 ? c[j] : mulAlpha(c[j], alphaMul);
				int bottom = alphaMul >= 255 ? c[j + 1] : mulAlpha(c[j + 1], alphaMul);
				rect(pose, vc, f[i], f[i + 1], f[i + 2], f[i + 3], f[i + 4], top, bottom, light >= 0 ? light : c[j + 2]);
			}
		}

		public void submit(PoseStack ps, SubmitNodeCollector collector) {
			if (n == 0) {
				return;
			}
			collector.order(0).submitCustomGeometry(ps, fill(), (pose, vc) -> emit(pose, vc, 255, -1));
		}
	}

	private static final Map<String, Identifier> DOTS = new HashMap<>();

	/** {@code Kit.dot(family, halo)}, cached: renderers ask for it every frame. */
	public static Identifier dot(String family, boolean halo) {
		return DOTS.computeIfAbsent(halo ? family + "+" : family, k -> dev.agentcraft.client.ui.Kit.dot(family, halo));
	}

	public static int mulAlpha(int argb, int alpha) {
		int a = ((argb >>> 24) * alpha) / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Linear blend of two opaque colours ({@code t} 0 = a, 1 = b). */
	public static int mix(int a, int b, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int aa = (a >>> 24), ba = (b >>> 24);
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
			| Math.round(ab + (bb - ab) * t);
	}
}
