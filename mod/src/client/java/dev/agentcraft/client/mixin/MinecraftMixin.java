package dev.agentcraft.client.mixin;

import dev.agentcraft.client.dev.FrameScheduler;
import dev.agentcraft.client.dev.play.ShotPlayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** Start of a loop iteration, before the client ticks: dev.play runs the timeline events that are due. */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void agentcraft$iterationStart(boolean advanceGameTime, CallbackInfo ci) {
		ShotPlayer.beforeTick((Minecraft) (Object) this);
	}

	/** Start of a rendered frame, after the client ticks: dev.play puts the camera on its path. */
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void agentcraft$frameStart(boolean advanceGameTime, CallbackInfo ci) {
		ShotPlayer.beforeRender((Minecraft) (Object) this);
	}

	/** End of every rendered frame (after present): dev.play frame log, then DevBridge frame waits + captures. */
	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void agentcraft$frameEnd(boolean advanceGameTime, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		ShotPlayer.afterRender(mc);
		FrameScheduler.onFrameEnd(mc);
	}
}
