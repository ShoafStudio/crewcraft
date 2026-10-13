package dev.agentcraft.client.dev;

import com.mojang.blaze3d.platform.Window;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/**
 * OS-level focus check (Windows): is the game window the real foreground window? SDL's own
 * "focused" flag can disagree with the OS when the window was shown without activation, so
 * dev.state reports both. Uses the Java FFM API (no extra native deps). Read-only.
 */
final class WinFocus {
	private static final MethodHandle GET_FOREGROUND_WINDOW = init();

	private WinFocus() {
	}

	private static MethodHandle init() {
		if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			return null;
		}
		try {
			SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
			return Linker.nativeLinker().downcallHandle(user32.find("GetForegroundWindow").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
		} catch (Throwable t) {
			return null;
		}
	}

	/** @return true/false on Windows, null when unknown. */
	static Boolean isForeground(Window window) {
		if (GET_FOREGROUND_WINDOW == null) {
			return null;
		}
		try {
			long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window.handle()), "SDL.window.win32.hwnd", 0L);
			if (hwnd == 0L) {
				return null;
			}
			MemorySegment fg = (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
			return fg.address() == hwnd;
		} catch (Throwable t) {
			return null;
		}
	}
}
