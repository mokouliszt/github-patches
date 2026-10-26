// Bridge to MainActivity.Bridge. JS → window.Native.*, Native → window.__native(type, payload).

export interface PushRecord {
  repo: string;
  branch: string;
  head: string;
  commits: string[];
  created: boolean;
  base: string;
  /** Commit, compare range, or (new branch) the pull request form. */
  url: string;
  commitUrl: string;
  at: number;
}

export interface PatchEntry {
  id: string;
  name: string;
  title: string;
  importedAt: number;
  source: "share" | "file" | "clipboard" | "url";
  url: string;
  /** owner/name when the patch came from a GitHub URL. */
  repo: string;
  size: number;
  sha1: string;
  commits: number;
  files: number;
  additions: number;
  deletions: number;
  meta: boolean;
  pushes: PushRecord[];
  /** Files the last push left out (pushed by 1.0.0, which could not read some hunks). */
  missed?: number;
}

/** [kind, old line, new line, text, no newline at end] — 0 means "no number". */
export type Row = [" " | "+" | "-", number, number, string, 1?];

export interface DiffHunk { header: string; section: string; lines: Row[] }

export type FileStatus = "modified" | "added" | "deleted" | "renamed" | "copied";

export interface DiffFile {
  commit: number;
  path: string;
  oldPath: string;
  status: FileStatus;
  oldMode: string;
  newMode: string;
  additions: number;
  deletions: number;
  binary: boolean;
  binarySize: number;
  submodule: boolean;
  truncated: boolean;
  encoding?: string;
  hunks: DiffHunk[];
  /** Hunks of this file the last push left out. */
  missed?: number;
}

export interface DiffCommit {
  sha: string;
  subject: string;
  body: string;
  message: string;
  author: string;
  email: string;
  date: string;
  files: number[];
  additions: number;
  deletions: number;
}

export interface PatchDetail extends PatchEntry {
  detail: { commits: DiffCommit[]; files: DiffFile[] };
}

export interface Repo { fullName: string; owner: string; name: string; private: boolean; fork: boolean; defaultBranch: string; pushedAt: string }
export interface Branch { name: string; protected: boolean }
export interface Target { repo: string; branch: string; create: boolean; base: string; onlyMissed?: boolean }
export interface Recent { repo: string; branch: string }

export type Outcome = "ok" | "offset" | "loose" | "fail";
export interface FileCheck { commit: number; path: string; oldPath: string; status: FileStatus; outcome: Outcome; hunk: number; reason: string }
export interface CheckReport { ok: boolean; head: string; files: FileCheck[] }

export interface Settings {
  lang: "ja" | "en";
  githubClientId: string;
  /** Commit author = the patch's author (like git am); otherwise the signed-in user. */
  keepAuthor: boolean;
  wrap: boolean;
}

export interface GhUser { login: string; avatar: string; name: string }

export interface AppState {
  github: { loggedIn: boolean; user: GhUser };
  clientIdSet: boolean;
  settings: Settings;
  patches: PatchEntry[];
  recent: Recent[];
  repos: { at: number; items: Repo[] };
  /** Patch imported before the page was ready (share sheet on a cold start). */
  open: string;
  running: string[];
  version: string;
}

/* eslint-disable @typescript-eslint/no-explicit-any */
declare global {
  interface Window {
    Native?: any;
    __native?: (type: string, payload: any) => void;
  }
}

type Handler = (payload: any) => void;
const listeners = new Map<string, Set<Handler>>();
const STICKY = new Set(["theme", "insets"]);
const sticky = new Map<string, any>();

window.__native = (type: string, payload: any) => {
  if (STICKY.has(type)) sticky.set(type, payload);
  listeners.get(type)?.forEach((h) => {
    try { h(payload); } catch (e) { console.error(e); }
  });
};

export function on(type: string, h: Handler): () => void {
  let set = listeners.get(type);
  if (!set) { set = new Set(); listeners.set(type, set); }
  set.add(h);
  if (sticky.has(type)) {
    try { h(sticky.get(type)); } catch (e) { console.error(e); }
  }
  return () => { set!.delete(h); };
}

