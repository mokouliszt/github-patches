import * as React from "react";
import { AlertCircle, Check, ChevronLeft, ChevronRight, GitBranchPlus, Lock, RefreshCw, Search, Shield, X } from "lucide-react";
import {
  native, newReq, on,
  type Branch, type CheckReport, type FileCheck, type PatchDetail, type PushRecord, type Recent, type Repo, type Target,
} from "@/lib/native";
import { useI18n, type Key, type T } from "@/lib/i18n";
import { cn, suggestBranch, validBranch } from "@/lib/utils";
import { Button, IconButton, Segmented, Sheet, Spinner, Switch } from "./ui";

export type PushView = "main" | "repo" | "branch";

type CheckState =
  | { s: "idle" }
  | { s: "running" }
  | { s: "done"; report: CheckReport }
  | { s: "error"; message: string };

interface Branches { items: Branch[]; def: string; loading: boolean; error: string }

const REPOS_STALE_MS = 10 * 60 * 1000;

export function PushSheet({
  open,
  onOpenChange,
  view,
  setView,
  patch,
  onlyMissed,
  repos,
  reposLoading,
  recent,
  onLoadRepos,
  onPushed,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  view: PushView;
  setView: (v: PushView) => void;
  patch: PatchDetail | null;
  /** Only the hunks 1.0.0 left out of the last push, as one commit. */
  onlyMissed: boolean;
  repos: { at: number; items: Repo[] };
  reposLoading: boolean;
  recent: Recent[];
  onLoadRepos: (force: boolean) => void;
  /** Closes the sheet; the result is announced outside it. */
  onPushed: (push: PushRecord) => void;
}) {
  const { t } = useI18n();
  const [target, setTarget] = React.useState<Target | null>(null);
  const [branches, setBranches] = React.useState<Record<string, Branches>>({});
  const [check, setCheck] = React.useState<CheckState>({ s: "idle" });
  const [messages, setMessages] = React.useState<string[]>([]);
  const [squash, setSquash] = React.useState(false);
  const [squashMessage, setSquashMessage] = React.useState("");
  const [pushing, setPushing] = React.useState(false);
  const [pushError, setPushError] = React.useState("");
  const [query, setQuery] = React.useState("");
  const [mode, setMode] = React.useState<"existing" | "new">("existing");
  const [newName, setNewName] = React.useState("");
  const [newBase, setNewBase] = React.useState("");

  const checkReq = React.useRef<string | null>(null);
  const onPushedRef = React.useRef(onPushed);
  onPushedRef.current = onPushed;
  const pushReq = React.useRef<string | null>(null);
  const branchReqs = React.useRef(new Map<string, string>());

  const allCommits = patch?.detail.commits ?? [];
  // Left-out hunks go up as one commit, under the messages of the commits they came from.
  const commits = onlyMissed && patch
    ? allCommits.filter((c) => c.files.some((fi) => patch.detail.files[fi]?.missed)).slice(0, 1)
    : allCommits;
  const missedMessage = onlyMissed && patch
    ? allCommits.filter((c) => c.files.some((fi) => patch.detail.files[fi]?.missed)).map((c) => c.message).filter(Boolean).join("\n\n")
    : "";
  const repoByName = React.useMemo(() => new Map(repos.items.map((r) => [r.fullName, r])), [repos.items]);

  const loadBranches = React.useCallback((repo: string) => {
    const id = newReq("branches");
    branchReqs.current.set(id, repo);
    setBranches((b) => ({ ...b, [repo]: { items: b[repo]?.items ?? [], def: b[repo]?.def ?? "", loading: true, error: "" } }));
    native.branches(id, repo);
  }, []);

  // ------------------------------------------------------------ open / reset
  React.useEffect(() => {
    if (!open || !patch) return;
    setView("main");
    setPushing(false);
    setPushError("");
    setSquash(false);
    setQuery("");
    setMessages(onlyMissed ? [missedMessage] : patch.detail.commits.map((c) => c.message));
    setSquashMessage(patch.detail.commits.map((c) => c.message).filter(Boolean).join("\n\n"));
    const last = patch.pushes[patch.pushes.length - 1];
    let initial: Target | null = null;
    if (last) initial = { repo: last.repo, branch: last.branch, create: false, base: "" };
    else if (patch.repo && repoByName.has(patch.repo)) initial = { repo: patch.repo, branch: repoByName.get(patch.repo)!.defaultBranch, create: false, base: "" };
    else if (recent[0]) initial = { repo: recent[0].repo, branch: recent[0].branch, create: false, base: "" };
    setTarget(initial);
    if (initial) loadBranches(initial.repo);
    if (!repos.items.length || Date.now() - repos.at > REPOS_STALE_MS) onLoadRepos(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, patch?.id, onlyMissed]);

  // A patch fetched from a GitHub URL targets that repository once the list has loaded.
  React.useEffect(() => {
    if (!open || !patch || target || !patch.repo) return;
    const r = repoByName.get(patch.repo);
    if (r) {
      setTarget({ repo: r.fullName, branch: r.defaultBranch, create: false, base: "" });
      loadBranches(r.fullName);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, repoByName]);

  // ------------------------------------------------------------ check whenever the target changes
  const targetKey = target ? JSON.stringify(target) : "";
  React.useEffect(() => {
    if (checkReq.current) native.cancel(checkReq.current);
    checkReq.current = null;
    if (!open || !patch || !target || !target.branch || (target.create && !target.base)) {
      setCheck({ s: "idle" });
      return;
    }
    const id = newReq("check");
    checkReq.current = id;
    setCheck({ s: "running" });
    setPushError("");
    native.check(id, patch.id, { ...target, onlyMissed });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, patch?.id, targetKey, onlyMissed]);

  // ------------------------------------------------------------ native events
  React.useEffect(() => {
    const offs = [
      on("check", (p) => { if (p.reqId === checkReq.current) setCheck({ s: "done", report: p.report }); }),
      on("branches", (p) => {
        const repo = branchReqs.current.get(p.reqId);
        if (!repo) return;
        setBranches((b) => ({ ...b, [repo]: { items: p.items, def: p.defaultBranch, loading: false, error: "" } }));
      }),
      on("pushed", (p) => {
        if (p.reqId !== pushReq.current) return;
        native.haptic("confirm");
        onPushedRef.current(p.push);
      }),
      on("error", (p) => {
        if (p.reqId === checkReq.current) setCheck({ s: "error", message: p.message });
        if (p.reqId === pushReq.current) {
          setPushError(p.message);
          if (p.report) setCheck({ s: "done", report: p.report });
          native.haptic("reject");
        }
        const repo = branchReqs.current.get(p.reqId);
        if (repo) setBranches((b) => ({ ...b, [repo]: { items: b[repo]?.items ?? [], def: b[repo]?.def ?? "", loading: false, error: p.message } }));
      }),
      on("done", (p) => {
        if (p.reqId === pushReq.current) { pushReq.current = null; setPushing(false); }
        if (branchReqs.current.has(p.reqId)) {
          const repo = branchReqs.current.get(p.reqId)!;
          branchReqs.current.delete(p.reqId);
          setBranches((b) => (b[repo]?.loading ? { ...b, [repo]: { ...b[repo], loading: false } } : b));
        }
      }),
    ];
    return () => offs.forEach((f) => f());
  }, [setView]);

  if (!patch) return null;

  const br = target ? branches[target.repo] : undefined;
  const sortedBranches = sortBranches(br?.items ?? [], br?.def || repoByName.get(target?.repo ?? "")?.defaultBranch || "");
  const report = check.s === "done" ? check.report : null;
  const finalMessages = squash ? [squashMessage] : messages;
  const canPush = !!target && !!report?.ok && !pushing && finalMessages.every((m) => m.trim().length > 0);

  const doPush = () => {
    if (!target || !canPush) return;
    const id = newReq("push");
    pushReq.current = id;
    setPushing(true);
    setPushError("");
    native.push(id, patch.id, { ...target, onlyMissed }, finalMessages, onlyMissed || (squash && commits.length > 1), report?.head ?? "");
  };

  const chooseRepo = (r: Repo) => {
    setTarget({ repo: r.fullName, branch: r.defaultBranch, create: false, base: "" });
    if (!branches[r.fullName]?.items.length) loadBranches(r.fullName);
    setQuery("");
    setView("main");
  };

  const openBranch = () => {
    if (!target) return;
    setMode(target.create ? "new" : "existing");
    setNewName(target.create ? target.branch : suggestBranch(patch.title));
    setNewBase(target.create ? target.base : br?.def || repoByName.get(target.repo)?.defaultBranch || target.branch);
    setQuery("");
    if (!br || (!br.items.length && !br.loading)) loadBranches(target.repo);
    setView("branch");
  };

  const back = (
    <button onClick={() => { setQuery(""); setView("main"); }} className="mb-3 -ml-1 flex items-center gap-1 text-[14px] text-muted active:text-ink">
      <ChevronLeft className="h-4 w-4" /> Push
    </button>
  );

  const nameTaken = mode === "new" && sortedBranches.some((b) => b.name === newName);
  const newValid = validBranch(newName) && !nameTaken && !!newBase;

  let footer: React.ReactNode = undefined;
  if (view === "main") {
    footer = (
      <Button variant="primary" size="lg" className="w-full" disabled={!canPush} loading={pushing} onClick={doPush}>
        Push
      </Button>
    );
  } else if (view === "branch" && mode === "new") {
    footer = (
      <Button variant="primary" size="lg" className="w-full" disabled={!newValid}
        onClick={() => { setTarget({ repo: target!.repo, branch: newName, create: true, base: newBase }); setView("main"); }}>
        {t("ok")}
      </Button>
    );
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={view === "main" ? (onlyMissed ? t("pushMissed") : "Push") : undefined} footer={footer}>
      {view === "main" && (
        <div className="pt-1">
          <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">
            <TargetRow label={t("repository")} value={target?.repo ?? t("choose")} muted={!target} onClick={() => setView("repo")} />
            <TargetRow
              label={t("branch")}
              value={target?.branch ?? ""}
              isNew={!!target?.create}
              newLabel={t("newBranch")}
              onClick={openBranch}
              disabled={!target}
            />
          </div>

          <CheckLine check={check} />
          {pushError && (
            <div className="-mt-2 mb-5 flex items-start gap-2 px-1 text-[13px] text-del">
              <AlertCircle className="mt-[1px] h-4 w-4 shrink-0" />
              <span className="leading-snug">{pushError}</span>
            </div>
          )}

          {commits.length > 1 && (
            <label className="mb-4 flex items-center justify-between gap-3 px-1">
              <span className="text-[14px]">{t("squash")}</span>
              <Switch checked={squash} onCheckedChange={setSquash} />
            </label>
          )}
          {squash || commits.length === 1 ? (
            <Message
              value={squash ? squashMessage : messages[0] ?? ""}
              onChange={(v) => (squash ? setSquashMessage(v) : setMessages([v]))}
            />
          ) : (
            commits.map((_, i) => (
              <Message
                key={i}
                label={t("messageN", { i: i + 1 })}
                value={messages[i] ?? ""}
                onChange={(v) => setMessages((m) => m.map((x, j) => (j === i ? v : x)))}
                rows={3}
              />
            ))
          )}
        </div>
      )}

      {view === "repo" && (
        <div>
          {back}
          <div className="mb-4 flex items-center gap-1.5">
            <SearchBox value={query} onChange={setQuery} placeholder={t("searchRepos")} />
            <IconButton label="reload" onClick={() => onLoadRepos(true)} disabled={reposLoading}>
              <RefreshCw className={cn("h-[18px] w-[18px]", reposLoading && "animate-spin")} />
            </IconButton>
          </div>
          <RepoList
            repos={repos.items}
            recent={recent}
            query={query}
            selected={target?.repo}
            loading={reposLoading}
            onPick={chooseRepo}
            t={t}
          />
        </div>
      )}

      {view === "branch" && target && (
        <div>
          {back}
          <Segmented
            className="mb-4"
            value={mode}
            onChange={setMode}
            options={[{ value: "existing", label: t("existing") }, { value: "new", label: t("newBranch") }]}
          />
          {mode === "new" && (
            <div className="mb-5">
              <div className="mb-1.5 px-1 text-[12px] font-medium text-muted">{t("branchName")}</div>
              <input
                value={newName}
                onChange={(e) => setNewName(e.target.value.trim())}
                autoCapitalize="off"
                autoCorrect="off"
                spellCheck={false}
                className={cn(
                  "code h-11 w-full rounded-xl border bg-bg px-3 text-[14px] outline-none focus:border-ink",
                  (newName && !validBranch(newName)) || nameTaken ? "border-del" : "border-line",
                )}
              />
              <div className="mb-1.5 mt-5 px-1 text-[12px] font-medium text-muted">{t("baseBranch")}</div>
            </div>
          )}
          {sortedBranches.length > 10 && (
            <div className="mb-3">
              <SearchBox value={query} onChange={setQuery} placeholder={t("searchBranches")} />
            </div>
          )}
          <BranchList
            items={sortedBranches.filter((b) => !query || b.name.toLowerCase().includes(query.toLowerCase()))}
            def={br?.def || repoByName.get(target.repo)?.defaultBranch || ""}
            selected={mode === "new" ? newBase : target.create ? "" : target.branch}
            loading={!!br?.loading}
            error={br?.error ?? ""}
            onPick={(name) => {
              if (mode === "new") { setNewBase(name); return; }
              setTarget({ repo: target.repo, branch: name, create: false, base: "" });
              setQuery("");
              setView("main");
            }}
            t={t}
          />
        </div>
      )}

    </Sheet>
  );
}

function sortBranches(items: Branch[], def: string): Branch[] {
  const d = items.find((b) => b.name === def);
  return d ? [d, ...items.filter((b) => b !== d)] : items;
}

function TargetRow({ label, value, isNew, newLabel, muted, onClick, disabled }: {
  label: string; value: string; isNew?: boolean; newLabel?: string; muted?: boolean; onClick: () => void; disabled?: boolean;
}) {
  return (
    <button disabled={disabled} onClick={onClick} className="flex min-h-[52px] w-full items-center gap-3 px-4 py-3 text-left active:bg-subtle disabled:opacity-40">
      <span className="shrink-0 text-[15px]">{label}</span>
      <span className="flex min-w-0 flex-1 items-center justify-end gap-2">
        {isNew && <GitBranchPlus className="h-4 w-4 shrink-0 text-muted" aria-label={newLabel} />}
        <span className={cn("min-w-0 truncate text-[14px]", muted ? "text-muted" : "text-ink/80")}>{value}</span>
      </span>
      <ChevronRight className="h-4 w-4 shrink-0 text-muted" />
    </button>
  );
}

function reasonText(t: T, f: FileCheck): string {
  const key = `r_${f.reason}` as Key;
  return f.reason ? t(key, { hunk: f.hunk }) : "";
}

function CheckLine({ check }: { check: CheckState }) {
  const { t } = useI18n();
  if (check.s === "idle") return <div className="mb-5" />;
  if (check.s === "running") {
    return (
      <div className="mb-5 mt-3 flex items-center gap-2 px-1 text-[13px] text-muted">
        <Spinner className="h-3.5 w-3.5" /> {t("checking")}
      </div>
    );
  }
  if (check.s === "error") {
    return (
      <div className="mb-5 mt-3 flex items-start gap-2 px-1 text-[13px] text-del">
        <AlertCircle className="mt-[1px] h-4 w-4 shrink-0" />
        <span className="leading-snug">{check.message}</span>
      </div>
    );
  }
  const r = check.report;
  const fails = r.files.filter((f) => f.outcome === "fail");
  if (fails.length) {
    return (
      <div className="mb-5 mt-3 px-1">
        <div className="flex items-center gap-2 text-[13px] text-del">
          <X className="h-4 w-4 shrink-0" /> {t("checkFail")}
        </div>
        <ul className="mt-2 space-y-1.5">
          {fails.map((f, i) => (
            <li key={i} className="text-[12.5px] leading-snug">
              <span className="code break-all">{f.path}</span>
              <span className="text-muted"> — {reasonText(t, f)}</span>
            </li>
          ))}
        </ul>
      </div>
    );
  }
  const offset = r.files.filter((f) => f.outcome === "offset").length;
  const loose = r.files.filter((f) => f.outcome === "loose").length;
  return (
    <div className="mb-5 mt-3 flex flex-wrap items-center gap-x-2 gap-y-1 px-1 text-[13px]">
      <Check className="h-4 w-4 shrink-0 text-add" />
      <span>{t("checkOk")}</span>
      {offset > 0 && <span className="text-muted">· {t("checkOffset", { n: offset })}</span>}
      {loose > 0 && <span className="text-muted">· {t("checkLoose", { n: loose })}</span>}
    </div>
  );
}

function Message({ label, value, onChange, rows = 4 }: { label?: string; value: string; onChange: (v: string) => void; rows?: number }) {
  const { t } = useI18n();
  return (
    <div className="mb-4">
      {label && <div className="mb-1.5 px-1 text-[12px] font-medium text-muted">{label}</div>}
      <textarea
        value={value}
        onChange={(e) => onChange(e.target.value)}
        rows={rows}
        placeholder={t("messagePlaceholder")}
        className="block w-full resize-none rounded-xl border border-line bg-bg px-3 py-2.5 text-[14px] leading-relaxed outline-none placeholder:text-muted focus:border-ink"
      />
    </div>
  );
}

function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <div className="flex h-11 min-w-0 flex-1 items-center gap-2 rounded-xl bg-subtle px-3">
      <Search className="h-4 w-4 shrink-0 text-muted" />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        enterKeyHint="search"
        className="h-full min-w-0 flex-1 bg-transparent text-[15px] outline-none placeholder:text-muted"
      />
      {value && (
        <button aria-label="clear" onClick={() => onChange("")} className="rounded-full p-1 text-muted active:text-ink">
          <X className="h-4 w-4" />
        </button>
      )}
    </div>
  );
}

