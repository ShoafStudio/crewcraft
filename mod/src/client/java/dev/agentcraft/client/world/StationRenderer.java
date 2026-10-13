package dev.agentcraft.client.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.client.foreman.Foreman;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Base of the station block-entity renderers that Phase 3 features fill in. It extracts the common
 * {@link StationRenderState} (binding, facing, connected-panel origin/extent, time, Foreman
 * revision); subclasses add their own fields in {@link #extractStation} and draw in
 * {@link #submit}. Read the Foreman model in extract (client thread), never in submit.
 *
 * <p>{@link #toFace} sets up "screen pixel space" on a block's front surface, so text and kit sprites
 * can be drawn with GUI coordinates (x right, y down, origin = top-left as seen by a viewer).
 */
public abstract class StationRenderer<T extends StationBlockEntity, S extends StationRenderState> implements BlockEntityRenderer<T, S> {
	@Override
	public final void extractRenderState(T be, S state, float partialTicks, Vec3 cameraPosition,
		ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderState.extractBase(be, state, breakProgress);
		BlockState bs = be.getBlockState();
		state.binding = be.binding();
		state.facing = bs.hasProperty(HorizontalDirectionalBlock.FACING) ? bs.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
		if (bs.getBlock() instanceof PanelBlock && be.getLevel() != null) {
			BlockPos origin = PanelBlock.origin(be.getLevel(), be.getBlockPos(), bs);
			state.panelOrigin = origin.equals(be.getBlockPos());
			if (state.panelOrigin) {
				int[] wh = PanelBlock.extent(be.getLevel(), origin, bs);
				state.panelWidth = wh[0];
				state.panelHeight = wh[1];
			}
		}
		state.timeSeconds = be.getLevel() == null ? 0 : (be.getLevel().getGameTime() % 1_000_000L + partialTicks) / 20f;
		state.foremanRevision = Foreman.state() == null ? 0 : Foreman.state().revision();
		extractStation(be, state, partialTicks);
	}

	/** Fill your feature's fields (client thread; the Foreman model may be read here). */
	protected void extractStation(T be, S state, float partialTicks) {
	}

	@Override
	public int getViewDistance() {
		return 48;
	}

	/**
	 * Move {@code poseStack} (at the block origin, as given to {@code submit}) onto the front surface of
	 * the block, {@code depth} blocks behind the front plane of the north-facing model (monitor screen:
	 * 12/16, task board linen: 14/16; minus a hair to sit in front of it), in units of
	 * {@code 1/pixelsPerBlock} block, x right / y down as seen by a viewer, origin at the top-left of
	 * the block's face. For a connected panel drawn from its origin (bottom-left block), shift up by
	 * {@code (panelHeight - 1) * pixelsPerBlock} to reach the panel's top-left.
	 */
	public static void toFace(PoseStack poseStack, Direction facing, float depth, float pixelsPerBlock) {
		poseStack.translate(0.5f, 0.5f, 0.5f);
		poseStack.rotateDegrees(Axis.YP, -modelRotation(facing));
		poseStack.translate(-0.5f, -0.5f, -0.5f);
		// north-facing model: the viewer stands north (-Z) looking south, so the viewer's right is -X
		poseStack.translate(1f, 1f, depth);
		poseStack.scale(-1f / pixelsPerBlock, -1f / pixelsPerBlock, 1f);
	}

	/** Blockstate y rotation of the north-facing model for {@code facing} (N 0, E 90, S 180, W 270). */
	public static float modelRotation(Direction facing) {
		return switch (facing) {
			case EAST -> 90f;
			case SOUTH -> 180f;
			case WEST -> 270f;
			default -> 0f;
		};
	}
}
