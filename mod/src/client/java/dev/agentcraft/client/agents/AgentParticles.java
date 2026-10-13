package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.agentcraft.client.ui.UiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Small state particles around one agent, drawn with vanilla particle sprites tinted in the Warm
 * Studio status palette (so they feel native to Minecraft but carry our meaning):
 * <ul>
 *   <li>thinking: a slow twinkle of brass four-point stars around the head (vanilla {@code glow});</li>
 *   <li>working: small teal motes rising from the hands / keyboard / book ({@code glitter});</li>
 *   <li>error: a red puff when the agent hits an error ({@code generic} smoke), then a small puff
 *       every few seconds while it stays in error;</li>
 *   <li>done: a burst of sage, cream, brass and clay confetti flakes when a task completes.</li>
 * </ul>
 * Tasteful by construction: at most one new thinking/working particle every ~0.4 s, short lives,
 * small sizes. A fixed pool per agent (no allocation per tick or frame), simulated per tick and
 * interpolated per frame, submitted as one batch of camera-facing quads at the agent's origin.
 */
public final class AgentParticles {
	private static final int CAP = 48;
	static final int SPARK = 0, MOTE = 1, PUFF = 2, CONFETTI = 3;

	private final double[] x = new double[CAP], y = new double[CAP], z = new double[CAP];
	private final double[] ox = new double[CAP], oy = new double[CAP], oz = new double[CAP];
	private final float[] vx = new float[CAP], vy = new float[CAP], vz = new float[CAP];
	private final float[] size = new float[CAP], rot = new float[CAP], orot = new float[CAP], spin = new float[CAP];
	private final float[] gravity = new float[CAP], drag = new float[CAP];
	private final int[] age = new int[CAP], life = new int[CAP], kind = new int[CAP], color = new int[CAP];
	private int live;
	private int cursor;

	// frame scratch (render thread only)
	private static final Vector3f RIGHT = new Vector3f();
	private static final Vector3f UP = new Vector3f();
	private static TextureAtlas atlas;
	private static TextureAtlasSprite glow, glitterBig, glitterSmall, square;
	private static final TextureAtlasSprite[] SMOKE = new TextureAtlasSprite[8];
	private static RenderType type;

	public int live() {
		return live;
	}

	private int alloc() {
		for (int n = 0; n < CAP; n++) {
			int i = (cursor + n) % CAP;
			if (life[i] == 0) {
				cursor = (i + 1) % CAP;
				live++;
				return i;
			}
		}
		// pool full: recycle the oldest slot after the cursor
		int i = cursor;
		cursor = (cursor + 1) % CAP;
		return i;
	}

	private int spawn(int k, double px, double py, double pz, float vx0, float vy0, float vz0, int lifeTicks, float sz, int argb) {
		int i = alloc();
		kind[i] = k;
		x[i] = ox[i] = px;
		y[i] = oy[i] = py;
		z[i] = oz[i] = pz;
		vx[i] = vx0;
		vy[i] = vy0;
		vz[i] = vz0;
		age[i] = 0;
		life[i] = Math.max(1, lifeTicks);
		size[i] = sz;
		rot[i] = orot[i] = 0;
		spin[i] = 0;
		gravity[i] = 0;
		drag[i] = 0.96f;
		color[i] = argb;
		return i;
	}

	// ---------------------------------------------------------------- emitters

	/** Thinking: one brass twinkle somewhere around the head. */
	void sparkle(ClientAgentEntity e, double height, AgentLife r) {
		double a = r.rand01() * Math.PI * 2;
		double rad = 0.34 + r.rand01() * 0.2;
		double px = e.getX() + Math.cos(a) * rad;
		double pz = e.getZ() + Math.sin(a) * rad;
		double py = e.getY() + height + r.randSigned() * 0.2;
		float tang = 0.004f;
		int i = spawn(SPARK, px, py, pz, (float) -Math.sin(a) * tang, 0.006f + r.rand01() * 0.004f, (float) Math.cos(a) * tang, 24 + r.rand(10),
			0.12f + r.rand01() * 0.05f, mix(UiStyle.status("thinking"), UiStyle.CREAM, 0.25f));
		drag[i] = 0.99f;
	}

	/** Working: a small teal mote rising from the hands ({@code at} is relative to the feet). */
	void mote(ClientAgentEntity e, net.minecraft.world.phys.Vec3 at, AgentLife r) {
		double rad = Math.toRadians(e.yBodyRot);
		double sx = Math.cos(rad);
		double sz = Math.sin(rad);
		double side = r.randSigned() * 0.22;
		double px = e.getX() + at.x + sx * side;
		double pz = e.getZ() + at.z + sz * side;
		double py = e.getY() + at.y + r.rand01() * 0.08;
		float fx = (float) (-Math.sin(rad) * 0.003);
		float fz = (float) (Math.cos(rad) * 0.003);
		int i = spawn(MOTE, px, py, pz, fx, 0.013f + r.rand01() * 0.007f, fz, 18 + r.rand(10), 0.065f + r.rand01() * 0.025f,
			mix(UiStyle.status("working"), UiStyle.CREAM, 0.2f));
		drag[i] = 0.97f;
	}

