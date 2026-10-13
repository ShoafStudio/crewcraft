package dev.agentcraft.client.console;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/**
 * Editable text with a caret and a selection, for the console field and the decision answer field.
 * Newlines are allowed (multi-line paste, Shift+Enter); control characters are dropped and tabs
 * become spaces. {@link #layout} soft-wraps it for drawing and caret moves.
 */
public final class TextModel {
	private final StringBuilder value = new StringBuilder();
	private int cursor;
	private int anchor;
	private final int maxLength;
	private long editedAt = System.currentTimeMillis();

	public TextModel(int maxLength) {
		this.maxLength = maxLength;
	}

	public String value() {
		return value.toString();
	}

	public int length() {
		return value.length();
	}

	public boolean isEmpty() {
		return value.isEmpty();
	}

	public int cursor() {
		return cursor;
	}

	public int selStart() {
		return Math.min(cursor, anchor);
	}

	public int selEnd() {
		return Math.max(cursor, anchor);
	}

	public boolean hasSelection() {
		return cursor != anchor;
	}

	public long editedAt() {
		return editedAt;
	}

	public void touch() {
		editedAt = System.currentTimeMillis();
	}

	public void set(String s) {
		value.setLength(0);
		value.append(clean(s, maxLength));
		cursor = anchor = value.length();
		touch();
	}

	public void clear() {
		set("");
	}

	public String selected() {
		return value.substring(selStart(), selEnd());
	}

	/** Insert (replacing the selection). */
	public void insert(String raw) {
		String s = clean(raw, Integer.MAX_VALUE);
		int a = selStart();
		int b = selEnd();
		int room = maxLength - (value.length() - (b - a));
		if (room <= 0) {
			return;
		}
		if (s.length() > room) {
			s = s.substring(0, room);
		}
		value.replace(a, b, s);
		cursor = anchor = a + s.length();
		touch();
	}

	/** Replace {@code [start, end)} (completion). */
	public void replace(int start, int end, String s) {
		int a = Math.max(0, Math.min(start, value.length()));
		int b = Math.max(a, Math.min(end, value.length()));
		value.replace(a, b, clean(s, maxLength));
		cursor = anchor = Math.min(value.length(), a + s.length());
		touch();
	}

	public void backspace(boolean word) {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		if (cursor == 0) {
			return;
		}
		int to = word ? wordLeft(cursor) : cursor - 1;
		value.delete(to, cursor);
		cursor = anchor = to;
		touch();
	}

	public void delete(boolean word) {
		if (hasSelection()) {
			deleteSelection();
			return;
		}
		if (cursor >= value.length()) {
			return;
		}
		int to = word ? wordRight(cursor) : cursor + 1;
		value.delete(cursor, to);
		anchor = cursor;
		touch();
	}

	public void deleteSelection() {
		int a = selStart();
		value.delete(a, selEnd());
		cursor = anchor = a;
		touch();
	}

	public void moveTo(int pos, boolean select) {
		cursor = Math.max(0, Math.min(pos, value.length()));
		if (!select) {
			anchor = cursor;
		}
		touch();
	}

	public void left(boolean word, boolean select) {
		if (!select && hasSelection()) {
			moveTo(selStart(), false);
			return;
		}
		moveTo(word ? wordLeft(cursor) : cursor - 1, select);
	}

	public void right(boolean word, boolean select) {
		if (!select && hasSelection()) {
			moveTo(selEnd(), false);
			return;
		}
		moveTo(word ? wordRight(cursor) : cursor + 1, select);
	}

	public void selectAll() {
		anchor = 0;
		cursor = value.length();
		touch();
	}

	public int lineStart(int pos) {
		return value.lastIndexOf("\n", pos - 1) + 1;
	}

	public int lineEnd(int pos) {
		int i = value.indexOf("\n", pos);
		return i < 0 ? value.length() : i;
	}

	private int wordLeft(int pos) {
		int i = pos;
		while (i > 0 && Character.isWhitespace(value.charAt(i - 1))) {
			i--;
		}
		while (i > 0 && !Character.isWhitespace(value.charAt(i - 1))) {
			i--;
		}
		return i;
	}

	private int wordRight(int pos) {
		int i = pos;
		int n = value.length();
		while (i < n && Character.isWhitespace(value.charAt(i))) {
			i++;
		}
		while (i < n && !Character.isWhitespace(value.charAt(i))) {
			i++;
		}
		return i;
	}

	static String clean(String s, int max) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length() && b.length() < max; i++) {
			char c = s.charAt(i);
			if (c == '\r') {
				continue;
			}
			if (c == '\t') {
				b.append("  ");
			} else if (c == '\n' || c >= ' ' && c != 0x7F && c != '§') {
				b.append(c);
			}
		}
		return b.toString();
	}

	// ------------------------------------------------------------------ layout

	/** A visual line: chars {@code [start, end)} of the value. */
	public record VLine(int start, int end) {
	}

	/**
	 * Soft-wrap into visual lines of at most {@code width} px: hard newlines start a new line; long
	 * lines break after the last space that fits (or mid-word when there is none).
	 */
	public List<VLine> layout(Font font, int width) {
		List<VLine> out = new ArrayList<>();
		String v = value.toString();
		int lineStart = 0;
		while (true) {
			int nl = v.indexOf('\n', lineStart);
			int hardEnd = nl < 0 ? v.length() : nl;
			int s = lineStart;
			if (s == hardEnd) {
				out.add(new VLine(s, s));
			}
			while (s < hardEnd) {
				String rest = v.substring(s, hardEnd);
				String fit = font.plainSubstrByWidth(rest, Math.max(8, width));
				int e = s + Math.max(1, fit.length());
				if (e < hardEnd) {
					int sp = v.lastIndexOf(' ', e - 1);
					if (sp >= s + 1 && sp < e) {
						e = sp + 1;
					}
				}
				out.add(new VLine(s, e));
				s = e;
			}
			if (nl < 0) {
				break;
			}
			lineStart = nl + 1;
			if (lineStart == v.length()) {
				out.add(new VLine(lineStart, lineStart));
				break;
			}
		}
		if (out.isEmpty()) {
			out.add(new VLine(0, 0));
		}
		return out;
	}

	/** Index of the visual line that holds {@code pos} (the caret sits at the end of a wrapped line only at the very end). */
	public static int lineOf(List<VLine> lines, int pos) {
		for (int i = 0; i < lines.size(); i++) {
			VLine l = lines.get(i);
			boolean last = i == lines.size() - 1;
			boolean nextStartsHere = !last && lines.get(i + 1).start() == pos;
			if (pos >= l.start() && (pos < l.end() || pos == l.end() && !nextStartsHere)) {
				return i;
			}
		}
		return lines.size() - 1;
	}

	/** Caret move to the visual line above/below at the same x; false at the first/last line. */
	public boolean vertical(Font font, int width, int dir, boolean select) {
		List<VLine> lines = layout(font, width);
		int li = lineOf(lines, cursor);
		int target = li + dir;
		if (target < 0 || target >= lines.size()) {
			return false;
		}
		VLine cur = lines.get(li);
		int x = font.width(value.substring(cur.start(), cursor));
		VLine t = lines.get(target);
		String seg = value.substring(t.start(), t.end());
		String fit = font.plainSubstrByWidth(seg, x);
		int pos = t.start() + fit.length();
		if (pos > t.start() && pos == t.end() && target < lines.size() - 1 && pos > 0 && value.charAt(pos - 1) == ' ') {
			pos--;
		}
		moveTo(Math.min(pos, t.end()), select);
		return true;
	}
}
