import * as React from "react";
import { ArrowUp, Check, ChevronLeft, ChevronRight, CircleSlash, ExternalLink, GitPullRequest, Trash2, TriangleAlert, WrapText } from "lucide-react";
import type { DiffCommit, DiffFile, PatchDetail, PushRecord, Row } from "@/lib/native";
import { useI18n, type Key } from "@/lib/i18n";
import { ago, cn, date, size, splitPath } from "@/lib/utils";
import { Button, IconButton } from "./ui";
import { Stat } from "./PatchCard";

const EXPAND_ALL_LINES = 1500;
const FILE_PAGE = 600;

export function PatchView({
  patch,
  wrap,
  onWrap,
  onBack,
  onPush,
  onPushMissed,
  onDelete,
  onOpenUrl,
}: {
  patch: PatchDetail;
  wrap: boolean;
  onWrap: () => void;
  onBack: () => void;
  onPush: () => void;
  onPushMissed: () => void;
  onDelete: () => void;
  onOpenUrl: (url: string) => void;
}) {
  const { t } = useI18n();
  const { commits, files } = patch.detail;
  const multi = commits.length > 1;
  const totalLines = files.reduce((n, f) => n + f.hunks.reduce((m, h) => m + h.lines.length, 0), 0);
  const expand = totalLines <= EXPAND_ALL_LINES;
  const last = patch.pushes[patch.pushes.length - 1];
  // The first subject is the page heading; the bar shows it only after it scrolls out of view.
  const heading = React.useRef<HTMLHeadingElement>(null);
  const scroller = React.useRef<HTMLDivElement>(null);
  const [barTitle, setBarTitle] = React.useState(!commits[0]?.subject);
  React.useEffect(() => {
    const el = heading.current;
    if (!el || !scroller.current) { setBarTitle(true); return; }
    const io = new IntersectionObserver((e) => setBarTitle(!e[0].isIntersecting), {
      root: scroller.current,
      rootMargin: "-64px 0px 0px 0px",
    });
    io.observe(el);
    return () => io.disconnect();
  }, [patch.id]);

  return (
    <div ref={scroller} className="fixed inset-0 z-30 animate-page-in overflow-y-auto bg-bg">
      <div className="sticky top-0 z-10 bg-bg/95 pt-safe backdrop-blur">
        <div className="flex h-14 items-center gap-1 px-2">
          <IconButton label={t("back")} onClick={onBack}>
            <ChevronLeft className="h-5 w-5" />
          </IconButton>
          <div className={cn("min-w-0 flex-1 truncate text-[16px] font-semibold transition-opacity duration-150", barTitle ? "opacity-100" : "opacity-0")}>
            {patch.title}
          </div>
          <IconButton label={t("wrap")} aria-pressed={wrap} onClick={onWrap} className={wrap ? "" : "text-muted"}>
            <WrapText className="h-[19px] w-[19px]" />
          </IconButton>
          <IconButton label={t("delete")} onClick={onDelete}>
            <Trash2 className="h-[18px] w-[18px]" />
          </IconButton>
        </div>
      </div>

      <div className="px-4 pt-1" style={{ paddingBottom: "calc(var(--sab) + 96px)" }}>
        {last && (
          <Pushed push={last} missed={files.filter((f) => f.missed).map((f) => splitPath(f.path).base)} onOpenUrl={onOpenUrl} onPushMissed={onPushMissed} />
        )}
        {commits.map((c, i) => (
          <section key={i} className="mb-6">
            <CommitHead commit={c} index={i} total={commits.length} multi={multi} files={c.files.length} headingRef={i === 0 ? heading : undefined} />
            <div className="space-y-2.5">
              {c.files.map((fi) => (
                <FileCard key={fi} file={files[fi]} wrap={wrap} defaultOpen={expand} />
              ))}
            </div>
          </section>
        ))}
      </div>

      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-bg/95 px-4 pt-3 pb-safe-sheet backdrop-blur">
        <Button variant="primary" size="lg" className="w-full" onClick={onPush}>
          <ArrowUp className="h-4 w-4" /> Push
        </Button>
      </div>
    </div>
  );
}

