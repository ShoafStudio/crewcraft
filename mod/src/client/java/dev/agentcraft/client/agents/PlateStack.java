package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;

/**
 * What stacks on top of an agent's nameplate, in plate space (FEATURES.md "plate space"):
 * the speech bubble right above the plate, and above that the pulsing clay "!" while the agent
 * needs you (waiting on a decision or on your merge). {@link #measure} runs at extract time and
 * tells {@link PlateLayout} how much room to reserve, so a bubble never covers another agent's
 * plate: the other plates are lifted clear of it like they are of the plate itself.
 */
final class PlateStack {
	/** The "!" marker: bar + dot, outlined, in plate px. */
	private static final float BAR_W = 4, BAR_H = 11, DOT = 4, GAP = 2, OUTLINE = 1.5f;
	/** Reserved height of the marker (incl. bob and margins). */
	static final float EXCLAIM_H = BAR_H + GAP + DOT + 2 * OUTLINE + 6;
	static final float EXCLAIM_W = BAR_W + 2 * OUTLINE + 4;

	private PlateStack() {
	}

	/** Fill {@link AgentRenderState#stackHeight} / {@link AgentRenderState#stackWidth} (render thread). */
	static void measure(AgentRenderState s, AgentLife life) {
		float h = 0;
		float w = 0;
		if (s.bubble > 0f) {
			SpeechBubble.Layout l = life.bubble.layout();
			if (l != null) {
				h += SpeechBubble.stackHeight(l);
				w = Math.max(w, l.width());
			}
		}
		if (s.exclaim > 0.05f) {
			h += EXCLAIM_H;
			w = Math.max(w, EXCLAIM_W);
		}
		s.stackHeight = h;
		s.stackWidth = w;
	}

	static void submit(AgentRenderState s, PoseStack ps, SubmitNodeCollector c, CameraRenderState camera) {
		AgentLife life = s.life;
		if (life == null || s.stackHeight <= 0f) {
			return;
		}
		int light = WorldUi.uiLight();
		ps.pushPose();
		Nameplate.plateSpace(s, ps, camera);
		float top = Nameplate.top(s);
		if (s.bubble > 0f) {
			SpeechBubble.Layout l = life.bubble.layout();
			if (l != null) {
				SpeechBubble.submit(ps, c, l, top, s.bubble, light);
				top -= SpeechBubble.stackHeight(l);
			}
		}
		if (s.exclaim > 0.05f) {
			exclaim(ps, c, top, s.exclaim, s.timeSeconds, light);
		}
		ps.popPose();
	}

	/** A clay "!" with an ink outline, bobbing and pulsing gently (1.2 s, ui-style pulse). */
	private static void exclaim(PoseStack ps, SubmitNodeCollector c, float bottom, float vis, float time, int light) {
		float period = UiStyle.metric("metrics.pulse_ms", 1200) / 1000f;
		float pulse = 0.5f + 0.5f * Mth.sin(time * Mth.TWO_PI / period);
		float bob = 1.5f * Mth.sin(time * Mth.TWO_PI / (period * 2));
		float scale = (0.4f + 0.6f * Mth.clamp(vis, 0f, 1f)) * (1f + 0.1f * pulse);
		float total = BAR_H + GAP + DOT;
		float cy = bottom - 3 - OUTLINE - total / 2f + bob;
		int ink = UiStyle.INK;
		int clay = UiStyle.status("waiting");
		int lit = AgentParticles.mix(clay, UiStyle.CREAM, 0.35f + 0.25f * pulse);
		ps.pushPose();
		ps.translate(0, cy, 0);
		ps.scale(scale, scale, 1f);
		float x0 = -BAR_W / 2f;
		float barY0 = -total / 2f;
		float dotY0 = barY0 + BAR_H + GAP;
		// outline (ink), then the clay body, then a light bevel on the left: each layer a hair nearer
		WorldUi.submitFill(ps, c, x0 - OUTLINE, barY0 - OUTLINE, x0 + BAR_W + OUTLINE, barY0 + BAR_H + OUTLINE, ink, light);
		WorldUi.submitFill(ps, c, x0 - OUTLINE, dotY0 - OUTLINE, x0 + DOT + OUTLINE, dotY0 + DOT + OUTLINE, ink, light);
		ps.translate(0, 0, 0.35f);
		WorldUi.submitFill(ps, c, x0, barY0, x0 + BAR_W, barY0 + BAR_H, clay, light);
		WorldUi.submitFill(ps, c, x0, dotY0, x0 + DOT, dotY0 + DOT, clay, light);
		ps.translate(0, 0, 0.35f);
		WorldUi.submitFill(ps, c, x0, barY0, x0 + 1.5f, barY0 + BAR_H - 1, lit, light);
		WorldUi.submitFill(ps, c, x0, dotY0, x0 + 1.5f, dotY0 + DOT - 1, lit, light);
		ps.popPose();
	}
}
