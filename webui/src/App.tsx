import * as React from "react";
import { Plus, Settings as SettingsIcon } from "lucide-react";
import { native, newReq, on, type AppState, type PatchDetail, type PatchEntry, type PushRecord, type Settings } from "@/lib/native";
import { I18nContext, translate, type Lang, type T } from "@/lib/i18n";
import { ImportSheet, type ImportKind } from "@/components/ImportSheet";
import { Onboarding, type DeviceCode } from "@/components/Onboarding";
import { PatchCard } from "@/components/PatchCard";
import { PatchView } from "@/components/PatchView";
import { PushSheet, type PushView } from "@/components/PushSheet";
import { SettingsSheet } from "@/components/SettingsSheet";
import { Button, IconButton, Sheet, Toasts, type ToastItem } from "@/components/ui";

type SheetName = null | "import" | "settings" | "push" | "delete";

export default function App() {
  const initial = React.useMemo<AppState>(() => native.state(), []);
  const [github, setGithub] = React.useState(initial.github);
  const [clientIdSet, setClientIdSet] = React.useState(initial.clientIdSet);
  const [settings, setSettings] = React.useState<Settings>(initial.settings);
  const [patches, setPatches] = React.useState<PatchEntry[]>(initial.patches);
  const [recent, setRecent] = React.useState(initial.recent);
  const [repos, setRepos] = React.useState(initial.repos);
  const [reposLoading, setReposLoading] = React.useState(false);
  const [openId, setOpenId] = React.useState<string | null>(initial.open || null);
  const [detail, setDetail] = React.useState<PatchDetail | null>(null);
  const [sheet, setSheet] = React.useState<SheetName>(null);
  /** The push sheet sends only what 1.0.0 left out of the last push. */
  const [pushMissed, setPushMissed] = React.useState(false);
  const [pushView, setPushView] = React.useState<PushView>("main");
  const [importBusy, setImportBusy] = React.useState<ImportKind | null>(null);
  const [deviceCode, setDeviceCode] = React.useState<DeviceCode | null>(null);
  const [ghReq, setGhReq] = React.useState<string | null>(null);
  const [toasts, setToasts] = React.useState<ToastItem[]>([]);
  const importReq = React.useRef<string | null>(null);
  const reposReq = React.useRef<string | null>(null);

  const lang: Lang = settings.lang;
  const t: T = React.useCallback((k, v) => translate(lang, k, v), [lang]);
  React.useEffect(() => { document.documentElement.lang = lang; }, [lang]);

  const toast = React.useCallback((text: string, action?: ToastItem["action"]) => {
    const id = Date.now() + Math.random();
    setToasts((x) => [...x.slice(-2), { id, text, action }]);
    setTimeout(() => setToasts((x) => x.filter((y) => y.id !== id)), action ? 7000 : 3600);
  }, []);

  const changeSettings = React.useCallback((patch: Partial<Settings>) => {
    const s = native.saveSettings(patch);
    setSettings(s);
    if ("githubClientId" in patch) setClientIdSet(!!s.githubClientId);
  }, []);

  const onPushed = React.useCallback((push: PushRecord) => {
    setSheet(null);
    toast(t("pushedTo", { branch: push.branch }), {
      label: push.created ? t("createPr") : t("open"),
      run: () => native.openUrl(push.url),
    });
  }, [toast, t]);

  const loadRepos = React.useCallback((force: boolean) => {
    const id = newReq("repos");
    reposReq.current = id;
    setReposLoading(true);
    native.repos(id, force);
  }, []);

  // ------------------------------------------------------------ detail
  React.useEffect(() => {
    if (!openId) { setDetail(null); return; }
    const d = native.patch(openId);
    if (d && !("error" in d)) { setDetail(d); return; }
    if (d) toast(d.error);
    setOpenId(null);
  }, [openId, toast]);

  // ------------------------------------------------------------ native events
  React.useEffect(() => {
    const offs = [
      on("githubCode", (p) => setDeviceCode({ userCode: p.userCode, verificationUri: p.verificationUri })),
      on("githubLoggedIn", (p) => {
        setGithub({ loggedIn: true, user: p.user });
        setDeviceCode(null);
        setGhReq(null);
        native.haptic("confirm");
      }),
      on("imported", (p) => {
        setPatches(p.patches);
        if (p.reqId === importReq.current) { importReq.current = null; setImportBusy(null); }
        setSheet(null);
        setOpenId(p.entry.id);
      }),
      on("repos", (p) => setRepos(p.repos)),
      on("patches", (p) => setPatches(p.patches)),
      on("pushed", (p) => {
        setPatches(p.patches);
        setRecent(p.recent);
        // Reload: a push can clear what an older version left out.
        setDetail((d) => {
          if (!d || !p.entry || d.id !== p.entry.id) return d;
          const fresh = native.patch(d.id);
          return fresh && !("error" in fresh) ? fresh : { ...d, pushes: p.entry.pushes };
        });
      }),
      on("error", (p) => {
        const id: string = p.reqId || "";
        // The push sheet shows its own errors next to the controls.
        if (id.startsWith("check-") || id.startsWith("branches-") || id.startsWith("push-")) return;
        if (p.kind === "login") setGithub((g) => ({ ...g, loggedIn: false }));
        if (id === importReq.current) { importReq.current = null; setImportBusy(null); }
        if (id === reposReq.current) setReposLoading(false);
        toast(p.message);
      }),
      on("done", (p) => {
        const id: string = p.reqId || "";
        if (id === importReq.current) { importReq.current = null; setImportBusy(null); }
        if (id === reposReq.current) { reposReq.current = null; setReposLoading(false); }
        if (id.startsWith("gh-login")) setGhReq(null);
      }),
    ];
    return () => offs.forEach((f) => f());
  }, [toast]);

  // Android back: close what is open first.
  React.useEffect(() => {
    (window as unknown as { __back: () => boolean }).__back = () => {
      if (sheet === "push" && (pushView === "repo" || pushView === "branch")) { setPushView("main"); return true; }
      if (sheet) { setSheet(null); return true; }
      if (openId) { setOpenId(null); return true; }
      return false;
    };
  }, [sheet, pushView, openId]);

  const startImport = (kind: ImportKind, url?: string) => {
    const id = newReq("import");
    importReq.current = id;
    setImportBusy(kind);
    if (kind === "file") native.pickFile(id);
    else if (kind === "clipboard") native.importClipboard(id);
    else native.importUrl(id, url ?? "");
  };

  const ctx = React.useMemo(() => ({ lang, t }), [lang, t]);

  // ------------------------------------------------------------ onboarding
  if (!github.loggedIn) {
    return (
      <I18nContext.Provider value={ctx}>
        <Onboarding
          lang={lang}
          onLang={(l) => changeSettings({ lang: l })}
          clientIdSet={clientIdSet}
          savedClientId={settings.githubClientId}
          code={deviceCode}
          waiting={!!ghReq && !deviceCode}
          onSaveClientId={(v) => changeSettings({ githubClientId: v })}
          onLogin={() => {
            const id = newReq("gh-login");
            setGhReq(id);
            native.githubLogin(id);
          }}
          onCancel={() => {
            if (ghReq) native.cancel(ghReq);
            setGhReq(null);
            setDeviceCode(null);
          }}
        />
        <Toasts items={toasts} dismiss={(id) => setToasts((x) => x.filter((y) => y.id !== id))} />
      </I18nContext.Provider>
    );
  }

  // ------------------------------------------------------------ main
  return (
    <I18nContext.Provider value={ctx}>
      <div className="min-h-full">
        <div className="fixed inset-x-0 top-0 z-20 bg-bg" style={{ height: "var(--sat)" }} />
        <div className="pt-safe">
          <div className="flex h-14 items-center px-4">
            <div className="flex flex-1 items-baseline gap-2">
              <h1 className="text-[24px] font-semibold tracking-tight">Patches</h1>
              {patches.length > 0 && <span className="num text-[15px] text-muted">{patches.length}</span>}
            </div>
            <IconButton label={t("openPatch")} onClick={() => setSheet("import")}>
              <Plus className="h-[21px] w-[21px]" />
            </IconButton>
            <IconButton label={t("settings")} onClick={() => setSheet("settings")}>
              {github.user.avatar ? (
                <img src={`${github.user.avatar}${github.user.avatar.includes("?") ? "&" : "?"}s=64`} alt="" className="h-7 w-7 rounded-full bg-subtle" />
              ) : (
                <SettingsIcon className="h-[19px] w-[19px]" />
              )}
            </IconButton>
          </div>
        </div>

        <main className="space-y-2.5 px-4 pt-1" style={{ paddingBottom: "calc(var(--sab) + 32px)" }}>
          {patches.map((p) => <PatchCard key={p.id} patch={p} onOpen={setOpenId} />)}
          {patches.length === 0 && (
            <div className="flex flex-col items-center py-24 text-center">
              <p className="text-[14px] text-muted">{t("emptyNone")}</p>
              <Button variant="secondary" size="sm" className="mt-4" onClick={() => setSheet("import")}>
                <Plus className="h-4 w-4" /> {t("open")}
              </Button>
            </div>
          )}
        </main>

        {detail && openId && (
          <PatchView
            patch={detail}
            wrap={settings.wrap}
            onWrap={() => changeSettings({ wrap: !settings.wrap })}
            onBack={() => setOpenId(null)}
            onPush={() => { setPushMissed(false); setPushView("main"); setSheet("push"); }}
            onPushMissed={() => { setPushMissed(true); setPushView("main"); setSheet("push"); }}
            onDelete={() => setSheet("delete")}
            onOpenUrl={native.openUrl}
          />
        )}

        <ImportSheet
          open={sheet === "import"}
          onOpenChange={(v) => setSheet(v ? "import" : null)}
          busy={importBusy}
          onFile={() => startImport("file")}
          onClipboard={() => startImport("clipboard")}
          onUrl={(u) => startImport("url", u)}
        />

        <PushSheet
          open={sheet === "push"}
          onOpenChange={(v) => setSheet(v ? "push" : null)}
          view={pushView}
          setView={setPushView}
          patch={detail}
          onlyMissed={pushMissed}
          repos={repos}
          reposLoading={reposLoading}
          recent={recent}
          onLoadRepos={loadRepos}
          onPushed={onPushed}
        />

        <Sheet
          open={sheet === "delete"}
          onOpenChange={(v) => setSheet(v ? "delete" : null)}
          title={t("deleteAsk")}
          footer={
            <div className="flex gap-2">
              <Button variant="secondary" size="lg" className="flex-1" onClick={() => setSheet(null)}>{t("keep")}</Button>
              <Button
                variant="danger"
                size="lg"
                className="flex-1"
                onClick={() => {
                  if (!detail) return;
                  native.deletePatch(detail.id);
                  setPatches((x) => x.filter((p) => p.id !== detail.id));
                  setSheet(null);
                  setOpenId(null);
                }}
              >
                {t("delete")}
              </Button>
            </div>
          }
        >
          <p className="code truncate text-[13px] text-muted">{detail?.name}</p>
        </Sheet>

        <SettingsSheet
          open={sheet === "settings"}
          onOpenChange={(v) => setSheet(v ? "settings" : null)}
          settings={settings}
          onChange={(patch) => {
            changeSettings(patch);
            if ("githubClientId" in patch) toast(t("savedRelogin"));
          }}
          user={github.user}
          version={initial.version}
          onGithubLogout={() => {
            native.githubLogout();
            setGithub({ loggedIn: false, user: { login: "", avatar: "", name: "" } });
            setRepos({ at: 0, items: [] });
            setRecent([]);
            setSheet(null);
          }}
        />

        <Toasts items={toasts} offset={detail && openId && !sheet ? 76 : 0} dismiss={(id) => setToasts((x) => x.filter((y) => y.id !== id))} />
      </div>
    </I18nContext.Provider>
  );
}
