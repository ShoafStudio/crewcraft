package dev.agentcraft.client.dev;

/**
 * Dev camera state that rendering code reads (see {@code CameraMixin}).
 *
 * <p>The FOV pin: while set, the world is rendered with exactly this vertical FOV, ignoring the
 * player's FOV option and vanilla's dynamic FOV effects (flying/sprint widening, which would
 * otherwise turn a requested 70 into about 77 in spectator). The player's options are never
 * touched, so nothing leaks into options.txt. Every {@code dev.camera} call sets it (to its
 * {@code fov}, or to the player's FOV option when omitted); {@code dev.release} clears it.
 */
public final class DevCamera {
	private static volatile Float fovPin;

	private DevCamera() {
	}

	/** The pinned FOV in degrees, or null for vanilla behaviour. */
	public static Float fovPin() {
		return fovPin;
	}

	public static void pinFov(float fov) {
		fovPin = fov;
	}

	public static void releaseFov() {
		fovPin = null;
	}
}
