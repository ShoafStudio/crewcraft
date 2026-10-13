package dev.agentcraft.client.world;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;

/** Common render state of every AgentCraft station BER (see {@link StationRenderer}). Extend it per feature. */
public class StationRenderState extends BlockEntityRenderState {
	/** The block entity's binding ("kit", "agent:kit", "shared", ...; empty = default). */
	public String binding = "";
	/** Horizontal facing of the block's front (NORTH for blocks without facing). */
	public Direction facing = Direction.NORTH;
	/** Connectable panels: true only on the bottom-left block, which draws the whole surface. */
	public boolean panelOrigin = true;
	/** Connectable panels: size of the whole connected surface in blocks (1x1 otherwise). */
	public int panelWidth = 1;
	public int panelHeight = 1;
	/** Seconds since the client started, partial-tick accurate (animations, pulses). */
	public float timeSeconds;
	/** Foreman state revision at extraction (cache derived layouts by it). */
	public long foremanRevision;
}
