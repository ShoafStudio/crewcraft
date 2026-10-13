package dev.agentcraft.client.diff;

import dev.agentcraft.client.ui.UiStyle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * "Syntax-ish" tint for code lines on paper: keywords, strings, comments and numbers in the paper
 * text tokens (each >= 4.5:1 on cream and on the add/del row tints). Stateless and per line (a line
 * that starts like a block-comment continuation is treated as a comment), language picked from the
 * file extension. Markdown/plain text gets no tint.
 */
public final class SyntaxTint {
	public static final int PLAIN = 0;
	public static final int KEYWORD = 1;
	public static final int STRING = 2;
	public static final int COMMENT = 3;
	public static final int NUMBER = 4;

	private static final int[] NONE = new int[0];

	public enum Lang {
		NONE(false, null, Set.of()),
		JS(true, null, words("break case catch class const continue debugger default delete do else enum export extends false finally for from"
			+ " function if implements import in instanceof interface let new null of private protected public readonly return static super switch"
			+ " this throw true try type typeof undefined var void while yield async await as satisfies declare namespace abstract keyof")),
		JAVA(true, null, words("abstract assert boolean break byte case catch char class const continue default do double else enum extends final"
			+ " finally float for if implements import instanceof int interface long native new null package private protected public record return"
			+ " sealed permits short static super switch synchronized this throw throws transient true false try var void volatile while yield val"
			+ " fun when object companion data override open internal is in")),
		CLIKE(true, null, words("auto break case char const continue default do double else enum extern float for goto if inline int long register"
			+ " return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace public private protected"
			+ " template typename using virtual new delete true false nullptr this bool string var")),
		GO(true, null, words("break case chan const continue default defer else fallthrough for func go goto if import interface map package range"
			+ " return select struct switch type var true false nil")),
		RUST(true, null, words("as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub"
			+ " ref return self Self static struct super trait true type unsafe use where while")),
		PY(false, "#", words("and as assert async await break class continue def del elif else except False finally for from global if import in is"
			+ " lambda None nonlocal not or pass raise return True try while with yield self")),
		SHELL(false, "#", words("if then else elif fi case esac for while until do done in function return local export echo exit set")),
		CONFIG(false, "#", words("true false null yes no on off")),
		JSON(false, null, words("true false null")),
		SQL(false, "--", words("select from where and or not insert into values update set delete create table alter drop index join left right"
			+ " inner outer on group by order having limit as null is primary key"));

		final boolean slashComments;
		final String lineComment;
		final Set<String> keywords;

		Lang(boolean slashComments, String lineComment, Set<String> keywords) {
			this.slashComments = slashComments;
			this.lineComment = lineComment;
			this.keywords = keywords;
		}
	}

	private SyntaxTint() {
	}

	private static Set<String> words(String s) {
		return new HashSet<>(Arrays.asList(s.split(" ")));
	}

	public static Lang forPath(String path) {
		String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
		int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
		String name = p.substring(slash + 1);
		if (name.equals("makefile") || name.equals("dockerfile") || name.startsWith(".env") || name.equals(".gitignore")) {
			return Lang.CONFIG;
		}
		int dot = name.lastIndexOf('.');
		String ext = dot < 0 ? "" : name.substring(dot + 1);
		return switch (ext) {
			case "ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts", "vue", "svelte" -> Lang.JS;
			case "java", "kt", "kts", "groovy", "gradle", "scala", "dart", "swift" -> Lang.JAVA;
			case "c", "h", "cpp", "hpp", "cc", "cxx", "cs", "m", "mm", "php", "css", "scss", "less" -> Lang.CLIKE;
			case "go" -> Lang.GO;
			case "rs" -> Lang.RUST;
			case "py", "pyi", "rb" -> Lang.PY;
			case "sh", "bash", "zsh", "ps1", "psm1", "fish" -> Lang.SHELL;
			case "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "env" -> Lang.CONFIG;
			case "json", "jsonc", "json5", "lock" -> Lang.JSON;
			case "sql", "lua" -> Lang.SQL;
			default -> Lang.NONE;
		};
	}

	/** Paper colour of a token class (PLAIN = ink). */
	public static int color(int cls) {
		return switch (cls) {
			case KEYWORD -> UiStyle.color("paper.hunk");
			case STRING -> UiStyle.color("paper.path");
			case COMMENT -> UiStyle.color("paper.muted");
			case NUMBER -> UiStyle.color("paper.link");
			default -> UiStyle.color("paper.text");
		};
	}

	/** Token spans of one line as triples {start, end, class}, covering only tinted ranges (gaps are plain). */
	public static int[] spans(Lang lang, String s) {
		if (lang == Lang.NONE || s.isEmpty()) {
			return NONE;
		}
		int n = s.length();
		int[] out = new int[24];
		int k = 0;
		String trimmed = s.stripLeading();
		if (lang.slashComments && (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//"))) {
			if (trimmed.startsWith("/*") || trimmed.startsWith("//") || trimmed.startsWith("* ") || trimmed.equals("*") || trimmed.startsWith("*/")) {
				return new int[] {n - trimmed.length(), n, COMMENT};
			}
		}
		int i = 0;
		while (i < n) {
			char ch = s.charAt(i);
			int start = i;
			int cls = PLAIN;
			if (lang.slashComments && ch == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
				i = n;
				cls = COMMENT;
			} else if (lang.slashComments && ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int e = s.indexOf("*/", i + 2);
				i = e < 0 ? n : e + 2;
				cls = COMMENT;
			} else if (lang.lineComment != null && s.startsWith(lang.lineComment, i) && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
				i = n;
				cls = COMMENT;
			} else if (ch == '"' || ch == '\'' || (ch == '`' && lang == Lang.JS)) {
				if (ch == '\'' && (lang == Lang.RUST || lang == Lang.CONFIG) && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
					i++;
					continue; // lifetimes / apostrophes in words
				}
				i++;
				while (i < n && s.charAt(i) != ch) {
					i += s.charAt(i) == '\\' ? 2 : 1;
				}
				i = Math.min(n, i + 1);
				cls = STRING;
			} else if (Character.isDigit(ch) && (i == 0 || !Character.isJavaIdentifierPart(s.charAt(i - 1)))) {
				while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
					i++;
				}
				cls = NUMBER;
			} else if (Character.isJavaIdentifierStart(ch)) {
				while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
					i++;
				}
				String w = s.substring(start, i);
				if (lang.keywords.contains(lang == Lang.SQL ? w.toLowerCase(Locale.ROOT) : w)) {
					cls = KEYWORD;
				}
			} else {
				i++;
			}
			if (cls != PLAIN) {
				if (k + 3 > out.length) {
					out = Arrays.copyOf(out, out.length * 2);
				}
				out[k++] = start;
				out[k++] = i;
				out[k++] = cls;
			}
		}
		return k == 0 ? NONE : Arrays.copyOf(out, k);
	}
}
