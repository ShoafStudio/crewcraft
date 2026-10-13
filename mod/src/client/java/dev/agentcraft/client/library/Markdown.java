package dev.agentcraft.client.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small markdown reader for memory notes: headings, paragraphs, bullet / numbered / task lists
 * (nested by indent), block quotes, fenced code, rules and pipe tables; inline bold, italic, code
 * spans, links and strike-through. Unknown syntax degrades to plain text, never to an error.
 */
final class Markdown {
	enum Type {
		H1, H2, H3, PARA, BULLET, NUMBER, TASK, QUOTE, CODE, RULE, TABLE
	}

	static final int BOLD = 1;
	static final int ITALIC = 2;
	static final int CODE_SPAN = 4;
	static final int LINK = 8;
	static final int STRIKE = 16;

	record Span(String text, int style) {
	}

	static final class Block {
		final Type type;
		/** Inline text (paragraph, heading, list item, quote). */
		String text = "";
		/** List nesting level (0 = top). */
		int level;
		/** NUMBER: the marker ("1."). */
		String marker = "";
		/** TASK: checked. */
		boolean checked;
		/** CODE: the lines; fence info (language) in {@link #lang}. */
		final List<String> lines = new ArrayList<>();
		String lang = "";
		/** TABLE: rows of cells (first row = header). */
		final List<List<String>> rows = new ArrayList<>();

		Block(Type type) {
			this.type = type;
		}
	}

	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
	private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
	private static final Pattern TASK = Pattern.compile("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.*)$");
	private static final Pattern NUMBER = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
	private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
	private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

	private Markdown() {
	}

	static List<Block> parse(String md) {
		List<Block> out = new ArrayList<>();
		String[] lines = (md == null ? "" : md.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1);
		Block para = null;
		for (int i = 0; i < lines.length; i++) {
			String raw = lines[i].replace("\t", "    ");
			String t = raw.trim();
			// fenced code
			if (t.startsWith("```") || t.startsWith("~~~")) {
				String fence = t.substring(0, 3);
				Block b = new Block(Type.CODE);
				b.lang = t.substring(3).trim().toLowerCase(Locale.ROOT);
				int j = i + 1;
				while (j < lines.length && !lines[j].trim().startsWith(fence)) {
					b.lines.add(lines[j].replace("\t", "    "));
					j++;
				}
				out.add(b);
				i = j;
				para = null;
				continue;
			}
			if (t.isEmpty()) {
				para = null;
				continue;
			}
			Matcher m;
			if ((m = HEADING.matcher(t)).matches()) {
				int n = m.group(1).length();
				Block b = new Block(n == 1 ? Type.H1 : n == 2 ? Type.H2 : Type.H3);
				b.text = m.group(2);
				out.add(b);
				para = null;
				continue;
			}
			if (RULE.matcher(t).matches()) {
				out.add(new Block(Type.RULE));
				para = null;
				continue;
			}
			// pipe table: a header row followed by a separator row
			if (t.contains("|") && i + 1 < lines.length && TABLE_SEP.matcher(lines[i + 1]).matches()) {
				Block b = new Block(Type.TABLE);
				b.rows.add(cells(t));
				int j = i + 2;
				while (j < lines.length && lines[j].trim().contains("|") && !lines[j].trim().isEmpty()) {
					b.rows.add(cells(lines[j].trim()));
					j++;
				}
				out.add(b);
				i = j - 1;
				para = null;
				continue;
			}
			if (t.startsWith(">")) {
				String q = t.replaceFirst("^>+\\s?", "");
				Block last = out.isEmpty() ? null : out.get(out.size() - 1);
				if (last != null && last.type == Type.QUOTE && para == last) {
					last.text = last.text + " " + q;
				} else {
					Block b = new Block(Type.QUOTE);
					b.text = q;
					out.add(b);
					para = b;
				}
				continue;
			}
			if ((m = TASK.matcher(raw)).matches()) {
				Block b = new Block(Type.TASK);
				b.level = level(m.group(1));
				b.checked = !m.group(2).isBlank();
				b.text = m.group(3);
				out.add(b);
				para = b;
				continue;
			}
			if ((m = BULLET.matcher(raw)).matches()) {
				Block b = new Block(Type.BULLET);
				b.level = level(m.group(1));
				b.text = m.group(2);
				out.add(b);
				para = b;
				continue;
			}
			if ((m = NUMBER.matcher(raw)).matches()) {
				Block b = new Block(Type.NUMBER);
				b.level = level(m.group(1));
				b.marker = m.group(2) + ".";
				b.text = m.group(3);
				out.add(b);
				para = b;
				continue;
			}
			// continuation of the previous paragraph / list item (lazy lines)
			if (para != null && para.type != Type.QUOTE) {
				para.text = para.text + " " + t;
				continue;
			}
			Block b = new Block(Type.PARA);
			b.text = t;
			out.add(b);
			para = b;
		}
		return out;
	}