	/**
	 * Error: red "steam" puffing out of both ears ({@code n} wisps, alternating sides), the cartoon
	 * "fuming" read. {@code height} is the ear height above the feet. The wisps start just outside
	 * the head (half width 0.23) and drift sideways and a little up, so they read from the front,
	 * the back and the side, and stay below the nameplate (they used to start inside the head).
	 */
	void puff(ClientAgentEntity e, double height, int n, AgentLife r) {
		int red = UiStyle.status("error");
		double yaw = Math.toRadians(e.getYHeadRot());
		// the head's right-hand side axis (yaw 0 faces +Z, so its right is -X)
		double sx = -Math.cos(yaw);
		double sz = -Math.sin(yaw);
		for (int k = 0; k < n; k++) {
			double side = (k & 1) == 0 ? 1 : -1;
			double off = 0.28 + r.rand01() * 0.05;
			double px = e.getX() + sx * side * off;
			double pz = e.getZ() + sz * side * off;
			double py = e.getY() + height + r.randSigned() * 0.05;
			float out = 0.022f + r.rand01() * 0.02f;
			float fwd = r.randSigned() * 0.006f;
			int c = k % 3 == 0 ? mix(red, UiStyle.INK, 0.25f) : k % 3 == 1 ? red : mix(red, UiStyle.CLAY, 0.35f);
			int i = spawn(PUFF, px, py, pz, (float) (sx * side * out - sz * fwd), 0.008f + r.rand01() * 0.01f, (float) (sz * side * out + sx * fwd),
				14 + r.rand(9), 0.15f + r.rand01() * 0.08f, c);
			drag[i] = 0.86f;
			spin[i] = r.randSigned() * 0.08f;
		}
	}

	/** Done: confetti flakes in the palette, thrown up and fluttering down. */
	void confetti(ClientAgentEntity e, double height, AgentLife r) {
		int[] colors = {UiStyle.SAGE, UiStyle.CREAM, UiStyle.BRASS, UiStyle.CLAY, UiStyle.status("done"), UiStyle.TEAL};
		for (int k = 0; k < 18; k++) {
			double a = r.rand01() * Math.PI * 2;
			// burst outwards around the shoulders, staying below the nameplate (no flakes over its text)
			float out = 0.05f + r.rand01() * 0.045f;
			double px = e.getX() + Math.cos(a) * 0.15;
			double pz = e.getZ() + Math.sin(a) * 0.15;
			double py = e.getY() + height;
			int i = spawn(CONFETTI, px, py, pz, (float) Math.cos(a) * out, 0.06f + r.rand01() * 0.045f, (float) Math.sin(a) * out, 34 + r.rand(18),
				0.075f + r.rand01() * 0.03f, colors[k % colors.length]);
			gravity[i] = 0.0085f;
			drag[i] = 0.935f;
			spin[i] = r.randSigned() * 0.35f;
			rot[i] = orot[i] = r.rand01() * 6.28f;
		}
	}

	// ---------------------------------------------------------------- simulation

	void tick() {
		if (live == 0) {
			return;
		}
		int n = 0;
		for (int i = 0; i < CAP; i++) {
			if (life[i] == 0) {
				continue;
			}
			ox[i] = x[i];
			oy[i] = y[i];
			oz[i] = z[i];
			orot[i] = rot[i];
			if (++age[i] >= life[i]) {
				life[i] = 0;
				continue;
			}
			n++;
			vy[i] -= gravity[i];
			vx[i] *= drag[i];
			vy[i] *= drag[i];
			vz[i] *= drag[i];
			if (kind[i] == CONFETTI) {
				// flutter: falling flakes drift sideways and slow down like paper
				float flutter = Mth.sin((age[i] + i * 7) * 0.45f) * 0.004f;
				vx[i] += flutter;
				vz[i] -= flutter * 0.6f;
				if (vy[i] < -0.025f) {
					vy[i] = -0.025f;
				}
			}
			x[i] += vx[i];
			y[i] += vy[i];
			z[i] += vz[i];
			rot[i] += spin[i];
		}
		live = n;
	}

	// ---------------------------------------------------------------- drawing

