import * as React from "react";
import { Check, TriangleAlert } from "lucide-react";
import type { PatchEntry } from "@/lib/native";
import { useI18n } from "@/lib/i18n";
import { ago, cn } from "@/lib/utils";

export function Stat({ add, del }: { add: number; del: number }) {
  return (
    <span className="num inline-flex gap-1.5">
      <span className="text-add">+{add}</span>
      <span className="text-del">−{del}</span>
    </span>
  );
}

export const PatchCard = React.memo(function PatchCard({ patch, onOpen }: { patch: PatchEntry; onOpen: (id: string) => void }) {
  const { t } = useI18n();
  const last = patch.pushes[patch.pushes.length - 1];
  const showName = patch.name && patch.name.replace(/\.(patch|diff)$/i, "") !== patch.title;
  return (
    <button
      onClick={() => onOpen(patch.id)}
      className="block w-full rounded-2xl border border-line bg-card p-4 text-left transition active:scale-[0.99] active:bg-subtle/60"
    >
      <div className="clamp-2 text-[15px] font-semibold leading-snug">{patch.title}</div>
      {showName && <div className="code mt-1 truncate text-[12px] text-muted">{patch.name}</div>}
      <div className="num mt-3 flex items-center gap-3 text-[12px] text-muted">
        <Stat add={patch.additions} del={patch.deletions} />
        {patch.files > 1 && <span>{t("nFiles", { n: patch.files })}</span>}
        {patch.commits > 1 && <span>{t("nCommits", { n: patch.commits })}</span>}
        <span className="flex-1" />
        {last ? (
          <span className={cn("flex min-w-0 items-center gap-1", patch.missed ? "text-del" : "text-ink/80")}>
            {patch.missed ? <TriangleAlert className="h-3.5 w-3.5 shrink-0" /> : <Check className="h-3.5 w-3.5 shrink-0" />}
            <span className="truncate">{last.repo.split("/")[1]}:{last.branch}</span>
          </span>
        ) : (
          <span className="shrink-0">{ago(patch.importedAt, t)}</span>
        )}
      </div>
    </button>
  );
});