let seq = 0;
export const newReq = (prefix: string) => `${prefix}-${Date.now().toString(36)}-${++seq}`;

const N = (): any => window.Native ?? mock;

export const native = {
  state: (): AppState => JSON.parse(N().state()),
  isDark: (): boolean => {
    try { return !!N().isDark(); } catch { return false; }
  },
  saveSettings: (patch: Partial<Settings>): Settings => JSON.parse(N().saveSettings(JSON.stringify(patch))),
  githubLogin: (reqId: string) => N().githubLogin(reqId),
  githubLogout: () => N().githubLogout(),
  pickFile: (reqId: string) => N().pickFile(reqId),
  importClipboard: (reqId: string) => N().importClipboard(reqId),
  importUrl: (reqId: string, url: string) => N().importUrl(reqId, url),
  patch: (id: string): PatchDetail | { error: string } | null => {
    const s: string = N().patch(id);
    return s ? JSON.parse(s) : null;
  },
  deletePatch: (id: string) => N().deletePatch(id),
  repos: (reqId: string, force: boolean) => N().repos(reqId, force),
  branches: (reqId: string, repo: string) => N().branches(reqId, repo),
  check: (reqId: string, id: string, target: Target) => N().check(reqId, id, JSON.stringify(target)),
  /** [checkedHead]: the tip the shown check ran on. */
  push: (reqId: string, id: string, target: Target, messages: string[], squash: boolean, checkedHead: string) =>
    N().push(reqId, id, JSON.stringify(target), JSON.stringify(messages), squash, checkedHead),
  cancel: (reqId: string) => N().cancel(reqId),
  openUrl: (url: string) => N().openUrl(url),
  copy: (text: string) => N().copy(text),
  share: (text: string) => N().share(text),
  haptic: (kind: "tick" | "confirm" | "reject" = "tick") => N().haptic(kind),
};

// ---------------------------------------------------------------------------
// Browser preview only (vite dev / screenshots). Never used inside the app.
const emit = (type: string, payload: any, ms = 0) => setTimeout(() => window.__native?.(type, payload), ms);
const flag = (f: string) => location.hash.slice(1).split("&").some((x) => x === f || x.startsWith(f + "="));
const now = Date.now();

const ctx = (n: number, from: number, to: number, prefix = ""): Row[] =>
  Array.from({ length: n }, (_, i) => [" ", from + i, to + i, prefix + ["", "    return token", "}", "", "fun refresh() {"][i % 5]] as Row);