function Pushed({ push, missed, onOpenUrl, onPushMissed }: {
  push: PushRecord; missed: string[]; onOpenUrl: (u: string) => void; onPushMissed: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mb-4 overflow-hidden rounded-xl border border-line bg-card text-[13px]">
      <button onClick={() => onOpenUrl(push.url)} className="flex w-full items-center gap-2 px-3.5 py-2.5 text-left active:bg-subtle">
        {missed.length ? <TriangleAlert className="h-4 w-4 shrink-0 text-del" /> : <Check className="h-4 w-4 shrink-0" />}
        <span className="min-w-0 flex-1 truncate">{push.repo} · {push.branch}</span>
        <span className="shrink-0 text-[12px] text-muted">{ago(push.at, t)}</span>
        {push.created ? <GitPullRequest className="h-3.5 w-3.5 shrink-0 text-muted" /> : <ExternalLink className="h-3.5 w-3.5 shrink-0 text-muted" />}
      </button>
      {missed.length > 0 && (
        <div className="flex items-center gap-3 border-t border-line py-2 pl-[38px] pr-2">
          <span className="min-w-0 flex-1 text-[12.5px] leading-snug text-del">{t("notPushed", { files: [...new Set(missed)].join(", ") })}</span>
          <Button size="sm" onClick={onPushMissed}><ArrowUp className="h-3.5 w-3.5" /> Push</Button>
        </div>
      )}
    </div>
  );
}

function CommitHead({ commit: c, index, total, multi, files, headingRef }: {
  commit: DiffCommit; index: number; total: number; multi: boolean; files: number; headingRef?: React.Ref<HTMLHeadingElement>;
}) {
  const { t } = useI18n();
  const [more, setMore] = React.useState(false);
  const long = c.body.split("\n").length > 4 || c.body.length > 280;
  return (
    <div className="mb-3 px-1">
      {multi && <div className="num mb-0.5 text-[12px] text-muted">{index + 1}/{total}</div>}
      {c.subject && <h2 ref={headingRef} className="selectable text-[18px] font-semibold leading-snug tracking-tight">{c.subject}</h2>}
      {c.body && (
        <div className="mt-1.5">
          <p className={cn("selectable whitespace-pre-wrap text-[13.5px] leading-relaxed text-ink/80", !more && long && "clamp-4")}>{c.body}</p>
          {long && !more && (
            <button className="mt-1 text-[12.5px] text-muted active:text-ink" onClick={() => setMore(true)}>{t("showMoreBody")}</button>
          )}
        </div>
      )}
      <div className="num mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-muted">
        <Stat add={c.additions} del={c.deletions} />
        {files > 1 && <span>{t("nFiles", { n: files })}</span>}
        {c.author && <span className="truncate">{c.author}</span>}
        {c.date && <span>{date(c.date)}</span>}
      </div>
    </div>
  );
}

/** Renames and copies show "old →" under the name instead of a badge. */
const STATUS: Partial<Record<DiffFile["status"], Key>> = {
  added: "st_added",
  deleted: "st_deleted",
};

const FileCard = React.memo(function FileCard({ file, wrap, defaultOpen }: { file: DiffFile; wrap: boolean; defaultOpen: boolean }) {
  const { t } = useI18n();
  const [open, setOpen] = React.useState(defaultOpen);
  const { dir, base } = splitPath(file.path);
  const status = STATUS[file.status];
  const moved = (file.status === "renamed" || file.status === "copied") && file.oldPath && file.oldPath !== file.path;
  return (
    <section className="overflow-hidden rounded-2xl border border-line bg-card">
      <button onClick={() => setOpen(!open)} className="flex w-full items-center gap-2 px-3 py-3 text-left active:bg-subtle">
        <ChevronRight className={cn("h-4 w-4 shrink-0 text-muted transition-transform", open && "rotate-90")} />
        <div className="min-w-0 flex-1">
          <div className="flex min-w-0 text-[13.5px] leading-tight">
            <span className="min-w-0 truncate text-muted">{dir}</span>
            <span className="shrink-0 font-semibold">{base}</span>
          </div>
          {moved && <div className="mt-0.5 truncate text-[11.5px] text-muted">{file.oldPath} →</div>}
        </div>
        {file.missed ? <span className="shrink-0 rounded-md bg-del-bg px-1.5 py-[2px] text-[11px] text-del">{t("missed")}</span> : null}
        {status && <span className="shrink-0 rounded-md bg-subtle px-1.5 py-[2px] text-[11px] text-muted">{t(status)}</span>}
        {file.additions + file.deletions > 0 && (
          <span className="shrink-0 text-[12px]">
            <Stat add={file.additions} del={file.deletions} />
          </span>
        )}
      </button>
      {open && <FileBody file={file} wrap={wrap} />}
    </section>
  );
});

