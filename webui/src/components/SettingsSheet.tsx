import * as React from "react";
import type { GhUser, Settings } from "@/lib/native";
import { useI18n } from "@/lib/i18n";
import { ClientIdHelp } from "./Onboarding";
import { Button, Group, Row, Segmented, Sheet } from "./ui";

export function SettingsSheet({
  open,
  onOpenChange,
  settings,
  onChange,
  user,
  version,
  onGithubLogout,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
  settings: Settings;
  onChange: (patch: Partial<Settings>) => void;
  user: GhUser;
  version: string;
  onGithubLogout: () => void;
}) {
  const { t } = useI18n();
  const [cid, setCid] = React.useState(settings.githubClientId);
  const [confirmLogout, setConfirmLogout] = React.useState(false);

  React.useEffect(() => {
    if (open) {
      setCid(settings.githubClientId);
      setConfirmLogout(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <Sheet open={open} onOpenChange={onOpenChange} title={t("settings")}>
      <div className="pt-1">
        <Group title={t("setGeneral")}>
          <Row label={t("setLanguage")} right={
            <Segmented size="sm" className="w-[150px]" value={settings.lang} onChange={(v) => onChange({ lang: v })}
              options={[{ value: "ja", label: "日本語" }, { value: "en", label: "English" }]} />
          } />
          <Row label={t("setAuthor")} right={
            <Segmented size="sm" value={settings.keepAuthor ? "patch" : "me"} onChange={(v) => onChange({ keepAuthor: v === "patch" })}
              options={[{ value: "patch", label: t("authorPatch") }, { value: "me", label: t("authorMe") }]} />
          } />
        </Group>

        <Group title={t("setAccount")}>
          <Row
            label={user.login ? `@${user.login}` : "GitHub"}
            right={
              confirmLogout ? (
                <div className="flex gap-1.5">
                  <Button size="sm" variant="ghost" onClick={() => setConfirmLogout(false)}>{t("keep")}</Button>
                  <Button size="sm" variant="danger" onClick={onGithubLogout}>{t("signOut")}</Button>
                </div>
              ) : (
                <Button size="sm" variant="secondary" onClick={() => setConfirmLogout(true)}>{t("signOut")}</Button>
              )
            }
          />
        </Group>


        <Group title="GitHub OAuth App">
          <div className="px-4 py-3">
            <div className="flex gap-2">
              <input
                value={cid}
                onChange={(e) => setCid(e.target.value.trim())}
                placeholder="Client ID"
                autoCapitalize="off"
                autoCorrect="off"
                spellCheck={false}
                className="h-10 min-w-0 flex-1 rounded-xl border border-line bg-bg px-3 font-mono text-[14px] outline-none focus:border-ink"
              />
              <Button variant="secondary" disabled={!cid || cid === settings.githubClientId} onClick={() => onChange({ githubClientId: cid })}>{t("save")}</Button>
            </div>
            <ClientIdHelp />
          </div>
        </Group>

        <Group title={t("setAbout")}>
          <Row label={t("setAppVersion")} right={<span className="num text-[14px] text-muted">{version}</span>} />
          <div className="px-4 py-3 text-[12px] leading-relaxed text-muted">MIT License</div>
        </Group>
      </div>
    </Sheet>
  );
}
