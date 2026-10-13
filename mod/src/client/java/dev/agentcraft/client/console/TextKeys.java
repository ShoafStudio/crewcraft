package dev.agentcraft.client.console;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;

/**
 * Editing keys for a {@link TextModel}: Backspace/Delete (Ctrl = word), arrows (Ctrl = word,
 * Shift = select), Home/End (line; Ctrl = whole text), Ctrl+A/C/X/V. Enter, Tab and Up/Down are left
 * to the caller (submit, completion, history).
 */
public final class TextKeys {
	private TextKeys() {
	}

	/** Ctrl+letter by keycode (layout-aware), or by scancode when the keycode is missing (DevBridge key events). */
	static boolean ctrl(KeyEvent e, int keycodeChar, int scancode) {
		if (!e.hasControlDown() || e.hasAltDown()) {
			return false;
		}
		return e.keycode() == keycodeChar || e.keycode() == 0 && e.key() == scancode;
	}

	public static boolean isPaste(KeyEvent e) {
		return e.isPaste() || ctrl(e, 'v', InputConstants.KEY_V);
	}

	public static boolean handle(KeyEvent e, TextModel m) {
		boolean ctrl = e.hasControlDown();
		boolean shift = e.hasShiftDown();
		int k = e.key();
		Minecraft mc = Minecraft.getInstance();
		if (e.isSelectAll() || ctrl(e, 'a', InputConstants.KEY_A)) {
			m.selectAll();
			return true;
		}
		if (e.isCopy() || ctrl(e, 'c', InputConstants.KEY_C)) {
			if (m.hasSelection()) {
				mc.keyboardHandler.setClipboard(m.selected());
			}
			return true;
		}
		if (e.isCut() || ctrl(e, 'x', InputConstants.KEY_X)) {
			if (m.hasSelection()) {
				mc.keyboardHandler.setClipboard(m.selected());
				m.deleteSelection();
			}
			return true;
		}
		if (isPaste(e)) {
			m.insert(mc.keyboardHandler.getClipboard());
			return true;
		}
		switch (k) {
			case InputConstants.KEY_BACKSPACE -> {
				m.backspace(ctrl);
				return true;
			}
			case InputConstants.KEY_DELETE -> {
				m.delete(ctrl);
				return true;
			}
			case InputConstants.KEY_LEFT -> {
				m.left(ctrl, shift);
				return true;
			}
			case InputConstants.KEY_RIGHT -> {
				m.right(ctrl, shift);
				return true;
			}
			case InputConstants.KEY_HOME -> {
				m.moveTo(ctrl ? 0 : m.lineStart(m.cursor()), shift);
				return true;
			}
			case InputConstants.KEY_END -> {
				m.moveTo(ctrl ? m.length() : m.lineEnd(m.cursor()), shift);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	public static boolean isEnter(KeyEvent e) {
		return e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER;
	}

	/** 1-9 from the number row or the keypad (scancodes), else 0. */
	public static int digit(KeyEvent e) {
		int k = e.key();
		if (k >= InputConstants.KEY_1 && k <= InputConstants.KEY_9) {
			return k - InputConstants.KEY_1 + 1;
		}
		// SDL keypad 1..9 = 89..97
		if (k >= 89 && k <= 97) {
			return k - 89 + 1;
		}
		return 0;
	}
}