function FileBody({ file, wrap }: { file: DiffFile; wrap: boolean }) {
  const { t } = useI18n();
  const [limit, setLimit] = React.useState(FILE_PAGE);
  const mode = file.oldMode && file.newMode && file.oldMode !== file.newMode ? `${file.oldMode} → ${file.newMode}` : "";
  const note = file.truncated ? t("tooLarge")
    : file.submodule ? t("submodule")
    : file.binary ? t("binary") + (file.binarySize >= 0 ? ` · ${size(file.binarySize)}` : "")
    : "";
  const maxNo = file.hunks.reduce((m, h) => {
    const l = h.lines[h.lines.length - 1];
    return l ? Math.max(m, l[1], l[2]) : m;
  }, 0);
  const gutter = `${Math.max(2, String(maxNo).length) + 1}ch`;

  let shown = 0;
  const total = file.hunks.reduce((n, h) => n + h.lines.length, 0);
  return (
    <div className="border-t border-line">
      {mode && <div className="code px-4 py-2 text-[12px] text-muted">{mode}</div>}
      {note && <p className="px-4 py-3 text-[12.5px] text-muted">{note}</p>}
      {file.hunks.length > 0 && (
        <div className={cn("code text-[12px] leading-[1.6]", !wrap && "no-scrollbar overflow-x-auto")}>
          <div className={cn(!wrap && "min-w-max")}>
            {file.hunks.map((h, hi) => {
              if (shown >= limit) return null;
              const rows = h.lines.slice(0, Math.max(0, limit - shown));
              shown += rows.length;
              return (
                <React.Fragment key={hi}>
                  <div className={cn("bg-subtle px-3 py-1 text-[11.5px] text-muted", wrap ? "truncate" : "whitespace-pre")}>
                    {h.header}{h.section && <span className="ml-2">{h.section}</span>}
                  </div>
                  {rows.map((r, i) => <Line key={i} row={r} wrap={wrap} gutter={gutter} />)}
                </React.Fragment>
              );
            })}
          </div>
        </div>
      )}
      {total > limit && (
        <button onClick={() => setLimit((l) => l + FILE_PAGE * 2)} className="w-full border-t border-line py-2.5 text-[12.5px] text-muted active:bg-subtle">
          {t("showMore", { n: total - limit })}
        </button>
      )}
    </div>
  );
}

function Line({ row, wrap, gutter }: { row: Row; wrap: boolean; gutter: string }) {
  const { t } = useI18n();
  const [kind, oldNo, newNo, text, noEol] = row;
  const add = kind === "+";
  const del = kind === "-";
  const num = "select-none pr-1.5 text-right text-muted/60";
  return (
    <div className={cn("flex", add && "bg-add-bg", del && "bg-del-bg")}>
      {/* Line numbers stay in view while a long line is scrolled sideways. */}
      <span className={cn("sticky left-0 z-[1] flex shrink-0", add ? "bg-add-gut" : del ? "bg-del-gut" : "bg-card")}>
        <span className={num} style={{ width: gutter }}>{oldNo || ""}</span>
        <span className={num} style={{ width: gutter }}>{newNo || ""}</span>
      </span>
      <span className={cn("w-5 shrink-0 select-none text-center", add && "text-add", del && "text-del")}>{add ? "+" : del ? "-" : ""}</span>
      <span className={cn("selectable min-w-0 flex-1 pr-3", wrap ? "whitespace-pre-wrap [overflow-wrap:anywhere]" : "whitespace-pre")}>
        {text}
        {noEol && <CircleSlash aria-label={t("noNewline")} className="ml-1 inline h-3 w-3 align-[-2px] text-muted" />}
      </span>
    </div>
  );
}
