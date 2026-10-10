import * as React from "react";
import { ClipboardPaste, FileText, Link2 } from "lucide-react";
import { useI18n } from "@/lib/i18n";
import { Button, Sheet, Spinner } from "./ui";

export type ImportKind = "file" | "clipboard" | "url";

export function ImportSheet({
  open,
  onOpenChange,
  busy,
  onFile,
  onClipboard,
  onUrl,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  busy: ImportKind | null;
  onFile: () => void;
  onClipboard: () => void;
  onUrl: (url: string) => void;
}) {
  const { t } = useI18n();
  const [url, setUrl] = React.useState("");
  const [showUrl, setShowUrl] = React.useState(false);
  React.useEffect(() => { if (open) { setShowUrl(false); setUrl(""); } }, [open]);
  const valid = /^https?:\/\/\S+$/.test(url.trim());

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t("openPatch")}>
      <div className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-card">
        <Source icon={<FileText className="h-[18px] w-[18px]" />} label={t("importFile")} busy={busy === "file"} disabled={!!busy} onClick={onFile} />
        <Source icon={<ClipboardPaste className="h-[18px] w-[18px]" />} label={t("importClipboard")} busy={busy === "clipboard"} disabled={!!busy} onClick={onClipboard} />
        <Source icon={<Link2 className="h-[18px] w-[18px]" />} label={t("importUrl")} busy={false} disabled={!!busy} onClick={() => setShowUrl(true)} />
        {showUrl && (
          <div className="flex gap-2 px-4 pb-4 pt-3">
            <input
              autoFocus
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && valid) onUrl(url.trim()); }}
              placeholder={t("urlPlaceholder")}
              inputMode="url"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-bg px-3 text-[14px] outline-none placeholder:text-muted focus:border-ink"
            />
            <Button variant="primary" disabled={!valid} loading={busy === "url"} onClick={() => onUrl(url.trim())}>{t("open")}</Button>
          </div>
        )}
      </div>
    </Sheet>
  );
}

function Source({ icon, label, busy, disabled, onClick }: { icon: React.ReactNode; label: string; busy: boolean; disabled: boolean; onClick: () => void }) {
  return (
    <button disabled={disabled} onClick={onClick} className="flex min-h-[52px] w-full items-center gap-3 px-4 py-3 text-left active:bg-subtle disabled:opacity-60">
      <span className="text-muted">{icon}</span>
      <span className="flex-1 text-[15px]">{label}</span>
      {busy && <Spinner />}
    </button>
  );
}
