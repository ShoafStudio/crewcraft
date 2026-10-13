package dev.agentcraft.client.agents;

import net.minecraft.client.model.AnimationUtils;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;

/**
 * The vanilla player model plus AgentCraft postures: after the vanilla setup (walk swing, head
 * look, arm bob) the pose channels of {@link AgentLife} replace the arm and leg rotations (blended
 * by their weights, so walking keeps the vanilla swing) and lean the upper body forward around the
 * hips (head, arms and torso pivot together, the legs stay planted). The skin overlay parts are
 * children of the base parts, so sleeves and trousers follow.
 */
public final class AgentModel extends PlayerModel {
	public AgentModel(ModelPart root, boolean slim) {
		super(root, slim);
	}

	@Override
	public void setupAnim(AvatarRenderState state) {
		super.setupAnim(state);
		if (!(state instanceof AgentRenderState s) || s.posePose == null) {
			return;
		}
		float[] p = s.posePose;
		float aw = Mth.clamp(p[AgentLife.P_ARMW], 0f, 1f);
		if (aw > 0.001f) {
			rightArm.xRot = Mth.lerp(aw, rightArm.xRot, p[AgentLife.P_RAX]);
			rightArm.yRot = Mth.lerp(aw, rightArm.yRot, p[AgentLife.P_RAY]);
			rightArm.zRot = Mth.lerp(aw, rightArm.zRot, p[AgentLife.P_RAZ]);
			leftArm.xRot = Mth.lerp(aw, leftArm.xRot, p[AgentLife.P_LAX]);
			leftArm.yRot = Mth.lerp(aw, leftArm.yRot, p[AgentLife.P_LAY]);
			leftArm.zRot = Mth.lerp(aw, leftArm.zRot, p[AgentLife.P_LAZ]);
			// keep vanilla's gentle breathing sway on top of the posture
			AnimationUtils.bobModelPart(rightArm, state.ageInTicks, 1.0F);
			AnimationUtils.bobModelPart(leftArm, state.ageInTicks, -1.0F);
		}
		float lw = Mth.clamp(p[AgentLife.P_LEGW], 0f, 1f);
		if (lw > 0.001f) {
			rightLeg.xRot = Mth.lerp(lw, rightLeg.xRot, p[AgentLife.P_RLX]);
			rightLeg.yRot = Mth.lerp(lw, rightLeg.yRot, p[AgentLife.P_RLY]);
			rightLeg.zRot = Mth.lerp(lw, rightLeg.zRot, p[AgentLife.P_RLZ]);
			leftLeg.xRot = Mth.lerp(lw, leftLeg.xRot, p[AgentLife.P_LLX]);
			leftLeg.yRot = Mth.lerp(lw, leftLeg.yRot, p[AgentLife.P_LLY]);
			leftLeg.zRot = Mth.lerp(lw, leftLeg.zRot, p[AgentLife.P_LLZ]);
		}
		float lean = p[AgentLife.P_LEAN];
		if (Math.abs(lean) > 0.001f) {
			lean(lean);
		}
	}

	/**
	 * Rotate the upper body by {@code a} radians around the hip point (0, 12, 0) (model px, -z is
	 * forward): the torso tilts forward and the neck and shoulders move with it.
	 */
	private void lean(float a) {
		float c = Mth.cos(a);
		float sn = Mth.sin(a);
		// a pivot at (y, z) relative to the hips maps to (y cos a - z sin a, y sin a + z cos a); the
		// neck sits 12 px above the hips (y = -12), the shoulders 10 px (y = -10)
		body.xRot += a;
		body.y += 12 - 12 * c;
		body.z += -12 * sn;
		head.y += 12 - 12 * c;
		head.z += -12 * sn;
		rightArm.y += 10 - 10 * c;
		rightArm.z += -10 * sn;
		rightArm.xRot += a * 0.6f;
		leftArm.y += 10 - 10 * c;
		leftArm.z += -10 * sn;
		leftArm.xRot += a * 0.6f;
	}
}
