package dev.agentcraft.client.console;

import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.UiStyle;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Draws a {@link TextModel} as a kit text field (ui-style "Console field 18 px tall, prefix in brass,
 * ghost completion in #BCAD95, 1 px ink caret blinking every 500 ms"). Multi-line values grow the
 * field up to {@code maxLines} visual lines and scroll to keep the caret in view.
 */
public final class TextFieldView {
	public static final int LINE = 10;
	public static final int BASE_H = 18;

	/** What to draw besides the text. */
	public record Style(@Nullable String prefix, int prefixColor, @Nullable String placeholder, @Nullable String ghost, @Nullable String hintRight,
		int hintColor, int maxLines) {
	}

	private int scrollLine;

	/** The wrapped lines and the wrap width; a value taller than the field wraps narrower, leaving the right edge to the "12/27" marker. */
	private record Fit(List<TextModel.VLine> lines, int iw, boolean overflow) {
	}

	private static Fit fit(Font font, TextModel m, int w, Style st) {
		int iw = innerW(font, w, st);
		List<TextModel.VLine> lines = m.layout(font, iw);
		if (lines.size() <= st.maxLines()) {
			return new Fit(lines, iw, false);
		}
		int narrow = Math.max(20, iw - markerRoom(font));
		return new Fit(m.layout(font, narrow), narrow, true);
	}

	private static int markerRoom(Font font) {
		return font.width("999/999") + 8;
	}

	/** Height the field will take for this model and width. */
	public int height(Font font, TextModel m, int w, Style st) {
		int lines = Math.min(st.maxLines(), fit(font, m, w, st).lines().size());
		return BASE_H + (Math.max(1, lines) - 1) * LINE;
	}

	private static int prefixW(Font font, Style st) {
		return st.prefix() == null ? 0 : font.width(st.prefix()) + 4;
	}

	private static int innerW(Font font, int w, Style st) {
		Kit.Padding p = Kit.padding("text_field");
		return Math.max(20, w - p.left() - p.right() - prefixW(font, st) - 1);
	}

	/** The width the text wraps at for this value (callers moving the caret by visual lines use it). */
	public static int wrapWidth(Font font, TextModel m, int w, Style st) {
		return fit(font, m, w, st).iw();
	}

	public int draw(GuiGraphicsExtractor g, Font font, TextModel m, int x, int y, int w, boolean focused, Style st) {
		Kit.Padding p = Kit.padding("text_field");
		Fit f = fit(font, m, w, st);
		int iw = f.iw();
		List<TextModel.VLine> lines = f.lines();
		int vis = Math.max(1, Math.min(st.maxLines(), lines.size()));
		int h = BASE_H + (vis - 1) * LINE;
		Panels.sprite(g, focused ? Kit.TEXT_FIELD_FOCUSED : Kit.TEXT_FIELD, x, y, w, h);
		int tx = x + p.left() + prefixW(font, st);
		int ty = y + 5;
		if (st.prefix() != null) {
			g.text(font, st.prefix(), x + p.left(), ty, st.prefixColor(), false);
		}
		int caretLine = TextModel.lineOf(lines, m.cursor());
		if (caretLine < scrollLine) {
			scrollLine = caretLine;
		} else if (caretLine >= scrollLine + vis) {
			scrollLine = caretLine - vis + 1;
		}
		scrollLine = Math.max(0, Math.min(scrollLine, lines.size() - vis));
		String v = m.value();
		int ink = UiBits.ink();
		if (m.isEmpty() && st.placeholder() != null) {
			g.text(font, st.placeholder(), tx, ty, UiStyle.color("ink_ui.ghost_on_paper", 0xFFBCAD95), false);
		}
		int caretX = tx;
		int caretY = ty;
		for (int i = 0; i < vis; i++) {
			int li = scrollLine + i;
			if (li >= lines.size()) {
				break;
			}
			TextModel.VLine l = lines.get(li);
			int ly = ty + i * LINE;
			String seg = v.substring(l.start(), l.end());
			// selection
			if (m.hasSelection()) {
				int a = Math.max(m.selStart(), l.start());
				int b = Math.min(m.selEnd(), l.end());
				if (a < b) {
					int sx = tx + font.width(v.substring(l.start(), a));
					int ex = tx + font.width(v.substring(l.start(), b));
					g.fill(sx, ly - 1, ex, ly + 9, UiStyle.color("palette.ui.field_focus", 0xFFF3CDBB));
				}
			}
			g.text(font, seg, tx, ly, ink, false);
			if (li == caretLine) {
				caretX = tx + font.width(v.substring(l.start(), Math.min(m.cursor(), l.end())));
				caretY = ly;
			}
		}
		boolean atEnd = m.cursor() == m.length() && !m.hasSelection();
		if (focused && atEnd && st.ghost() != null && !st.ghost().isEmpty() && caretLine == lines.size() - 1) {
			g.text(font, st.ghost(), caretX, caretY, UiStyle.color("ink_ui.ghost_on_paper", 0xFFBCAD95), false);
		}
		// right-aligned hint on the last visible line, when it fits
		if (st.hintRight() != null && !st.hintRight().isEmpty()) {
			int hw = font.width(st.hintRight());
			int lastLine = Math.min(lines.size(), scrollLine + vis) - 1;
			TextModel.VLine l = lines.get(Math.max(0, lastLine));
			int used = font.width(v.substring(l.start(), l.end()));
			if (m.isEmpty() && st.placeholder() != null) {
				used = font.width(st.placeholder());
			}
			if (lastLine == caretLine && focused && atEnd && st.ghost() != null) {
				used += font.width(st.ghost());
			}
			if (used + hw + 14 < iw) {
				g.text(font, st.hintRight(), x + w - p.right() - hw - 1, ty + (vis - 1) * LINE, st.hintColor(), false);
			}
		}
		if (focused && UiBits.caretOn(m.editedAt())) {
			g.fill(caretX, caretY - 1, caretX + 1, caretY + 9, ink);
		}
		if (focused) {
			Minecraft.getInstance().textInputManager().setTextInputArea(caretX, caretY, caretX + 1, caretY + LINE);
		}
		// a small "more lines" marker when scrolled, in the right-hand strip the text no longer uses
		if (lines.size() > vis) {
			String more = (scrollLine + vis) + "/" + lines.size();
			g.text(font, more, x + w - p.right() - font.width(more) - 1, ty, UiBits.muted(), false);
		}
		return h;
	}

	/** Character index under the mouse (for click-to-place), or -1 outside. */
	public int hit(Font font, TextModel m, int x, int y, int w, Style st, double mx, double my) {
		Kit.Padding p = Kit.padding("text_field");
		List<TextModel.VLine> lines = fit(font, m, w, st).lines();
		int vis = Math.max(1, Math.min(st.maxLines(), lines.size()));
		int h = BASE_H + (vis - 1) * LINE;
		if (mx < x || mx > x + w || my < y || my > y + h) {
			return -1;
		}
		int li = scrollLine + Math.max(0, Math.min(vis - 1, (int) ((my - y - 4) / LINE)));
		li = Math.min(li, lines.size() - 1);
		TextModel.VLine l = lines.get(li);
		int tx = x + p.left() + prefixW(font, st);
		String seg = m.value().substring(l.start(), l.end());
		String fit = font.plainSubstrByWidth(seg, (int) Math.max(0, mx - tx + 2));
		return l.start() + fit.length();
	}
}
