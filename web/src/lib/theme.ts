/**
 * 主题状态机（浅色 / 深色 / 跟随系统）
 *
 * 规则（用户要求）：
 * - 顶部快捷按钮只做「当前实际显示的反面」：浅色时点一下立刻变深色，反之亦然。
 *   「跟随系统」只放在「外观」设置里，不让快捷按钮在 system→light→dark 之间轮换。
 * - 选择要持久化（localStorage），刷新保持。
 * - 只有 system 模式才跟随系统变化；light/dark 是明确的用户选择，不被系统覆盖。
 * - 存储不可用（隐私模式 / localStorage 抛错）时降级到内存状态，不崩。
 *
 * 原生侧：<html> 上的 data-theme / .dark 之外，还要通知原生同步系统栏颜色。
 * WebView 里 `window.__sztuSystemDark` 由原生注入；没有原生时用 matchMedia。
 */

export type ThemeMode = "light" | "dark" | "system";
export type ResolvedTheme = "light" | "dark";

const STORAGE_KEY = "sztu_theme";

declare global {
  interface Window {
    /** 原生注入的当前系统是否深色；未注入时为 undefined。 */
    __sztuSystemDark?: boolean;
    /** 原生在系统深浅色变化时调用。 */
    onSztuSystemTheme?: (dark: boolean) => void;
  }
}

const listeners = new Set<(theme: ResolvedTheme, mode: ThemeMode) => void>();
let mode: ThemeMode = "system";
let resolved: ResolvedTheme = "light";
let media: MediaQueryList | null = null;
let started = false;

function systemPrefersDark(): boolean {
  if (typeof window.__sztuSystemDark === "boolean") return window.__sztuSystemDark;
  try {
    return !!window.matchMedia?.("(prefers-color-scheme: dark)")?.matches;
  } catch {
    return false;
  }
}

function readStored(): ThemeMode {
  try {
    const raw = window.localStorage?.getItem(STORAGE_KEY);
    if (raw === "light" || raw === "dark" || raw === "system") return raw;
  } catch {
    /* 存储不可用：按跟随系统处理 */
  }
  return "system";
}

function writeStored(next: ThemeMode) {
  try {
    window.localStorage?.setItem(STORAGE_KEY, next);
  } catch {
    /* 忽略：本次会话内仍生效 */
  }
}

/** 把主题写到 DOM，并通知原生系统栏。 */
function paint(next: ResolvedTheme) {
  const root = document.documentElement;
  root.dataset.theme = next;
  root.classList.toggle("dark", next === "dark");
  // 原生系统栏（可选桥方法；缺失时静默跳过 —— 浏览器 / 回归测试里没有它）
  try {
    window.ScheduleBridge?.setSystemBars?.(next === "dark");
  } catch {
    /* 桥不可用不影响前端主题 */
  }
}

function resolve(): ResolvedTheme {
  if (mode === "light" || mode === "dark") return mode;
  return systemPrefersDark() ? "dark" : "light";
}

function emit() {
  for (const fn of listeners) {
    try {
      fn(resolved, mode);
    } catch {
      /* 监听方自己出错不能影响其它监听方 */
    }
  }
}

function apply(nextMode: ThemeMode, persist: boolean) {
  mode = nextMode;
  if (persist) writeStored(nextMode);
  const next = resolve();
  if (next !== resolved) {
    resolved = next;
    paint(next);
  } else {
    // 模式可能变了但实际外观没变：仍然刷一遍 DOM，避免首屏漏设置
    paint(next);
  }
  emit();
}

/**
 * 安全区所有权标记：带原生桥时由原生负责系统栏/刘海避让，网页 CSS 变量归零。
 * index.html 首屏脚本已经做过一次（防闪）；这里兜底旧 WebView 里桥注入较晚的情况。
 */
function markNativeInsetsOwner() {
  try {
    if (window.ScheduleBridge && typeof window.ScheduleBridge.getState === "function") {
      document.documentElement.dataset.nativeInsets = "1";
    }
  } catch {
    /* 无桥 = 独立浏览器，继续用 env() */
  }
}

/** 首次挂载：读存储 + 挂系统监听。重复调用无副作用。 */
export function initTheme() {
  markNativeInsetsOwner();
  if (!started) {
    started = true;
    mode = readStored();
    resolved = resolve();
    paint(resolved);
    try {
      media = window.matchMedia("(prefers-color-scheme: dark)");
      media.addEventListener("change", () => {
        if (mode !== "system") return; // 明确选择浅/深时不被系统覆盖
        apply("system", false);
      });
    } catch {
      media = null;
    }
    // 原生在 Configuration 变化时回调（可选）
    window.onSztuSystemTheme = (dark: boolean) => {
      window.__sztuSystemDark = dark;
      if (mode === "system") apply("system", false);
    };
  }
  return resolved;
}

export function getThemeMode(): ThemeMode {
  return mode;
}

export function getResolvedTheme(): ResolvedTheme {
  return resolved;
}

export function setThemeMode(next: ThemeMode) {
  if (next === mode) {
    // 同一模式重复点击也要保证 DOM 与原生栏一致（幂等）
    paint(resolve());
    return;
  }
  apply(next, true);
}

/** 顶部快捷按钮：只在浅色 ↔ 深色之间切换。 */
export function toggleTheme(): ResolvedTheme {
  const next: ResolvedTheme = resolved === "dark" ? "light" : "dark";
  setThemeMode(next);
  return next;
}

export function onThemeChange(fn: (theme: ResolvedTheme, mode: ThemeMode) => void) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

/** 主卡装饰动画：页面隐藏时暂停（不新增定时器）。 */
export function setDecorationsPaused(paused: boolean) {
  const root = document.documentElement;
  if (paused) root.style.setProperty("--deco-play", "paused");
  else root.style.removeProperty("--deco-play");
}