const files1: DiffFile[] = [
  {
    commit: 0, path: "app/src/main/java/com/example/auth/TokenStore.kt", oldPath: "app/src/main/java/com/example/auth/TokenStore.kt",
    status: "modified", oldMode: "", newMode: "", additions: 6, deletions: 2, binary: false, binarySize: -1, submodule: false, truncated: false,
    hunks: [
      {
        header: "@@ -41,9 +41,13 @@", section: "class TokenStore(private val prefs: SharedPreferences) {",
        lines: [
          [" ", 41, 41, "    private val lock = Mutex()"],
          [" ", 42, 42, ""],
          [" ", 43, 43, "    suspend fun valid(): String? {"],
          ["-", 44, 0, "        if (expired()) refresh()"],
          ["-", 45, 0, "        return token"],
          ["+", 0, 44, "        lock.withLock {"],
          ["+", 0, 45, "            // Another caller may have refreshed while we waited."],
          ["+", 0, 46, "            if (expired()) refresh()"],
          ["+", 0, 47, "        }"],
          ["+", 0, 48, "        return token"],
          ["+", 0, 49, ""],
          [" ", 46, 50, "    }"],
          [" ", 47, 51, ""],
          [" ", 48, 52, "    private fun expired() = System.currentTimeMillis() > expiresAt - 300_000"],
        ],
      },
    ],
  },
  {
    commit: 0, path: "docs/auth.md", oldPath: "AUTH.md", status: "renamed", oldMode: "", newMode: "", additions: 1, deletions: 1,
    binary: false, binarySize: -1, submodule: false, truncated: false,
    hunks: [{
      header: "@@ -1,4 +1,4 @@", section: "",
      lines: [
        [" ", 1, 1, "# Authentication"],
        [" ", 2, 2, ""],
        ["-", 3, 0, "Tokens are refreshed on demand."],
        ["+", 0, 3, "Tokens are refreshed on demand, one caller at a time."],
        [" ", 4, 4, ""],
      ],
    }],
  },
  {
    commit: 1, path: "app/src/main/java/com/example/net/Http.kt", oldPath: "app/src/main/java/com/example/net/Http.kt",
    status: "modified", oldMode: "", newMode: "", additions: 9, deletions: 1, binary: false, binarySize: -1, submodule: false, truncated: false,
    hunks: [{
      header: "@@ -18,7 +18,15 @@", section: "object Http {",
      lines: [
        [" ", 18, 18, "    suspend fun get(url: String): Response {"],
        [" ", 19, 19, "        val t = tokens.valid() ?: throw SignInRequired()"],
        ["-", 20, 0, "        return client.newCall(request(url, t)).await()"],
        ["+", 0, 20, "        val res = client.newCall(request(url, t)).await()"],
        ["+", 0, 21, "        if (res.code != 401) return res"],
        ["+", 0, 22, "        res.close()"],
        ["+", 0, 23, "        // The token was revoked or rotated elsewhere: refresh once and retry."],
        ["+", 0, 24, "        tokens.invalidate()"],
        ["+", 0, 25, "        val again = tokens.valid() ?: throw SignInRequired()"],
        ["+", 0, 26, "        return client.newCall(request(url, again)).await()"],
        ["+", 0, 27, "    }"],
        ["+", 0, 28, ""],
        [" ", 21, 29, "    }"],
      ],
    }],
  },
  {
    commit: 1, path: "app/src/main/res/drawable/ic_retry.png", oldPath: "", status: "added", oldMode: "", newMode: "100644",
    additions: 0, deletions: 0, binary: true, binarySize: 1834, submodule: false, truncated: false, hunks: [],
  },
  {
    commit: 1, path: "scripts/release.sh", oldPath: "scripts/release.sh", status: "modified", oldMode: "100644", newMode: "100755",
    additions: 0, deletions: 0, binary: false, binarySize: -1, submodule: false, truncated: false, hunks: [],
  },
];

const files2: DiffFile[] = [{
  commit: 0, path: "README.md", oldPath: "README.md", status: "modified", oldMode: "", newMode: "", additions: 2, deletions: 1,
  binary: false, binarySize: -1, submodule: false, truncated: false,
  hunks: [{
    header: "@@ -8,6 +8,7 @@", section: "## Install",
    lines: [
      [" ", 8, 8, "## Install"],
      [" ", 9, 9, ""],
      ["-", 10, 0, "Download the APK from the relases page."],
      ["+", 0, 10, "Download the APK from the releases page."],
      ["+", 0, 11, "Android 8.0 or later is required."],
      [" ", 11, 12, ""],
      [" ", 12, 13, "## Build", 1],
    ],
  }],
}];

const files3: DiffFile[] = [{
  commit: 0, path: "webui/src/components/SettingsSheet.tsx", oldPath: "webui/src/components/SettingsSheet.tsx", status: "modified",
  oldMode: "", newMode: "", additions: 3, deletions: 3, binary: false, binarySize: -1, submodule: false, truncated: false,
  ...(flag("missed") ? { missed: 1 } : {}),
  hunks: [{
    header: "@@ -120,7 +120,7 @@", section: "export function SettingsSheet({",
    lines: [
      ...ctx(2, 120, 120, "  "),
      ["-", 122, 0, '      <Group title={t("setAccount")} className="mb-8">'],
      ["+", 0, 122, '      <Group title={t("setAccount")} className="mb-6">'],
      ["-", 123, 0, '        <Row label={user.login} className="py-4" />'],
      ["+", 0, 123, '        <Row label={user.login} className="py-3" />'],
      ["-", 124, 0, "      </Group>"],
      ["+", 0, 124, "      </Group>  "],
      ...ctx(2, 125, 125, "  "),
    ],
  }],
}];

