package dev.agentcraft.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Base of every AgentCraft block entity. It carries a {@code binding}: a short string that tells the
 * client-side renderer which part of the Foreman state this block shows, for example
 * {@code "kit"} on a monitor, {@code "agent:kit"} / {@code "ci:demo-app"} / {@code "goal"} on a
 * status lamp, {@code "shared"} on a memory archive. The HQ builder sets it; it is saved with the
 * world and synced to clients. An empty binding means "the default for this station".
 *
 * <p>Feature specialists add their own fields to the subclass of their station (monitor scroll,
 * etc.) and must call {@link #changed()} after changing anything the client renders.
 */
public abstract class StationBlockEntity extends BlockEntity {
	private String binding = "";

	protected StationBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public String binding() {
		return binding;
	}

	/** Sets the binding (server side), saves and syncs it to clients. */
	public void setBinding(String binding) {
		String b = binding == null ? "" : binding;
		if (!b.equals(this.binding)) {
			this.binding = b;
			changed();
		}
	}

	/** Mark dirty and push the new data to clients. */
	protected void changed() {
		setChanged();
		Level level = getLevel();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.binding = input.getStringOr("binding", "");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!binding.isEmpty()) {
			output.putString("binding", binding);
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveCustomOnly(registries);
	}
}