	private static boolean sprites() {
		TextureAtlas a = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
		if (a != atlas || glow == null) {
			atlas = a;
			glow = a.getSprite(Identifier.withDefaultNamespace("glow"));
			glitterBig = a.getSprite(Identifier.withDefaultNamespace("glitter_1"));
			glitterSmall = a.getSprite(Identifier.withDefaultNamespace("glitter_0"));
			square = a.getSprite(Identifier.withDefaultNamespace("glitter_2"));
			for (int i = 0; i < 8; i++) {
				SMOKE[i] = a.getSprite(Identifier.withDefaultNamespace("generic_" + i));
			}
			type = RenderTypes.text(a.location());
		}
		return glow != null;
	}

	/**
	 * Submit every live particle (pose stack at the agent's render origin, which is at
	 * {@code (originX, originY, originZ)} in the world).
	 */
	void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, double originX, double originY, double originZ, float pt,
		int light) {
		if (live == 0 || !sprites()) {
			return;
		}
		camera.orientation.transform(RIGHT.set(1, 0, 0));
		camera.orientation.transform(UP.set(0, 1, 0));
		final float rx = RIGHT.x, ry = RIGHT.y, rz = RIGHT.z, ux = UP.x, uy = UP.y, uz = UP.z;
		collector.order(1).submitCustomGeometry(poseStack, type, (pose, vc) -> {
			for (int i = 0; i < CAP; i++) {
				if (life[i] == 0) {
					continue;
				}
				float t = Math.min(1f, (age[i] + pt) / life[i]);
				float px = (float) (Mth.lerp(pt, ox[i], x[i]) - originX);
				float py = (float) (Mth.lerp(pt, oy[i], y[i]) - originY);
				float pz = (float) (Mth.lerp(pt, oz[i], z[i]) - originZ);
				float s;
				float alpha;
				TextureAtlasSprite sp;
				int lt = LightCoordsUtil.FULL_BRIGHT;
				switch (kind[i]) {
					case SPARK -> {
						// twinkle: grow, shine, shrink
						float tw = Mth.sin(t * Mth.PI);
						s = size[i] * (0.35f + 0.65f * tw);
						alpha = Math.min(1f, tw * 1.6f);
						sp = glow;
					}
					case MOTE -> {
						s = size[i] * (1f - 0.45f * t);
						alpha = t < 0.15f ? t / 0.15f : 1f - (t - 0.15f) / 0.85f * 0.9f;
						sp = t < 0.55f ? glitterBig : glitterSmall;
					}
					case PUFF -> {
						s = size[i] * (0.7f + 0.6f * t);
						alpha = 0.85f * (1f - t * t);
						sp = SMOKE[Math.min(7, 3 + (int) (t * 5))];
						lt = light;
					}
					default -> {
						s = size[i];
						alpha = t > 0.75f ? (1f - t) / 0.25f : 1f;
						sp = square;
						lt = light;
					}
				}
				if (s <= 0.002f || alpha <= 0.02f) {
					continue;
				}
				int argb = UiStyle.withAlpha(color[i], (int) (alpha * 255));
				float r0 = Mth.lerp(pt, orot[i], rot[i]);
				float c = Mth.cos(r0) * s;
				float sn = Mth.sin(r0) * s;
				// rotated billboard corners: a = right*c + up*sn, b = -right*sn + up*c
				float ax = rx * c + ux * sn, ay = ry * c + uy * sn, az = rz * c + uz * sn;
				float bx = -rx * sn + ux * c, by = -ry * sn + uy * c, bz = -rz * sn + uz * c;
				if (kind[i] == CONFETTI) {
					// paper flakes are rectangles that flip as they turn
					float flip = 0.45f + 0.55f * Math.abs(Mth.sin(r0 * 1.7f));
					bx *= flip;
					by *= flip;
					bz *= flip;
				}
				quad(vc, pose, px, py, pz, ax, ay, az, bx, by, bz, sp, argb, lt);
			}
		});
	}

	private static void quad(VertexConsumer vc, PoseStack.Pose pose, float px, float py, float pz, float ax, float ay, float az, float bx, float by,
		float bz, TextureAtlasSprite sp, int argb, int light) {
		float u0 = sp.getU0(), u1 = sp.getU1(), v0 = sp.getV0(), v1 = sp.getV1();
		vc.addVertex(pose, px - ax + bx, py - ay + by, pz - az + bz).setUv(u0, v0).setColor(argb).setLight(light);
		vc.addVertex(pose, px - ax - bx, py - ay - by, pz - az - bz).setUv(u0, v1).setColor(argb).setLight(light);
		vc.addVertex(pose, px + ax - bx, py + ay - by, pz + az - bz).setUv(u1, v1).setColor(argb).setLight(light);
		vc.addVertex(pose, px + ax + bx, py + ay + by, pz + az + bz).setUv(u1, v0).setColor(argb).setLight(light);
	}

	/** {@code a} blended towards {@code b} by {@code t} (opaque ARGB). */
	static int mix(int a, int b, float t) {
		int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
		int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
		return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
	}
}