const entry = (o: Partial<PatchEntry>): PatchEntry => ({
  id: "", name: "", title: "", importedAt: now, source: "share", url: "", repo: "", size: 1000, sha1: "",
  commits: 1, files: 1, additions: 0, deletions: 0, meta: true, pushes: [], ...o,
});

const mockPatches: PatchEntry[] = [
  entry({
    id: "p1", name: "sample-app-pr-42.patch", title: "Serialize token refresh", importedAt: now - 4 * 60e3, source: "url",
    url: "https://github.com/octo-org/sample-app/pull/42", repo: "octo-org/sample-app", commits: 2, files: 5, additions: 16, deletions: 4,
  }),
  entry({ id: "p2", name: "fix-readme.patch", title: "fix-readme", importedAt: now - 3 * 3600e3, source: "file", additions: 2, deletions: 1, meta: false }),
  entry({
    id: "p3", name: "0001-settings-spacing.patch", title: "設定画面の余白を調整", importedAt: now - 2 * 86400e3, additions: 3, deletions: 3,
    ...(flag("missed") ? { missed: 1 } : {}),
    pushes: [{
      repo: "octocat/hello-world", branch: "main", head: "9f2c4e1a7b", commits: ["9f2c4e1a7b"], created: false, base: "",
      url: "https://github.com/octocat/hello-world/commit/9f2c4e1a7b", commitUrl: "https://github.com/octocat/hello-world/commit/9f2c4e1a7b",
      at: now - 2 * 86400e3 + 600e3,
    }],
  }),
];

const details: Record<string, PatchDetail["detail"]> = {
  p1: {
    commits: [
      {
        sha: "1a2b3c4d", subject: "Serialize token refresh", body: "Two requests that hit an expired token at the same time\nboth refreshed it, and the second refresh invalidated the first.",
        message: "Serialize token refresh\n\nTwo requests that hit an expired token at the same time\nboth refreshed it, and the second refresh invalidated the first.",
        author: "Mona Lisa", email: "mona@example.com", date: new Date(now - 26 * 3600e3).toISOString(), files: [0, 1], additions: 7, deletions: 3,
      },
      {
        sha: "5e6f7a8b", subject: "Retry once on 401", body: "", message: "Retry once on 401",
        author: "Mona Lisa", email: "mona@example.com", date: new Date(now - 25 * 3600e3).toISOString(), files: [2, 3, 4], additions: 9, deletions: 1,
      },
    ],
    files: files1,
  },
  p2: {
    commits: [{ sha: "", subject: "", body: "", message: "", author: "", email: "", date: "", files: [0], additions: 2, deletions: 1 }],
    files: files2,
  },
  p3: {
    commits: [{
      sha: "77aa", subject: "設定画面の余白を調整", body: "", message: "設定画面の余白を調整", author: "Octocat", email: "octocat@example.com",
      date: new Date(now - 2 * 86400e3).toISOString(), files: [0], additions: 3, deletions: 3,
    }],
    files: files3,
  },
};

const mockRepos: Repo[] = [
  { fullName: "octo-org/sample-app", owner: "octo-org", name: "sample-app", private: false, fork: false, defaultBranch: "main", pushedAt: "" },
  { fullName: "octocat/hello-world", owner: "octocat", name: "hello-world", private: false, fork: false, defaultBranch: "main", pushedAt: "" },
  { fullName: "octocat/dotfiles", owner: "octocat", name: "dotfiles", private: true, fork: false, defaultBranch: "master", pushedAt: "" },
  { fullName: "octo-org/website", owner: "octo-org", name: "website", private: false, fork: false, defaultBranch: "main", pushedAt: "" },
  { fullName: "octo-org/infra", owner: "octo-org", name: "infra", private: true, fork: false, defaultBranch: "main", pushedAt: "" },
];