	private static int level(String indent) {
		return Math.min(4, indent.length() / 2);
	}

	private static List<String> cells(String row) {
		String r = row.trim();
		if (r.startsWith("|")) {
			r = r.substring(1);
		}
		if (r.endsWith("|")) {
			r = r.substring(0, r.length() - 1);
		}
		List<String> out = new ArrayList<>();
		for (String c : r.split("\\|", -1)) {
			out.add(c.trim());
		}
		return out;
	}

	/** Inline spans of a line of markdown text. */
	static List<Span> inline(String s) {
		List<Span> out = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		int style = 0;
		int n = s.length();
		int i = 0;
		while (i < n) {
			char ch = s.charAt(i);
			if (ch == '\\' && i + 1 < n && "\\`*_[]()#+-.!~|".indexOf(s.charAt(i + 1)) >= 0) {
				buf.append(s.charAt(i + 1));
				i += 2;
				continue;
			}
			if (ch == '`') {
				int e = s.indexOf('`', i + 1);
				if (e > i) {
					flush(out, buf, style);
					out.add(new Span(s.substring(i + 1, e), style | CODE_SPAN));
					i = e + 1;
					continue;
				}
			}
			if (ch == '[') {
				int close = s.indexOf("](", i + 1);
				int end = close < 0 ? -1 : s.indexOf(')', close + 2);
				if (close > i && end > close) {
					flush(out, buf, style);
					out.add(new Span(s.substring(i + 1, close), style | LINK));
					i = end + 1;
					continue;
				}
			}
			if ((ch == '*' || ch == '_') && i + 1 < n && s.charAt(i + 1) == ch) {
				// ** or __ toggles bold (only when it can close later, or it is closing)
				if ((style & BOLD) != 0 || s.indexOf("" + ch + ch, i + 2) > 0) {
					flush(out, buf, style);
					style ^= BOLD;
					i += 2;
					continue;
				}
			}
			if (ch == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
				if ((style & STRIKE) != 0 || s.indexOf("~~", i + 2) > 0) {
					flush(out, buf, style);
					style ^= STRIKE;
					i += 2;
					continue;
				}
			}
			if (ch == '*' || (ch == '_' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1))) || ch == '_' && (style & ITALIC) != 0)) {
				boolean opening = (style & ITALIC) == 0;
				boolean canOpen = opening && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1)) && s.indexOf(ch, i + 1) > 0;
				boolean canClose = !opening && i > 0 && !Character.isWhitespace(s.charAt(i - 1));
				if (canOpen || canClose) {
					flush(out, buf, style);
					style ^= ITALIC;
					i++;
					continue;
				}
			}
			buf.append(ch);
			i++;
		}
		flush(out, buf, style);
		return out;
	}

	private static void flush(List<Span> out, StringBuilder buf, int style) {
		if (!buf.isEmpty()) {
			out.add(new Span(buf.toString(), style));
			buf.setLength(0);
		}
	}

	/** Plain text of inline markdown (for titles, labels, comparisons). */
	static String plain(String s) {
		StringBuilder b = new StringBuilder();
		for (Span sp : inline(s)) {
			b.append(sp.text());
		}
		return b.toString();
	}
}
