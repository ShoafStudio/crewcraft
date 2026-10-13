package dev.agentcraft.client.agents;

import com.mojang.blaze3d.platform.Window;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLTimer;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryUtil;

/**
 * TEST ONLY ({@code AGENTCRAFT_DEV_TEST=1}): presses keys the way SDL reports a keyboard, by
 * queueing SDL events for the game's own window ({@code SDL_PushEvent}). They go through
 * Minecraft's normal SDL event loop ({@code SDLEventHandler} -> {@code KeyboardHandler} -> the open
 * screen), unlike {@code dev.type}, which calls {@code charTyped} directly. Like SDL with a real
 * keyboard, a printable key also produces an {@code SDL_EVENT_TEXT_INPUT} event <b>only while SDL
 * text input is active</b> for the window ({@code SDL_TextInputActive}); that is exactly the 26.x
 * rule that broke the card's first message line (it never started text input), so a test that
 * types through here fails when a screen forgets to start it.
 *
 * <p>(Posting Windows key messages instead does not work for an unfocused window: SDL routes
 * keyboard events only to the window with OS focus, and taking focus is not allowed for QA.)
 */
final class SdlKeys {
	private static final int SC_A = 4;
	private static final int SC_1 = 30;
	private static final int SC_0 = 39;
	private static final int SC_RETURN = 40;
	private static final int SC_ESCAPE = 41;
	private static final int SC_BACKSPACE = 42;
	private static final int SC_TAB = 43;
	private static final int SC_SPACE = 44;
	private static final int SC_RIGHT = 79;
	private static final int SC_LEFT = 80;

	private SdlKeys() {
	}

	/** SDL scancode for a key name (a letter, a digit, space return escape back tab left right), or -1. */
	static int scancode(String key) {
		String k = key.toLowerCase(Locale.ROOT);
		if (k.length() == 1) {
			char c = k.charAt(0);
			if (c >= 'a' && c <= 'z') {
				return SC_A + (c - 'a');
			}
			if (c >= '1' && c <= '9') {
				return SC_1 + (c - '1');
			}
			if (c == '0') {
				return SC_0;
			}
			return c == ' ' ? SC_SPACE : -1;
		}
		return switch (k) {
			case "space" -> SC_SPACE;
			case "return", "enter" -> SC_RETURN;
			case "escape", "esc" -> SC_ESCAPE;
			case "back", "backspace" -> SC_BACKSPACE;
			case "tab" -> SC_TAB;
			case "left" -> SC_LEFT;
			case "right" -> SC_RIGHT;
			default -> -1;
		};
	}

	/** The character a key types (no modifiers), or 0. */
	private static char typed(int sc) {
		if (sc >= SC_A && sc < SC_A + 26) {
			return (char) ('a' + sc - SC_A);
		}
		if (sc >= SC_1 && sc < SC_1 + 9) {
			return (char) ('1' + sc - SC_1);
		}
		return sc == SC_0 ? '0' : sc == SC_SPACE ? ' ' : 0;
	}

	/**
	 * Queue one key press (down, text if SDL text input is on, up) for the game window. Text buffers
	 * are added to {@code keep}; free them ({@link #free}) after the events were polled.
	 *
	 * @return whether a text event was queued
	 */
	static boolean press(Window window, int scancode, List<ByteBuffer> keep) {
		int windowId = SDLVideo.SDL_GetWindowID(window.handle());
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) 0, false);
		long now = SDLTimer.SDL_GetTicksNS();
		boolean text = false;
		try (SDL_Event ev = SDL_Event.calloc()) {
			ev.key().set(SDLEvents.SDL_EVENT_KEY_DOWN, now, windowId, 0, scancode, keycode, (short) 0, (short) 0, true, false);
			SDLEvents.SDL_PushEvent(ev);
			char c = typed(scancode);
			if (c != 0 && SDLKeyboard.SDL_TextInputActive(window.handle())) {
				ByteBuffer utf8 = MemoryUtil.memUTF8(String.valueOf(c), true);
				keep.add(utf8);
				SDL_Event te = SDL_Event.calloc();
				te.text().set(SDLEvents.SDL_EVENT_TEXT_INPUT, now, windowId, utf8);
				SDLEvents.SDL_PushEvent(te);
				te.free();
				text = true;
			}
			ev.key().set(SDLEvents.SDL_EVENT_KEY_UP, now + 1, windowId, 0, scancode, keycode, (short) 0, (short) 0, false, false);
			SDLEvents.SDL_PushEvent(ev);
		}
		return text;
	}

	static void free(List<ByteBuffer> keep) {
		for (ByteBuffer b : keep) {
			MemoryUtil.memFree(b);
		}
		keep.clear();
	}
}