let patchesNow = flag("empty") ? [] : mockPatches;

const mock = {
  isDark: () => flag("dark"),
  state: () => JSON.stringify({
    github: { loggedIn: !flag("onboarding"), user: { login: "octocat", avatar: "", name: "" } },
    clientIdSet: !flag("noclient"),
    settings: { lang: flag("en") ? "en" : "ja", githubClientId: "", keepAuthor: true, wrap: true },
    patches: patchesNow,
    recent: [{ repo: "octocat/hello-world", branch: "main" }],
    repos: { at: now, items: mockRepos },
    open: (location.hash.match(/open=(\w+)/) || [])[1] || "",
    running: [],
    version: "1.0.0",
  } satisfies AppState),
  saveSettings: (j: string) => JSON.stringify({ ...JSON.parse(mock.state()).settings, ...JSON.parse(j) }),
  githubLogin: (id: string) => emit("githubCode", { reqId: id, userCode: "WDJB-MJHT", verificationUri: "https://github.com/login/device", expiresIn: 900 }),
  githubLogout: () => {},
  pickFile: (id: string) => emit("done", { reqId: id }, 300),
  importClipboard: (id: string) => {
    emit("error", { reqId: id, kind: "format", message: "パッチとして読み込めませんでした" }, 200);
    emit("done", { reqId: id }, 200);
  },
  importUrl: (id: string) => {
    emit("imported", { reqId: id, entry: mockPatches[0], isNew: false, patches: patchesNow }, 500);
    emit("done", { reqId: id }, 500);
  },
  patch: (id: string) => {
    const e = patchesNow.find((p) => p.id === id);
    return e ? JSON.stringify({ ...e, detail: details[id] }) : "";
  },
  deletePatch: (id: string) => { patchesNow = patchesNow.filter((p) => p.id !== id); },
  repos: (id: string) => { emit("repos", { reqId: id, repos: { at: now, items: mockRepos } }, 400); emit("done", { reqId: id }, 400); },
  branches: (id: string, repo: string) => {
    emit("branches", {
      reqId: id, repo, defaultBranch: "main",
      items: [{ name: "develop", protected: false }, { name: "feature/login", protected: false }, { name: "main", protected: true }, { name: "release/1.2", protected: true }],
    }, 300);
    emit("done", { reqId: id }, 300);
  },
  check: (id: string, pid: string, target: string) => {
    const t: Target = JSON.parse(target);
    const fail = flag("fail");
    const fs = (details[pid]?.files ?? []).map((f, i): FileCheck => ({
      commit: f.commit, path: f.path, oldPath: f.oldPath, status: f.status,
      outcome: fail && i === 0 ? "fail" : !t.create && i === 0 ? "offset" : "ok", hunk: fail && i === 0 ? 1 : 0, reason: fail && i === 0 ? "mismatch" : "",
    }));
    emit("check", { reqId: id, report: { ok: !fail, head: "abc", files: fs } }, 700);
    emit("done", { reqId: id }, 700);
  },
  push: (id: string, pid: string, target: string) => {
    const t: Target = JSON.parse(target);
    const push: PushRecord = {
      repo: t.repo, branch: t.branch, head: "c0ffee1234", commits: ["c0ffee1234"], created: t.create, base: t.base,
      url: `https://github.com/${t.repo}/commit/c0ffee1234`, commitUrl: `https://github.com/${t.repo}/commit/c0ffee1234`, at: Date.now(),
    };
    patchesNow = patchesNow.map((p) => (p.id === pid ? { ...p, pushes: [...p.pushes, push] } : p));
    emit("pushed", { reqId: id, push, entry: patchesNow.find((p) => p.id === pid), patches: patchesNow, recent: [{ repo: t.repo, branch: t.branch }] }, 1200);
    emit("done", { reqId: id }, 1200);
  },
  cancel: () => {},
  openUrl: (u: string) => console.log("open", u),
  copy: () => {},
  share: () => {},
  haptic: () => {},
};
