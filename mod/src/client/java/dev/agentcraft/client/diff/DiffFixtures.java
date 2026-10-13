package dev.agentcraft.client.diff;

import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.DiffFile;
import dev.agentcraft.client.foreman.Protocol.DiffFileStatus;
import dev.agentcraft.client.foreman.Protocol.DiffHunk;
import dev.agentcraft.client.foreman.Protocol.DiffLine;
import dev.agentcraft.client.foreman.Protocol.DiffLineKind;
import java.util.ArrayList;
import java.util.List;

/**
 * A synthetic worst-case diff for QA of the review screen ({@code dev.diff {fixture:true}}): many
 * files, every file status, a binary file, a pure rename, deep paths, tabs, a minified 600-char
 * line, a long deletion and the "truncated" notice. Never sent anywhere.
 */
final class DiffFixtures {
	private DiffFixtures() {
	}

	static Protocol.Diff stress() {
		List<DiffFile> files = new ArrayList<>();
		files.add(modified("src/auth/session.ts", 40, new String[] {
			" import { db } from '../db';",
			" ",
			"-export const SESSION_TTL = 60 * 60; // one hour",
			"+export const SESSION_TTL = 60 * 60 * 24 * 14; // two weeks, matches the OAuth refresh window",
			"+export const OAUTH_STATE_TTL = 10 * 60;",
			" ",
			" export async function createSession(userId: number): Promise<string> {",
			"-\tconst token = randomToken();",
			"+\tconst token = randomToken(32);",
			"\tawait db.run('INSERT INTO sessions (token, user_id, expires) VALUES (?, ?, ?)', [token, userId, Date.now() + SESSION_TTL * 1000]);",
			" \treturn token;",
			" }"}));
		files.add(added("src/auth/oauth.ts", new String[] {
			"// GitHub OAuth: authorize -> callback -> session",
			"import { createSession } from './session';",
			"",
			"export async function callback(req: Request): Promise<Response> {",
			"  const code = new URL(req.url).searchParams.get(\"code\");",
			"  if (!code) return new Response(\"missing code\", { status: 400 });",
			"  const profile = await fetchProfile(await exchange(code));",
			"  const user = await upsertUser(profile);",
			"  return redirect('/', await createSession(user.id));",
			"}"}));
		files.add(added("migrations/0007_oauth_accounts.sql", new String[] {
			"CREATE TABLE oauth_accounts (",
			"  id INTEGER PRIMARY KEY,",
			"  provider TEXT NOT NULL, -- 'github'",
			"  subject TEXT NOT NULL,",
			"  user_id INTEGER NOT NULL REFERENCES users(id)",
			");"}));
		files.add(deleted("src/auth/legacy-tokens.json.ts", 18));
		files.add(new DiffFile("src/web/components/very/deeply/nested/folder/structure/LoginButtonWithProviderIcons.tsx",
			"src/web/components/LoginButton.tsx", DiffFileStatus.RENAMED, false, 2, 1, List.of(hunk(3, 4, 3, 5, new String[] {
				" export function LoginButton() {",
				"-  return <button>Log in</button>;",
				"+  return <button className=\"login\">",
				"+    <GitHubIcon /> Log in with GitHub</button>;",
				" }"}))));
		files.add(new DiffFile("docs/screenshots/login.png", null, DiffFileStatus.MODIFIED, true, 0, 0, List.of()));
		files.add(new DiffFile("src/web/old-name.css", "src/web/styles.css", DiffFileStatus.RENAMED, false, 0, 0, List.of()));
		files.add(modified("dist/vendor.min.js", 1, new String[] {
			"-!function(e){var t={};function n(r){if(t[r])return t[r].exports}}([]);",
			"+" + "!function(e){var t={};function n(r){if(t[r])return t[r].exports;var o=t[r]={i:r,l:!1,exports:{}};return e[r].call(o.exports,o,"
				+ "o.exports,n),o.l=!0,o.exports}n.m=e,n.c=t,n.d=function(e,t,r){n.o(e,t)||Object.defineProperty(e,t,{enumerable:!0,get:r})},"
				+ "n.r=function(e){\"undefined\"!=typeof Symbol&&Symbol.toStringTag&&Object.defineProperty(e,Symbol.toStringTag,{value:\"Module\"}),"
				+ "Object.defineProperty(e,\"__esModule\",{value:!0})},n.t=function(e,t){if(1&t&&(e=n(e)),8&t)return e;if(4&t&&\"object\"==typeof e"
				+ "&&e&&e.__esModule)return e;var r=Object.create(null)}}([]);"}));
		for (int i = 1; i <= 8; i++) {
			files.add(modified("test/auth/case" + i + ".test.ts", 10 * i, new String[] {
				" import { test } from 'node:test';",
				"-test('case " + i + "', () => {});",
				"+test('case " + i + " handles the OAuth callback', async () => {",
				"+  assert.equal(await run(" + i + "), 'ok');",
				"+});"}));
		}
		int adds = 0;
		int dels = 0;
		for (DiffFile f : files) {
			adds += f.additions();
			dels += f.deletions();
		}
		return new Protocol.Diff("fixture", "fixture", "fixture", "main", "agentcraft/kit/t9-oauth-stress", files,
			new Protocol.DiffStats(files.size(), adds, dels), true, null);
	}

