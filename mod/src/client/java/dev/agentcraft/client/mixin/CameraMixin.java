package dev.agentcraft.client.mixin;

import dev.agentcraft.client.dev.DevCamera;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean isPanoramicMode;

	/** DevBridge FOV pin: render with exactly the requested FOV (no option, no dynamic FOV effects). */
	@Inject(method = "calculateFov", at = @At("HEAD"), cancellable = true)
	private void agentcraft$devFovPin(float partialTicks, CallbackInfoReturnable<Float> cir) {
		Float pin = DevCamera.fovPin();
		if (pin != null && !this.isPanoramicMode) {
			cir.setReturnValue(pin);
		}
	}
}
