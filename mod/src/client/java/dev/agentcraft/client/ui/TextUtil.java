package dev.agentcraft.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/** Text helpers shared by screens and in-world renderers: ellipsis, wrapping, scrolling. */
public final class TextUtil {
	public static final String ELLIPSIS = "…";

	private TextUtil() {
	}

	/** {@code s} cut to {@code maxWidth} px with a trailing ellipsis when it does not fit. */
	public static String ellipsize(Font font, String s, int maxWidth) {
		if (s == null) {
			return "";
		}
		if (font.width(s) <= maxWidth) {
			return s;
		}
		int ew = font.width(ELLIPSIS);
		if (maxWidth <= ew) {
			return "";
		}
		return font.plainSubstrByWidth(s, maxWidth - ew).stripTrailing() + ELLIPSIS;
	}

	/** Word-wrap plain text (respecting newlines) into lines of at most {@code width} px. */
	public static List<FormattedCharSequence> wrap(Font font, String s, int width) {
		return font.split(FormattedText.of(s == null ? "" : s), Math.max(1, width));
	}

	/** Word-wrap into plain strings (useful for caching/measuring). */
	public static List<String> wrapPlain(Font font, String s, int width) {
		List<String> out = new ArrayList<>();
		for (String para : (s == null ? "" : s).split("\n", -1)) {
			if (para.isEmpty()) {
				out.add("");
				continue;
			}
			font.getSplitter().splitLines(para, Math.max(1, width), net.minecraft.network.chat.Style.EMPTY)
				.forEach(ft -> out.add(ft.getString()));
		}
		return out;
	}

	/** First line of {@code s}, trimmed. */
	public static String firstLine(String s) {
		if (s == null) {
			return "";
		}
		int nl = s.indexOf('\n');
		return (nl < 0 ? s : s.substring(0, nl)).trim();
	}

	/**
	 * Scroll model for a list of {@code content} rows shown {@code view} rows at a time.
	 * {@code offset} is the first visible row. With {@code follow} on (default) it stays pinned to
	 * the bottom as content grows (log tails); scrolling up turns follow off until you reach the end.
	 */
	public static final class Scroll {
		private int content;
		private int view = 1;
		private int offset;
		private boolean follow = true;

		public int content() {
			return content;
		}

		public int view() {
			return view;
		}

		public int offset() {
			return offset;
		}

		public boolean following() {
			return follow;
		}

		public boolean scrollable() {
			return content > view;
		}

		public int max() {
			return Math.max(0, content - view);
		}

		/** 0 = top, 1 = bottom. */
		public double fraction() {
			return max() == 0 ? 0 : (double) offset / max();
		}

		/** Update sizes (call every frame or when content changes). */
		public Scroll update(int contentRows, int viewRows) {
			this.content = Math.max(0, contentRows);
			this.view = Math.max(1, viewRows);
			offset = follow ? max() : Math.max(0, Math.min(offset, max()));
			return this;
		}

		public void scrollBy(int rows) {
			offset = Math.max(0, Math.min(offset + rows, max()));
			follow = offset >= max();
		}

		public void toTop() {
			offset = 0;
			follow = max() == 0;
		}

		public void toBottom() {
			offset = max();
			follow = true;
		}
	}
}
