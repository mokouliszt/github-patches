import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import type { T } from "./i18n";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Relative time in the app language. */
export function ago(ms: number, t: T): string {
  if (!ms) return "";
  const d = (Date.now() - ms) / 1000;
  if (d < 60) return t("agoNow");
  if (d < 3600) return t("agoMin", { n: Math.floor(d / 60) });
  if (d < 86400) return t("agoHour", { n: Math.floor(d / 3600) });
  if (d < 86400 * 30) return t("agoDay", { n: Math.floor(d / 86400) });
  if (d < 86400 * 365) return t("agoMonth", { n: Math.floor(d / (86400 * 30)) });
  return t("agoYear", { n: Math.floor(d / (86400 * 365)) });
}

export function date(iso: string): string {
  const t = Date.parse(iso);
  if (isNaN(t)) return "";
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

export function splitPath(p: string): { dir: string; base: string } {
  const i = p.lastIndexOf("/");
  return i < 0 ? { dir: "", base: p } : { dir: p.slice(0, i + 1), base: p.slice(i + 1) };
}

/** Branch name suggestion from the patch title: ASCII words only, else a timestamp. */
export function suggestBranch(title: string): string {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .split("-")
    .filter(Boolean)
    .slice(0, 6)
    .join("-")
    .slice(0, 40)
    .replace(/-+$/, "");
  if (slug.length >= 3) return `patch/${slug}`;
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, "0");
  return `patch/${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

/** git check-ref-format, the parts that matter for a branch name typed on a phone. */
export function validBranch(name: string): boolean {
  if (!name || name.length > 200) return false;
  if (/[\s~^:?*\[\\\x00-\x1f\x7f]/.test(name)) return false;
  if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".") || name.endsWith(".lock")) return false;
  if (name.includes("..") || name.includes("//") || name.includes("@{") || name === "@") return false;
  return !name.split("/").some((c) => c.startsWith(".") || c.endsWith(".lock"));
}