function RepoList({
  repos, recent, query, selected, loading, onPick, t,
}: {
  repos: Repo[]; recent: Recent[]; query: string; selected?: string; loading: boolean; onPick: (r: Repo) => void; t: T;
}) {
  const q = query.trim().toLowerCase();
  const byName = new Map(repos.map((r) => [r.fullName, r]));
  const recentRepos = q ? [] : recent.map((x) => byName.get(x.repo)).filter((r): r is Repo => !!r);
  const shown = new Set(recentRepos.map((r) => r.fullName));
  const rest = repos.filter((r) => !shown.has(r.fullName) && (!q || r.fullName.toLowerCase().includes(q)));

  if (!repos.length) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-[14px] text-muted">
        {loading ? <Spinner /> : t("noRepos")}
      </div>
    );
  }
  const row = (r: Repo) => (
    <button key={r.fullName} onClick={() => onPick(r)} className="flex min-h-[48px] w-full items-center gap-2 px-4 py-2.5 text-left active:bg-subtle">
      <span className="min-w-0 flex-1 truncate text-[14.5px]">
        <span className="text-muted">{r.owner}/</span>
        <span className="font-medium">{r.name}</span>
      </span>
      {r.private && <Lock className="h-3.5 w-3.5 shrink-0 text-muted" />}
      <Check className={cn("h-4 w-4 shrink-0", r.fullName === selected ? "opacity-100" : "opacity-0")} />
    </button>
  );
  return (
    <>
      {recentRepos.length > 0 && (
        <section className="mb-5">
          <h3 className="mb-2 px-1 text-[12px] font-medium text-muted">{t("recent")}</h3>
          <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">{recentRepos.map(row)}</div>
        </section>
      )}
      {rest.length > 0 ? (
        <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">{rest.map(row)}</div>
      ) : (
        q && <p className="py-8 text-center text-[14px] text-muted">{t("noMatch")}</p>
      )}
    </>
  );
}

function BranchList({
  items, def, selected, loading, error, onPick, t,
}: {
  items: Branch[]; def: string; selected: string; loading: boolean; error: string; onPick: (name: string) => void; t: T;
}) {
  if (!items.length) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-[14px] text-muted">
        {loading ? <Spinner /> : error || t("noMatch")}
      </div>
    );
  }
  return (
    <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">
      {items.map((b) => (
        <button key={b.name} onClick={() => onPick(b.name)} className="flex min-h-[48px] w-full items-center gap-2 px-4 py-2.5 text-left active:bg-subtle">
          <span className="code min-w-0 flex-1 truncate text-[13.5px]">{b.name}</span>
          {b.name === def && <span className="shrink-0 rounded-md bg-subtle px-1.5 py-[2px] text-[11px] text-muted">default</span>}
          {b.protected && <Shield className="h-3.5 w-3.5 shrink-0 text-muted" aria-label="protected" />}
          <Check className={cn("h-4 w-4 shrink-0", b.name === selected ? "opacity-100" : "opacity-0")} />
        </button>
      ))}
    </div>
  );
}