	private static DiffFile modified(String path, int start, String[] lines) {
		DiffHunk h = hunk(start, 0, start, 0, lines);
		int a = 0;
		int d = 0;
		for (DiffLine l : h.lines()) {
			if (l.kind() == DiffLineKind.ADD) {
				a++;
			} else if (l.kind() == DiffLineKind.DEL) {
				d++;
			}
		}
		return new DiffFile(path, null, DiffFileStatus.MODIFIED, false, a, d, List.of(h));
	}

	private static DiffFile added(String path, String[] lines) {
		String[] p = new String[lines.length];
		for (int i = 0; i < lines.length; i++) {
			p[i] = "+" + lines[i];
		}
		return new DiffFile(path, null, DiffFileStatus.ADDED, false, lines.length, 0, List.of(hunk(0, 0, 1, lines.length, p)));
	}

	private static DiffFile deleted(String path, int n) {
		String[] p = new String[n];
		for (int i = 0; i < n; i++) {
			p[i] = "-  \"token_" + i + "\": \"" + Integer.toHexString(0x5eed1234 * (i + 7)) + "\",";
		}
		return new DiffFile(path, null, DiffFileStatus.DELETED, false, 0, n, List.of(hunk(1, n, 0, 0, p)));
	}

	private static DiffHunk hunk(int oldStart, int oldLines, int newStart, int newLines, String[] raw) {
		List<DiffLine> out = new ArrayList<>();
		int o = Math.max(1, oldStart);
		int n = Math.max(1, newStart);
		int oc = 0;
		int nc = 0;
		for (String r : raw) {
			char ch = r.isEmpty() ? ' ' : r.charAt(0);
			String text = r.isEmpty() ? "" : r.substring(1);
			switch (ch) {
				case '+' -> {
					out.add(new DiffLine(DiffLineKind.ADD, text, null, n++));
					nc++;
				}
				case '-' -> {
					out.add(new DiffLine(DiffLineKind.DEL, text, o++, null));
					oc++;
				}
				default -> {
					out.add(new DiffLine(DiffLineKind.CTX, text, o++, n++));
					oc++;
					nc++;
				}
			}
		}
		int os = oldStart == 0 && oc == 0 ? 0 : Math.max(1, oldStart);
		int ns = newStart == 0 && nc == 0 ? 0 : Math.max(1, newStart);
		String header = "@@ -" + os + "," + oc + " +" + ns + "," + nc + " @@" + (oc > 2 ? " export function" : "");
		return new DiffHunk(header, os, oc, ns, nc, out);
	}
}
