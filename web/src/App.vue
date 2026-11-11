<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import campusMapUrl from "@/assets/campus-map.jpg";
import {
  getResolvedTheme,
  getThemeMode,
  initTheme,
  onThemeChange,
  setDecorationsPaused,
  setThemeMode,
  toggleTheme,
  type ResolvedTheme,
  type ThemeMode,
} from "@/lib/theme";

type Course = {
  id: string;
  name: string;
  teacher: string;
  place: string;
  weeks: string;
  weekday: number;
  startSection: number;
  endSection: number;
  startTime: string;
  endTime: string;
  sectionLabel: string;
  timeLabel: string;
};

type Snapshot = {
  courses: Course[];
  term: string;
  teachingWeek: number;
  syncedAt: number;
  loggedIn: boolean;
  version?: string;
  syncing?: boolean;
  /** 受限背景图端点地址（带 ?v= 版本号）。旧 APK 可能仍发 bgImage(data URL)，此处只做降级读取。 */
  bgImageUrl?: string;
  bgImage?: string;
  bgOpacity?: number;
  bgLuminance?: number;
  /** 会话三态：NONE / CACHED_ONLY / SERVER_VERIFIED（报告 J03） */
  sessionState?: string;
  needsResync?: boolean;
  emptyTerm?: boolean;
  /** 最近一次同步失败原文；空串表示没有失败（J29-02） */
  lastSyncError?: string;
  /** 校历起点（报告 J07）：CALIBRATED 表示用户校准过 */
  termStartMs?: number;
  termStartSource?: string;
  syncButtonLabel?: string;
  jwxtDetail?: string;
  hwsysDetail?: string;
  syncDetail?: string;
  syncStage?: string;
};

type AttendanceRow = {
  id: string;
  title: string;
  date: string;
  time: string;
  status: string;
  kind: string;
  detail: string;
};

type AttendanceView = {
  phase: string;
  headline: string;
  note: string;
  confirmedAbsences: number | null;
  pendingCount: number | null;
  rangeStart: string;
  rangeEnd: string;
  rangeConfirmed: boolean;
  rangeSource: string;
  fetchedAt: number;
  queryComplete: boolean;
  error: string;
  listEmpty: string;
  rows: AttendanceRow[];
};

type AttendanceState = {
  scheduleTerm: string;
  viewTerm: string;
  loggedIn: boolean;
  accountName: string;
  terms: { id: string; schedule?: boolean; start: string; end: string; confirmed: boolean; source: string }[];
  view: AttendanceView;
  today: AttendanceView;
  syncDetail: string;
  storageWarning?: boolean;
};

declare global {
  interface Window {
    ScheduleBridge?: {
      getState: () => string;
      login: () => void;
      refresh: () => void;
      pinWidget: () => void;
      openWidgetHelp: () => void;
      pickBackground: () => void;
      clearBackground: () => void;
      setBackgroundOpacity: (v: string) => void;
      getOtaState?: () => string;
      checkUpdate?: () => void;
      downloadUpdate?: () => string;
      openApkInBrowser?: () => string;
      openUnknownAppSettings?: () => void;
      setTermStart?: (dateIso: string) => string;
      clearTermStart?: () => string;
      /** 仅主题用途的受限原生桥：同步系统栏明暗（2.1.0 新增，可缺失） */
      setSystemBars?: (dark: boolean) => void;
      syncData?: {
        (): void;
        (origin: string): void;
      };
      getAttendanceState?: () => string;
      setAttendanceTerm?: (termId: string) => string;
      confirmAttendanceRange?: (termId: string, start: string, end: string) => string;
    };
    onScheduleState?: (json: string) => void;
    onScheduleMessage?: (msg: string) => void;
    onOtaState?: (json: string) => void;
    onAttendanceState?: (json: string) => void;
    __handleAndroidBack?: () => boolean;
  }
}

const WEEKDAY = ["", "一", "二", "三", "四", "五", "六", "日"];

/**
 * ==== 功能开关（用户约束 2026-10-29：新增业务入口 / 新功能须先经用户同意）====
 *
 * HOME_STATUS_BANNER
 *   首页「状态条」的**纯文案**部分。属于评审 J29-02 明确要求的能力——
 *   「首页错误状态要分清 未登录 / 缓存可看 / 同步失败 / 解析需核对」，
 *   数据全部来自既有快照字段（sessionState / needsResync / lastSyncError），
 *   不新增业务数据源。默认开启。
 *
 * HOME_BANNER_ACTIONS
 *   状态条上的「去登录 / 重新同步」两个按钮。这会在首页新增**业务入口**
 *   （登录流程原先只能从 设置 → 官方登录 进入），属未获批准的新入口，
 *   **默认关闭**。用户同意后把这里改成 true 即可，无需改其它代码。
 *   开关位置：本文件 `const HOME_BANNER_ACTIONS`；测试断言见
 *   `tools/test-web-startup.py`（默认断言按钮数为 0）。
 */
const HOME_STATUS_BANNER = true;
const HOME_BANNER_ACTIONS = false;

/** 深技大 1–14 节（晚间到 21:50），课表左侧时间轴用 */
const SECTION_TIMES: Record<number, [string, string]> = {
  1: ["08:30", "09:10"],
  2: ["09:15", "09:55"],
  3: ["10:15", "10:55"],
  4: ["11:00", "11:40"],
  5: ["11:45", "12:25"],
  6: ["14:00", "14:40"],
  7: ["14:45", "15:25"],
  8: ["15:45", "16:25"],
  9: ["16:30", "17:10"],
  10: ["17:15", "17:55"],
  11: ["19:00", "19:40"],
  12: ["20:10", "20:20"],
  13: ["20:30", "21:10"],
  14: ["21:40", "21:50"],
};

/** 2026-11-09 授权新增「出勤」。对分易考勤仍然不要恢复。 */
type TabId = "today" | "schedule" | "attendance";
const TAB_ORDER: Record<TabId, number> = { today: 0, schedule: 1, attendance: 2 };

function blankAttendance(headline = "未同步"): AttendanceView {
  return {
    phase: "unsynced",
    headline,
    note: "",
    confirmedAbsences: null,
    pendingCount: null,
    rangeStart: "",
    rangeEnd: "",
    rangeConfirmed: false,
    rangeSource: "",
    fetchedAt: 0,
    queryComplete: false,
    error: "",
    listEmpty: "这一学期还没有同步",
    rows: [],
  };
}

const tab = ref<TabId>("today");
const settingsMenuOpen = ref(false);
const settingsPage = ref<"login" | "appearance" | "bg" | "map" | "term" | "about" | null>(null);
const snap = ref<Snapshot>({
  courses: [],
  term: "",
  teachingWeek: 0,
  syncedAt: 0,
  loggedIn: false,
  version: "",
  syncing: false,
  bgImageUrl: "",
  bgImage: "",
  bgOpacity: 0.55,
  bgLuminance: 0.92,
  sessionState: "NONE",
  needsResync: false,
  emptyTerm: false,
  lastSyncError: "",
  termStartMs: 0,
  termStartSource: "GUESSED",
});

const attendance = ref<AttendanceState>({
  scheduleTerm: "",
  viewTerm: "",
  loggedIn: false,
  accountName: "",
  terms: [],
  view: blankAttendance(),
  today: blankAttendance(),
  syncDetail: "",
});
const absenceOnly = ref(true);
const openAttendanceId = ref("");
const rangeStartInput = ref("");
const rangeEndInput = ref("");

type OtaSnap = {
  checking: boolean;
  downloading: boolean;
  progress: number;
  hasUpdate: boolean;
  error: string;
  localVersionName: string;
  localVersionCode: number;
  remoteVersionName: string;
  remoteVersionCode: number;
  changelog: string;
};

const ota = ref<OtaSnap>({
  checking: false,
  downloading: false,
  progress: 0,
  hasUpdate: false,
  error: "",
  localVersionName: "",
  localVersionCode: 0,
  remoteVersionName: "",
  remoteVersionCode: 0,
  changelog: "",
});

function applyOta(raw: string) {
  try {
    ota.value = { ...ota.value, ...(JSON.parse(raw) as OtaSnap) };
  } catch {
    /* ignore */
  }
}

function refreshOta() {
  const raw = window.ScheduleBridge?.getOtaState?.();
  if (raw) applyOta(raw);
}

function checkAppUpdate() {
  window.ScheduleBridge?.checkUpdate?.();
  window.setTimeout(refreshOta, 400);
}

function downloadAppUpdate() {
  const err = window.ScheduleBridge?.downloadUpdate?.();
  if (err) ota.value = { ...ota.value, error: err };
  window.setTimeout(refreshOta, 400);
}

function openApkBrowser() {
  const err = window.ScheduleBridge?.openApkInBrowser?.();
  if (err) ota.value = { ...ota.value, error: err };
}

const busy = ref(false);
const message = ref("");
const viewWeek = ref(1);

// liveTeachingWeek 的 immediate watcher 会在 setup 中同步求值，时钟必须先初始化。
// 同时驱动跨日、跨周和节次切换后的页面刷新。
const clockTick = ref(0);
let clockTimer: ReturnType<typeof setInterval> | null = null;

/** 课程色带：0..4 → .tone-1..5（颜色在 tokens.css，组件不写裸色值） */
type GridCourse = Course & { start: number; end: number };
const selected = ref<GridCourse | null>(null);

function toneOf(name: string): number {
  return (Math.abs(hash(name)) % 5) + 1;
}

/** 跟手滑动（可打断结算、速度甩、直写 transform） */
const SLIDE_MS = 180;
const dragX = ref(0);
const dragging = ref(false);
const settling = ref(false);
const touchStartX = ref(0);
const touchStartY = ref(0);
const lockDir = ref<"h" | "v" | "">("");
const paneWidth = ref(0);
const gridBodyH = ref(0);
const gridWrapEl = ref<HTMLElement | null>(null);
const slideTrackEl = ref<HTMLElement | null>(null);
let resizeObs: ResizeObserver | null = null;
let settleTimer: number | null = null;
let dragRaf = 0;
let pendingDragX = 0;
let lastSampleX = 0;
let lastSampleT = 0;
let velocityX = 0;

const DAY_HEAD_PX = 34;

/**
 * 内容区尺寸：clientWidth/clientHeight 含 padding，必须扣掉。
 * .grid-wrap 左右各有页面边距（手机 16px / 宽屏 26px），
 * 直接用 clientWidth 会让每页多出 32/52px → 裁掉周日列、滑动偏位。
 */
function measurePaneWidth() {
  const el = gridWrapEl.value;
  if (!el) return;
  const cs = getComputedStyle(el);
  const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
  const padY = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
  const w = el.clientWidth - padX;
  const h = el.clientHeight - padY;
  if (w > 0) paneWidth.value = w;
  if (h > 0) gridBodyH.value = h;
}

function clearSettleTimer() {
  if (settleTimer != null) {
    window.clearTimeout(settleTimer);
    settleTimer = null;
  }
}

function setTrackTransform(x: number, withTransition: boolean) {
  const el = slideTrackEl.value;
  const w = paneWidth.value;
  if (!el) return;
  if (w <= 0) {
    el.style.transition = "none";
    el.style.transform = "translate3d(-33.333333%,0,0)";
    return;
  }
  el.style.transition = withTransition
    ? `transform ${SLIDE_MS}ms cubic-bezier(0.22, 0.92, 0.28, 1)`
    : "none";
  el.style.transform = `translate3d(${-w + x}px,0,0)`;
}

function readDragXFromDom(): number {
  const el = slideTrackEl.value;
  const w = paneWidth.value;
  if (!el || w <= 0) return dragX.value;
  const t = getComputedStyle(el).transform;
  if (!t || t === "none") return dragX.value;
  try {
    return new DOMMatrixReadOnly(t).m41 + w;
  } catch {
    return dragX.value;
  }
}

function cancelSettlingToFinger() {
  if (!settling.value) return;
  clearSettleTimer();
  const x = readDragXFromDom();
  settling.value = false;
  dragX.value = x;
  pendingDragX = x;
  setTrackTransform(x, false);
}

const opacityLocal = ref(0.88);

function openCourse(c: GridCourse, e: Event) {
  e.stopPropagation();
  selected.value = c;
}
function closeCourse() {
  selected.value = null;
}
function weeksDisplay(weeks: string) {
  if (!weeks?.trim()) return "—";
  return weeks.replace(/\s+/g, " ").trim();
}

function applyAttendance(raw?: string) {
  if (!raw) return;
  try {
    const next = JSON.parse(raw) as AttendanceState;
    next.view = { ...blankAttendance(), ...(next.view || {}) };
    next.today = { ...blankAttendance(), ...(next.today || {}) };
    next.terms = Array.isArray(next.terms) ? next.terms : [];
    attendance.value = next;
    rangeStartInput.value = next.view.rangeStart || "";
    rangeEndInput.value = next.view.rangeEnd || "";
  } catch {
    /* ignore */
  }
}

window.onAttendanceState = applyAttendance;

function applyState(raw: string) {
  try {
    const next = JSON.parse(raw) as Snapshot;
    const prevWeek = snap.value.teachingWeek;
    snap.value = next;
    opacityLocal.value = typeof next.bgOpacity === "number" ? next.bgOpacity : 0.88;
    // 报告 J07：教学周以 Kotlin 的校历起点（可校准）为准，用统一来源覆盖缓存
    const live = liveTeachingWeek.value;
    // 用实时周覆盖缓存，并在用户仍看「当前周」时跟随跳转
    snap.value = { ...snap.value, teachingWeek: live };
    if (viewWeek.value <= 1 || viewWeek.value === prevWeek || viewWeek.value === next.teachingWeek) {
      viewWeek.value = live;
    }
  } catch {
    /* ignore */
  }
  busy.value = !!snap.value.syncing;
}

window.onScheduleState = (json: string) => applyState(json);
window.onOtaState = (json: string) => applyOta(json);
window.onScheduleMessage = (msg: string) => {
  message.value = msg;
  if (msg === "更新中" || msg === "同步中") busy.value = true;
  else if (!snap.value.syncing) busy.value = false;
};

/**
 * 三态周次匹配（与 Kotlin ScheduleClock.matchWeek 保持一致）：
 * - "match"  ：本周上课
 * - "no"     ：本周不上（单双周 / 指定周次不包含）
 * - "unknown"：周次字段为空或解析不出数字 → 不能当作“本周不上课”
 */
function weekMatchOf(weeks: string, week: number): "match" | "no" | "unknown" {
  const raw = String(weeks ?? "").trim();
  if (!raw) return "unknown";
  // 禁止裸匹配「单/双」（会误伤）；只认 单周/(单)、双周/(双)
  const oddOnly = /单周|（单）|\(单\)/.test(raw) && !/双周|（双）|\(双\)/.test(raw);
  const evenOnly = /双周|（双）|\(双\)/.test(raw);
  if (oddOnly && week % 2 === 0) return "no";
  if (evenOnly && week % 2 === 1) return "no";
  // 先去「星期X」，再去「第/周」，避免「第7周 星期三」被拆烂
  let body = (raw.split("[")[0] ?? raw).split("节")[0] ?? raw;
  body = body
    .replace(/星期[一二三四五六日天]/g, "")
    .replace(/周[一二三四五六日天]/g, "")
    .replace(/[（(]周[）)]/g, "")
    .replace(/[第周单双]/g, "")
    .replace(/[～—~]/g, "-")
    .replace(/[()（）]/g, " ")
    .trim();
  if (!body || !/\d/.test(body)) return "unknown";
  for (const part of body.split(/[,，、\s;；]+/)) {
    const p = part.trim();
    if (!p) continue;
    if (p.includes("-")) {
      const nums = p.split("-").map((x) => parseInt(x.replace(/\D/g, ""), 10));
      const a = nums[0];
      const b = nums[1];
      if (!Number.isNaN(a) && !Number.isNaN(b) && week >= Math.min(a, b) && week <= Math.max(a, b)) {
        return "match";
      }
    } else {
      const n = parseInt(p.replace(/\D/g, ""), 10);
      if (n === week) return "match";
    }
  }
  return "no";
}

/** 兼容旧调用：未知周次按“可能上课”处理（不隐藏课程）。 */
function matchesWeek(weeks: string, week: number): boolean {
  return weekMatchOf(weeks, week) !== "no";
}

function courseSections(c: Course): { start: number; end: number } {
  let start = Number(c.startSection) || 0;
  let end = Number(c.endSection) || 0;
  if (start > 0 && end >= start) return { start, end };
  const fromLabel = String(c.sectionLabel || "").match(/(\d+)\s*[-–—~]\s*(\d+)/);
  if (fromLabel) return { start: Number(fromLabel[1]), end: Number(fromLabel[2]) };
  const single = String(c.sectionLabel || "").match(/(\d+)/);
  if (single) {
    const n = Number(single[1]);
    return { start: n, end: n };
  }
  return { start: 1, end: 2 };
}

function hash(s: string) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return h;
}

function week1Monday(): Date {
  const term = snap.value.term || "";
  const m = term.match(/(20\d{2})(?:\s*[-–]\s*(20\d{2}))?/);
  const y1 = m ? Number(m[1]) : new Date().getFullYear();
  const y2 = m && m[2] ? Number(m[2]) : y1 + 1;
  const spring = /-\s*2\s*$/.test(term.trim()) || term.trim().endsWith("-2");
  // 秋：含 9/1 的周一；春：含 3/1 的周一（与校历「开学当周」对齐，2026 秋即 8/31）
  const d = new Date(spring ? y2 : y1, spring ? 2 : 8, 1);
  d.setHours(0, 0, 0, 0);
  while (d.getDay() !== 1) d.setDate(d.getDate() - 1);
  return d;
}
function addDays(base: Date, n: number) {
  const d = new Date(base);
  d.setDate(d.getDate() + n);
  return d;
}

/** 按物理日期推算当前教学周（优先于磁盘里缓存的旧值） */
function currentTeachingWeek(): number {
  const start = week1Monday();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diffDays = Math.floor((today.getTime() - start.getTime()) / 86400000);
  return Math.min(30, Math.max(1, Math.floor(diffDays / 7) + 1));
}

/** 从给定的第 1 周周一（毫秒）推算教学周；与 Kotlin TeachingWeek.weeksFrom 一致。 */
function weekFromStart(startMs: number): number {
  const start = new Date(startMs);
  start.setHours(0, 0, 0, 0);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diffDays = Math.floor((today.getTime() - start.getTime()) / 86400000);
  return Math.min(30, Math.max(1, Math.floor(diffDays / 7) + 1));
}

const liveTeachingWeek = computed(() => {
  // 依赖 clockTick：跨日/跨周驻留时也要重算，否则会一直停在旧教学周
  void clockTick.value;
  // 报告 J07：Kotlin 侧给出校历起点（可能是用户校准值）时优先使用，
  // 保证今日页/周课表/小组件与“校准后的教学周”一致。
  const startMs = Number(snap.value.termStartMs || 0);
  if (startMs > 0) return weekFromStart(startMs);
  const live = currentTeachingWeek();
  // 有课表时用实时周；无课表时仍展示推算周
  return live;
});

const weekDates = computed(() => datesForWeek(viewWeek.value));
const monthLabel = computed(() => `${weekDates.value[0].getMonth() + 1}月`);

const maxWeek = computed(() => {
  let max = Math.max(liveTeachingWeek.value, snap.value.teachingWeek || 0, 18);
  for (const c of snap.value.courses) {
    const m = c.weeks?.match(/\d+/g);
    if (m) for (const n of m) max = Math.max(max, parseInt(n, 10));
  }
  return Math.min(Math.max(max, 1), 22);
});

watch(
  liveTeachingWeek,
  (w, prev) => {
    if (w <= 0) return;
    // 首次、或用户仍停在「上一当前周」时跟随物理周跳转
    if (prev == null || viewWeek.value === prev) {
      viewWeek.value = w;
    }
  },
  { immediate: true }
);

function datesForWeek(week: number) {
  const mon = addDays(week1Monday(), (week - 1) * 7);
  return [0, 1, 2, 3, 4, 5, 6].map((i) => addDays(mon, i));
}

function coursesForWeek(week: number): GridCourse[] {
  if (week < 1 || week > maxWeek.value) return [];
  return snap.value.courses
    .filter(
      (c) =>
        matchesWeek(c.weeks, week) &&
        c.weekday >= 1 &&
        c.weekday <= 7 &&
        !/课堂讲授|课内实践|^备注/.test(c.name)
    )
    .map((c) => {
      const { start, end } = courseSections(c);
      return {
        ...c,
        start: Math.min(Math.max(start, 1), maxSection.value),
        end: Math.min(Math.max(end, start), maxSection.value),
      };
    });
}

const maxSection = computed(() => {
  let max = 12;
  for (const c of snap.value.courses) {
    max = Math.max(max, courseSections(c).end);
  }
  return Math.min(Math.max(max, 12), 14);
});

/** 按网格可视高度均分节次行，铺满下方，避免课表底下留白 */
const rowPx = computed(() => {
  const n = maxSection.value;
  const fallback = n >= 13 ? 36 : 40;
  const body = gridBodyH.value;
  if (body <= DAY_HEAD_PX + n * 24) return fallback;
  return Math.max(26, Math.floor((body - DAY_HEAD_PX) / n));
});

const sectionRows = computed(() => Array.from({ length: maxSection.value }, (_, i) => i + 1));

/** 旧逻辑用背景图亮度切配色；现在配色全部来自主题令牌，只保留给 has-bg 的对比度提示 */
const darkBg = computed(() => getResolvedTheme() === "dark" && !!snap.value.bgImageUrl);

/** 左 / 中 / 右 三页；key 固定，换周只改内容，避免整页卸载卡顿 */
const weekPanes = computed(() => {
  const cur = viewWeek.value;
  return [
    { key: "prev", week: cur - 1, dates: datesForWeek(cur - 1), courses: coursesForWeek(cur - 1) },
    { key: "cur", week: cur, dates: datesForWeek(cur), courses: coursesForWeek(cur) },
    { key: "next", week: cur + 1, dates: datesForWeek(cur + 1), courses: coursesForWeek(cur + 1) },
  ];
});

const syncedText = computed(() => {
  if (!snap.value.syncedAt) return "尚未同步";
  const d = new Date(snap.value.syncedAt);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
});

const syncing = computed(
  () => !!snap.value.syncing || (busy.value && (message.value === "更新中" || message.value === "同步中"))
);

/**
 * 背景图地址（J29-01）：只接受本地受限端点（`/bg/...`）或 http(s)，
 * 明确拒绝 data: —— 防止有人把「整张图 Base64 跨桥」那条老路径再引回来。
 */
const bgUrl = computed(() => {
  const raw = String(snap.value.bgImageUrl ?? snap.value.bgImage ?? "").trim();
  if (!raw) return "";
  if (raw.startsWith("data:")) return "";
  if (raw.startsWith("/")) return raw;
  if (/^https?:\/\//i.test(raw)) return raw;
  return "";
});

/** 已预解码完成的地址。为空时首屏不画背景（先出内容，图片就绪再渐显）。 */
const bgReadyUrl = ref("");

/** 真正的「有背景」判定：地址非空 **且** 图片已经解码好。 */
const hasBg = computed(() => !!bgReadyUrl.value && bgReadyUrl.value === bgUrl.value);

function preloadBg(url: string) {
  if (!url) {
    bgReadyUrl.value = "";
    return;
  }
  if (bgReadyUrl.value === url) return;
  const img = new Image();
  img.decoding = "async";
  img.onload = () => {
    if (bgUrl.value === url) bgReadyUrl.value = url;
  };
  img.onerror = () => {
    if (bgReadyUrl.value === url) bgReadyUrl.value = "";
  };
  img.src = url;
}

// 换图/换版本号后就地重新预解码；清空背景时立即撤掉
watch(bgUrl, (url) => preloadBg(url));

const shellStyle = computed(() =>
  hasBg.value
    ? {
        "--bg-image": `url(${bgReadyUrl.value})`,
        "--bg-opacity": String(opacityLocal.value),
      }
    : undefined
);

/**
 * 后台/隐藏时停掉 30s 时钟，避免不可见时仍反复触发 computed 重算
 * （todayCourses / todayStatus / weekPanes 都依赖 clockTick，J29-02）。
 * 回到前台立刻补一次 tick，节次/跨日不会停摆。
 *
 * 同时暂停主卡装饰动画（CSS 变量驱动，不新增 JS 高频 timer）。
 */
function startClock() {
  if (clockTimer != null) return;
  clockTimer = window.setInterval(() => {
    clockTick.value += 1;
  }, 30_000);
}

function stopClock() {
  if (clockTimer == null) return;
  window.clearInterval(clockTimer);
  clockTimer = null;
}

/**
 * 首页状态条（J29-02）：把「未登录 / 缓存可看 / 同步失败 / 解析需核对」
 * 分开说清楚，而不是都落到「还没有课表」一句话。
 */
type HomeBanner = {
  tone: "warn" | "error";
  title: string;
  desc: string;
  action?: "login" | "refresh";
};

const homeBanner = computed<HomeBanner | null>(() => {
  const s = snap.value;
  const hasCourses = s.courses.length > 0;
  const state = String(s.sessionState ?? "NONE");
  const err = String(s.lastSyncError ?? "").trim();
  // 1) 同步失败：真实错误原文来自 Kotlin（doSync 的 onFailure）
  if (err) return { tone: "error", title: "同步失败", desc: err, action: hasCourses ? "refresh" : "login" };
  // 2) 解析需核对：教务页面结构可能与解析器不一致，旧课表已保留
  if (s.needsResync) {
    return {
      tone: "warn",
      title: "解析需核对",
      desc: "教务页面结构可能变了，已保留上次课表；请重新同步核对",
      action: "refresh",
    };
  }
  // 3) 完全没有课表
  if (!hasCourses) {
    if (state === "NONE") return { tone: "warn", title: "未登录", desc: "还没有课表，先登录教务再同步", action: "login" };
    return { tone: "warn", title: "还没有课表", desc: "登录教务后同步一次即可", action: "login" };
  }
  // 4) 有缓存但会话未验证：能看，但不是「已登录」
  if (state === "CACHED_ONLY") {
    return {
      tone: "warn",
      title: "缓存可看",
      desc: "当前显示的是本地缓存，会话未验证；课程可能有变动",
      action: "login",
    };
  }
  // 5) 本学期确实是空课表（不是失败）
  if (s.emptyTerm) {
    return { tone: "warn", title: "本学期空课表", desc: "教务返回 0 门课，属正常空学期" };
  }
  return null;
});

/** 登录页状态文案：用会话三态，而不是「有课表就当已登录」。 */
const sessionLabel = computed(() => {
  const state = String(snap.value.sessionState ?? "NONE");
  if (state === "SERVER_VERIFIED") return "已登录（会话已验证）";
  if (state === "CACHED_ONLY") return "缓存可看（会话未验证）";
  return snap.value.courses.length ? "有缓存课表（未登录）" : "未登录";
});

/** 宽度由 Vue 管；位移跟手时直写 DOM，避免每帧走响应式 */
const trackStyle = computed(() => {
  const w = paneWidth.value;
  if (w <= 0) return { width: "300%" };
  return { width: `${w * 3}px` };
});

const paneStyle = computed(() => {
  const w = paneWidth.value;
  if (w <= 0) return { width: "33.333333%" };
  return { width: `${w}px` };
});

watch(paneWidth, () => {
  if (!dragging.value) setTrackTransform(dragX.value, false);
});

function finishSettleToWeek(target: number) {
  clearSettleTimer();
  viewWeek.value = target;
  dragX.value = 0;
  pendingDragX = 0;
  // 先无过渡贴回中心，再松开 settling，避免露馅
  setTrackTransform(0, false);
  // 强制一帧应用无过渡复位
  void slideTrackEl.value?.offsetWidth;
  settling.value = false;
}

function bounceBack() {
  clearSettleTimer();
  settling.value = true;
  dragging.value = false;
  dragX.value = 0;
  pendingDragX = 0;
  setTrackTransform(0, true);
  settleTimer = window.setTimeout(() => {
    settleTimer = null;
    settling.value = false;
  }, SLIDE_MS);
}

function commitWeek(delta: -1 | 1) {
  const next = viewWeek.value + delta;
  if (next < 1 || next > maxWeek.value) {
    bounceBack();
    return;
  }
  animateToWeek(next, delta > 0 ? 1 : -1);
}

/** 朝 dir 方向滑出一页后落到 target（可跨多周，保证一定有动画） */
function animateToWeek(target: number, dir: 1 | -1) {
  clearSettleTimer();
  measurePaneWidth();
  const w = paneWidth.value;
  if (w <= 0) {
    viewWeek.value = target;
    dragX.value = 0;
    pendingDragX = 0;
    settling.value = false;
    setTrackTransform(0, false);
    return;
  }
  settling.value = true;
  dragging.value = false;
  const to = dir > 0 ? -w : w;
  dragX.value = to;
  pendingDragX = to;
  // 若当前已有位移，先无过渡对齐再开动画，避免偶发跳变无过渡
  setTrackTransform(readDragXFromDom(), false);
  void slideTrackEl.value?.offsetWidth;
  setTrackTransform(to, true);
  settleTimer = window.setTimeout(() => {
    settleTimer = null;
    finishSettleToWeek(target);
  }, SLIDE_MS);
}

/** 带动画跳到指定周（用于点周次 / 右下角返回本周） */
function goToWeek(target: number, animated = true) {
  const t = Math.min(maxWeek.value, Math.max(1, target));
  if (t === viewWeek.value) return;
  cancelSettlingToFinger();
  measurePaneWidth();
  if (!animated || paneWidth.value <= 0) {
    clearSettleTimer();
    viewWeek.value = t;
    dragX.value = 0;
    pendingDragX = 0;
    settling.value = false;
    setTrackTransform(0, false);
    return;
  }
  animateToWeek(t, t > viewWeek.value ? 1 : -1);
}

function onTouchStart(e: TouchEvent) {
  if (tab.value !== "schedule" || selected.value) return;
  const t = e.touches[0] || e.changedTouches[0];
  if (!t) return;
  cancelSettlingToFinger();
  if (dragRaf) {
    cancelAnimationFrame(dragRaf);
    dragRaf = 0;
  }
  measurePaneWidth();
  // 从当前视觉位置续拖，支持连滑
  const base = dragX.value;
  touchStartX.value = t.clientX - base;
  touchStartY.value = t.clientY;
  lastSampleX = t.clientX;
  lastSampleT = performance.now();
  velocityX = 0;
  lockDir.value = "";
  dragging.value = false;
}

function flushDragFrame() {
  dragRaf = 0;
  dragX.value = pendingDragX;
  setTrackTransform(pendingDragX, false);
}

function onTouchMove(e: TouchEvent) {
  if (tab.value !== "schedule" || selected.value) return;
  const t = e.touches[0];
  if (!t) return;
  const dx = t.clientX - touchStartX.value;
  const dy = t.clientY - touchStartY.value;
  if (!lockDir.value) {
    if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
    lockDir.value = Math.abs(dx) > Math.abs(dy) * 1.05 ? "h" : "v";
  }
  if (lockDir.value !== "h") return;
  e.preventDefault();
  dragging.value = true;

  const now = performance.now();
  const dt = now - lastSampleT;
  if (dt > 0) {
    const vx = (t.clientX - lastSampleX) / dt;
    velocityX = velocityX * 0.6 + vx * 0.4;
  }
  lastSampleX = t.clientX;
  lastSampleT = now;

  let x = dx;
  // 边界轻阻尼，别太死
  if ((viewWeek.value <= 1 && dx > 0) || (viewWeek.value >= maxWeek.value && dx < 0)) {
    x = dx * 0.35;
  }
  pendingDragX = x;
  if (!dragRaf) dragRaf = requestAnimationFrame(flushDragFrame);
}

function onTouchEnd() {
  if (dragRaf) {
    cancelAnimationFrame(dragRaf);
    dragRaf = 0;
    flushDragFrame();
  }
  if (!dragging.value) {
    lockDir.value = "";
    velocityX = 0;
    return;
  }
  dragging.value = false;
  lockDir.value = "";
  measurePaneWidth();
  const w = paneWidth.value || 360;
  const dist = dragX.value;
  const flick = Math.abs(velocityX) > 0.45; // px/ms ≈ 450px/s
  const threshold = Math.min(48, w * 0.12);
  const goNext = dist <= -threshold || (flick && velocityX < -0.35 && dist < -8);
  const goPrev = dist >= threshold || (flick && velocityX > 0.35 && dist > 8);
  velocityX = 0;
  if (goNext) commitWeek(1);
  else if (goPrev) commitWeek(-1);
  else bounceBack();
}

function prevWeek() {
  if (viewWeek.value <= 1) return;
  cancelSettlingToFinger();
  commitWeek(-1);
}
function nextWeek() {
  if (viewWeek.value >= maxWeek.value) return;
  cancelSettlingToFinger();
  commitWeek(1);
}
function goCurrentWeek() {
  const w = liveTeachingWeek.value > 0 ? liveTeachingWeek.value : 1;
  goToWeek(w, true);
}

const showBackCurrent = computed(
  () => liveTeachingWeek.value > 0 && viewWeek.value !== liveTeachingWeek.value && tab.value === "schedule"
);

function paneTransform(id: TabId) {
  const i = TAB_ORDER[id];
  const cur = TAB_ORDER[tab.value];
  if (i === cur) return "translate3d(0,0,0)";
  if (i < cur) return "translate3d(-100%,0,0)";
  return "translate3d(100%,0,0)";
}

function switchTab(next: TabId) {
  if (next === tab.value) return;
  // 打断周次结算，避免残留 transform 干扰
  clearSettleTimer();
  if (dragRaf) {
    cancelAnimationFrame(dragRaf);
    dragRaf = 0;
  }
  settling.value = false;
  dragging.value = false;
  dragX.value = 0;
  pendingDragX = 0;
  lockDir.value = "";
  tab.value = next;
  if (next === "schedule") {
    nextTick(() => {
      measurePaneWidth();
      setTrackTransform(-paneWidth.value, false);
    });
  }
}

function toggleSettingsMenu() {
  if (settingsPage.value) {
    settingsPage.value = null;
    settingsMenuOpen.value = false;
    return;
  }
  settingsMenuOpen.value = !settingsMenuOpen.value;
}
function closeSettingsMenu() {
  settingsMenuOpen.value = false;
}
type SettingsPage = "login" | "appearance" | "bg" | "map" | "about" | "term";
function openSettingsPage(page: SettingsPage) {
  settingsMenuOpen.value = false;
  if (page === "map") resetMapZoom();
  settingsPage.value = page;
  if (page === "term") syncTermStartLocal();
}
function closeSettingsPage() {
  settingsPage.value = null;
  resetMapZoom();
}
function backToSettingsMenu() {
  settingsPage.value = null;
  resetMapZoom();
  settingsMenuOpen.value = true;
}

/* ---------------- 主题 ---------------- */
const themeMode = ref<ThemeMode>("system");
const resolvedTheme = ref<ResolvedTheme>("light");
let themeUnsub: (() => void) | null = null;

function syncThemeState() {
  resolvedTheme.value = getResolvedTheme();
  // 真实模式必须回填：否则「外观」里的选中态永远停在初始的 system
  themeMode.value = getThemeMode();
}
function onToggleTheme() {
  toggleTheme();
  syncThemeState();
}
function pickThemeMode(next: ThemeMode) {
  setThemeMode(next);
  syncThemeState();
}
const themeToggleLabel = computed(() =>
  resolvedTheme.value === "dark" ? "切换到浅色外观" : "切换到深色外观"
);
const THEME_OPTIONS: { id: ThemeMode; label: string }[] = [
  { id: "light", label: "浅色" },
  { id: "dark", label: "深色" },
  { id: "system", label: "跟随系统" },
];

const mapScale = ref(1);
const mapScrollEl = ref<HTMLElement | null>(null);
const MAP_MIN = 1;
const MAP_MAX = 4;
let mapPinching = false;
let mapJustPinched = false;
let mapPinchDist0 = 0;
let mapPinchScale0 = 1;
let mapPinchRelX = 0;
let mapPinchRelY = 0;
let mapPinchViewX = 0;
let mapPinchViewY = 0;
let mapLastTapT = 0;
let mapLastTapX = 0;
let mapLastTapY = 0;

function resetMapZoom() {
  mapScale.value = 1;
  mapPinching = false;
  mapJustPinched = false;
  const el = mapScrollEl.value;
  if (el) {
    el.scrollLeft = 0;
    el.scrollTop = 0;
  }
}

function mapTouchDist(a: Touch, b: Touch) {
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
}

function applyMapScale(next: number, viewX: number, viewY: number, relX: number, relY: number) {
  mapScale.value = Math.min(MAP_MAX, Math.max(MAP_MIN, next));
  nextTick(() => {
    const el = mapScrollEl.value;
    if (!el) return;
    el.scrollLeft = relX * el.scrollWidth - viewX;
    el.scrollTop = relY * el.scrollHeight - viewY;
  });
}

function onMapTouchStart(e: TouchEvent) {
  if (e.touches.length === 2) {
    mapPinching = true;
    mapJustPinched = true;
    const el = mapScrollEl.value;
    if (!el) return;
    const t0 = e.touches[0];
    const t1 = e.touches[1];
    mapPinchDist0 = mapTouchDist(t0, t1) || 1;
    mapPinchScale0 = mapScale.value;
    const rect = el.getBoundingClientRect();
    mapPinchViewX = (t0.clientX + t1.clientX) / 2 - rect.left;
    mapPinchViewY = (t0.clientY + t1.clientY) / 2 - rect.top;
    const sw = Math.max(el.scrollWidth, 1);
    const sh = Math.max(el.scrollHeight, 1);
    mapPinchRelX = (el.scrollLeft + mapPinchViewX) / sw;
    mapPinchRelY = (el.scrollTop + mapPinchViewY) / sh;
  }
}

function onMapTouchMove(e: TouchEvent) {
  if (!mapPinching || e.touches.length < 2) return;
  e.preventDefault();
  const d = mapTouchDist(e.touches[0], e.touches[1]);
  applyMapScale(mapPinchScale0 * (d / mapPinchDist0), mapPinchViewX, mapPinchViewY, mapPinchRelX, mapPinchRelY);
}

function onMapTouchEnd(e: TouchEvent) {
  if (e.touches.length < 2) mapPinching = false;
  if (e.touches.length !== 0) return;
  if (mapJustPinched) {
    mapJustPinched = false;
    mapLastTapT = 0;
    return;
  }
  if (e.changedTouches.length !== 1) return;
  const t = e.changedTouches[0];
  const now = Date.now();
  const dt = now - mapLastTapT;
  const dx = t.clientX - mapLastTapX;
  const dy = t.clientY - mapLastTapY;
  if (dt < 280 && Math.hypot(dx, dy) < 28) {
    mapLastTapT = 0;
    const el = mapScrollEl.value;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const viewX = t.clientX - rect.left;
    const viewY = t.clientY - rect.top;
    const relX = (el.scrollLeft + viewX) / Math.max(el.scrollWidth, 1);
    const relY = (el.scrollTop + viewY) / Math.max(el.scrollHeight, 1);
    applyMapScale(mapScale.value > 1.15 ? 1 : 2.2, viewX, viewY, relX, relY);
  } else {
    mapLastTapT = now;
    mapLastTapX = t.clientX;
    mapLastTapY = t.clientY;
  }
}

function zoomMapBy(delta: number) {
  const el = mapScrollEl.value;
  if (!el) {
    mapScale.value = Math.min(MAP_MAX, Math.max(MAP_MIN, mapScale.value + delta));
    return;
  }
  const viewX = el.clientWidth / 2;
  const viewY = el.clientHeight / 2;
  const relX = (el.scrollLeft + viewX) / Math.max(el.scrollWidth, 1);
  const relY = (el.scrollTop + viewY) / Math.max(el.scrollHeight, 1);
  applyMapScale(mapScale.value + delta, viewX, viewY, relX, relY);
}

function todayWeekday(): number {
  void clockTick.value;
  const js = new Date().getDay();
  return js === 0 ? 7 : js;
}

function onAppVisible() {
  if (document.visibilityState === "visible") {
    // 回到前台：立刻补一次重算，并恢复 30s 时钟 + 装饰动画
    stopClock();
    clockTick.value += 1;
    startClock();
    setDecorationsPaused(false);
  } else {
    // 不可见时不做无意义的重算，省电（J29-02）；装饰动画同步暂停
    stopClock();
    setDecorationsPaused(true);
  }
}

function parseHm(hm: string): number {
  const m = String(hm || "").match(/(\d{1,2}):(\d{2})/);
  if (!m) return -1;
  return Number(m[1]) * 60 + Number(m[2]);
}

/** 当前分钟数；依赖 clockTick，30s 时钟推进后重算。 */
function nowMinutes(): number {
  void clockTick.value;
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
}

const todayCourses = computed(() => {
  void clockTick.value;
  const week = liveTeachingWeek.value > 0 ? liveTeachingWeek.value : viewWeek.value;
  const wd = todayWeekday();
  // 本周确实没有这门课就保持为空：不能用“今日全部课程”顶替，
  // 否则只在单周开设的课会在双周重新出现在今日页（报告 J02）。
  return coursesForWeek(week)
    .filter((c) => c.weekday === wd)
    .sort((a, b) => parseHm(a.startTime) - parseHm(b.startTime) || a.start - b.start);
});

/**
 * 今日页状态：
 * - empty     ：还没有课表数据（未同步）
 * - no_class  ：本周这一天确实没有课
 * - week_unknown：有今天的课，但周次字段解析不出来 → 保留课程并提示待核对
 * - ok        ：本周有课
 */
const todayStatus = computed(() => {
  void clockTick.value;
  if (!snap.value.courses.length) return "empty" as const;
  const week = liveTeachingWeek.value > 0 ? liveTeachingWeek.value : viewWeek.value;
  const wd = todayWeekday();
  const todays = snap.value.courses.filter(
    (c) => c.weekday === wd && !/课堂讲授|课内实践|^备注/.test(c.name)
  );
  if (!todays.length) return "no_class" as const;
  let hasUnknown = false;
  for (const c of todays) {
    const m = weekMatchOf(String(c.weeks ?? ""), week);
    if (m === "match") return "ok" as const;
    if (m === "unknown") hasUnknown = true;
  }
  return hasUnknown ? ("week_unknown" as const) : ("no_class" as const);
});

/**
 * 今日课程按周次分成「已确认」与「待核对」两类（报告 J02 / UNKNOWN 语义）：
 * 已知教学周时，weeks 解析不出来的课**继续展示**，但不能当作「确定今天上课」
 * 参与正在上课 / 下一节 / 已结束的判定，否则会把待核对的课说成正在上课。
 */
const todayWeekClass = computed(() => {
  void clockTick.value;
  const week = liveTeachingWeek.value > 0 ? liveTeachingWeek.value : viewWeek.value;
  const map = new Map<string, "match" | "unknown">();
  for (const c of todayCourses.value) {
    map.set(c.id, weekMatchOf(String(c.weeks ?? ""), week) === "unknown" ? "unknown" : "match");
  }
  return map;
});

function weekMatchClassOf(id: string): "match" | "unknown" {
  return todayWeekClass.value.get(id) ?? "match";
}

/** 已确认本周上课的今日课程：只有这些能驱动「正在上课 / 下一节」状态 */
const confirmedTodayCourses = computed(() =>
  todayCourses.value.filter((c) => weekMatchClassOf(c.id) === "match")
);

/** 周次无法解析的今日课程：保留展示并标记「待核对」 */
const uncertainTodayCourses = computed(() =>
  todayCourses.value.filter((c) => weekMatchClassOf(c.id) === "unknown")
);

/**
 * 课程时间区间：解析失败、缺字段或 end <= start 一律返回 null。
 * parseHm 对缺失时间返回 -1，直接拿来比较会把「时间未知」误判成「已结束」，
 * 所以 live / next / done 三条判定都必须先过这一关。
 */
function courseMinutes(c: Course): { start: number; end: number } | null {
  const s = parseHm(c.startTime);
  const e = parseHm(c.endTime);
  if (s < 0 || e < 0 || e <= s) return null;
  return { start: s, end: e };
}

/** 已确认本周上课、但时间字段缺失/不可解析的课：保留展示，状态标「时间待核对」 */
const timedUnknownTodayCourses = computed(() =>
  confirmedTodayCourses.value.filter((c) => courseMinutes(c) === null)
);

/** 正在上的课（start <= now < end；下课整分钟不算）。与 Kotlin ScheduleClock 一致。 */
const liveCourse = computed(() => {
  void clockTick.value;
  const now = nowMinutes();
  return (
    confirmedTodayCourses.value.find((c) => {
      const hm = courseMinutes(c);
      if (!hm) return false;
      return now >= hm.start && now < hm.end;
    }) ?? null
  );
});

/** 下一节：start > now 的第一门（只认已确认且有有效时间的课） */
const nextCourse = computed(() => {
  void clockTick.value;
  const now = nowMinutes();
  return (
    confirmedTodayCourses.value.find((c) => {
      const hm = courseMinutes(c);
      return hm ? hm.start > now : false;
    }) ?? null
  );
});

/** 已上完的最后一门（只认已确认且有有效时间的课） */
const lastDoneCourse = computed(() => {
  void clockTick.value;
  const now = nowMinutes();
  const done = confirmedTodayCourses.value.filter((c) => {
    const hm = courseMinutes(c);
    return hm ? hm.end <= now : false;
  });
  return done.length ? done[done.length - 1] : null;
});

const todayHeader = computed(() => {
  void clockTick.value;
  const d = new Date();
  const wd = WEEKDAY[todayWeekday()];
  const week = liveTeachingWeek.value > 0 ? `第 ${liveTeachingWeek.value} 周` : "";
  return {
    date: `${d.getMonth() + 1}月${d.getDate()}日`,
    weekday: `周${wd}`,
    week,
  };
});

/**
 * 首页主卡内容：只用真实数据推导，绝不把「未登录 / 缓存 / 待核对 / 同步失败」
 * 说成「运行中 / 已连接」。状态文案沿用 home-banner 的准确措辞。
 */
type HeroState = {
  tone: "live" | "warn" | "error" | "quiet";
  status: string;
  title: string;
  meta: string;
};

function courseMeta(c: Course): string {
  const time =
    c.startTime && c.endTime ? `${c.startTime}–${c.endTime}` : String(c.timeLabel ?? "").trim();
  const bits = [time, c.place, c.teacher].filter((x) => x && String(x).trim());
  return bits.join(" · ");
}

const heroState = computed<HeroState>(() => {
  void clockTick.value;
  const s = snap.value;
  const banner = homeBanner.value;
  const live = liveCourse.value;
  const next = nextCourse.value;
  const done = lastDoneCourse.value;
  const todayCount = todayCourses.value.length;

  // 1) 完全没有课表：状态用 banner 的准确文案，主标题不要说成「运行中」
  if (!s.courses.length) {
    if (banner?.tone === "error") {
      return { tone: "error", status: banner.title, title: "课表读取失败", meta: banner.desc };
    }
    if (s.emptyTerm) {
      return { tone: "quiet", status: "本学期空课表", title: "本学期暂无课程", meta: "教务返回 0 门课，属正常空学期" };
    }
    return {
      tone: banner ? "warn" : "quiet",
      status: banner?.title ?? "未登录",
      title: "还没有课表",
      meta: banner?.desc ?? "登录教务后同步一次即可",
    };
  }

  // 2) 同步失败 / 解析需核对 / 缓存可看：先把真实异常说清楚
  if (banner?.tone === "error") {
    return {
      tone: "error",
      status: banner.title,
      title: live ? live.name : next ? next.name : "课表未更新",
      meta: banner.desc,
    };
  }
  if (s.needsResync) {
    return {
      tone: "warn",
      status: "解析需核对",
      title: live ? live.name : next ? next.name : `${s.courses.length} 门课已缓存`,
      meta: "教务页面结构可能变了，已保留上次课表",
    };
  }
  if (String(s.sessionState ?? "NONE") === "CACHED_ONLY") {
    return {
      tone: "warn",
      status: "缓存可看",
      title: live ? live.name : next ? next.name : done ? done.name : "本地缓存课表",
      meta: live || next || done ? courseMeta((live ?? next ?? done) as Course) : "会话未验证，课程可能有变动",
    };
  }

  // 3) 正常数据：按 start <= now < end 推导真实状态
  if (live) {
    return { tone: "live", status: "正在上课", title: live.name, meta: courseMeta(live) };
  }
  if (next) {
    return {
      tone: "quiet",
      status: `下一节 ${next.startTime || ""}`.trim(),
      title: next.name,
      meta: courseMeta(next),
    };
  }
  if (done) {
    const pending =
      uncertainTodayCourses.value.length + timedUnknownTodayCourses.value.length;
    return {
      tone: "quiet",
      // 还有周次/时间待核对的课时，不能说成「全天结束」
      status: pending ? "已确认课程已结束" : "今日课程已结束",
      title: done.name,
      meta: `最后一节 ${done.startTime}–${done.endTime}${done.place ? " · " + done.place : ""}${
        pending ? ` · 另有 ${pending} 门待核对` : ""
      }`,
    };
  }
  // 已确认的课全都缺时间：不能说成「已结束」或「今日无课」，按待核对处理
  if (
    !live &&
    !next &&
    !done &&
    timedUnknownTodayCourses.value.length &&
    !confirmedTodayCourses.value.some((c) => courseMinutes(c) !== null)
  ) {
    return {
      tone: "warn",
      status: "时间待核对",
      title: `${timedUnknownTodayCourses.value.length} 门课时间缺失`,
      meta: "课程已保留在今日列表，请核对教务上课时间",
    };
  }
  // 没有已确认的课，但存在周次无法解析的课：保留展示，语义必须是「待核对」
  if (!confirmedTodayCourses.value.length && uncertainTodayCourses.value.length) {
    return {
      tone: "warn",
      status: "周次待核对",
      title: `${uncertainTodayCourses.value.length} 门课周次无法解析`,
      meta: "课程已保留在今日列表，请核对教务周次",
    };
  }
  if (todayStatus.value === "week_unknown") {
    return {
      tone: "warn",
      status: "周次待核对",
      title: `${todayCount} 门课周次无法解析`,
      meta: "课程已在课表中保留，请核对教务周次",
    };
  }
  if (!todayCount) {
    return {
      tone: "quiet",
      status: "今日无课",
      title: "今天没有安排课程",
      meta: "可以在课表里查看整周安排",
    };
  }
  return { tone: "quiet", status: "今日课程", title: `${todayCount} 门课`, meta: "" };
});

const heroFoot = computed(() => {
  const d = new Date();
  const bits = [`${d.getMonth() + 1}月${d.getDate()}日`, `周${WEEKDAY[todayWeekday()]}`];
  if (liveTeachingWeek.value > 0) bits.push(`第 ${liveTeachingWeek.value} 周`);
  if (snap.value.term) bits.push(snap.value.term);
  return bits;
});

const todayListNote = computed(() => {
  if (!todayCourses.value.length) return "";
  const live = liveCourse.value;
  if (live) return `正在上课 · ${live.startTime}–${live.endTime}`;
  const next = nextCourse.value;
  if (next) return `下一节 ${next.startTime}`;
  if (lastDoneCourse.value) {
    const pending = uncertainTodayCourses.value.length + timedUnknownTodayCourses.value.length;
    return pending ? `已确认课程已结束 · 另有 ${pending} 门待核对` : "今日已全部结束";
  }
  if (timedUnknownTodayCourses.value.length) return "时间待核对";
  return uncertainTodayCourses.value.length ? "周次待核对" : "";
});

function syncData(origin?: string) {
  message.value = origin === "attendance" ? "正在检查教学辅助系统" : "正在同步课表";
  busy.value = true;
  const bridge = window.ScheduleBridge;
  if (origin && bridge?.syncData) bridge.syncData(origin);
  else if (bridge?.syncData) bridge.syncData();
  else bridge?.login?.();
}
function login() {
  message.value = "";
  closeSettingsMenu();
  syncData();
}
function refresh() {
  syncData();
}
function openScheduleAttendance() {
  const term = attendance.value.scheduleTerm || snap.value.term;
  if (term && window.ScheduleBridge?.setAttendanceTerm) {
    applyAttendance(window.ScheduleBridge.setAttendanceTerm(term));
  }
  switchTab("attendance");
}
function onPickAttendanceTerm(event: Event) {
  const term = (event.target as HTMLSelectElement).value;
  openAttendanceId.value = "";
  if (window.ScheduleBridge?.setAttendanceTerm) applyAttendance(window.ScheduleBridge.setAttendanceTerm(term));
}
function confirmAttendanceRange() {
  const term = attendance.value.viewTerm;
  if (!term || !window.ScheduleBridge?.confirmAttendanceRange) return;
  applyAttendance(window.ScheduleBridge.confirmAttendanceRange(term, rangeStartInput.value, rangeEndInput.value));
}
const visibleAttendance = computed(() => {
  const rows = attendance.value.view?.rows || [];
  return absenceOnly.value ? rows.filter((row) => row.kind === "confirmed_absence") : rows;
});
function pickBg() {
  window.ScheduleBridge?.pickBackground();
}
function clearBg() {
  window.ScheduleBridge?.clearBackground();
}

/** 校历校准（报告 J07）：用户可以指定本学期第 1 周周一，替代“按 9/1、3/1 猜”。 */
const termStartInput = ref("");

function isoFromMs(ms: number): string {
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function syncTermStartLocal() {
  const ms = Number(snap.value.termStartMs || 0);
  if (ms > 0) termStartInput.value = isoFromMs(ms);
}

function saveTermStart() {
  const raw = termStartInput.value.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    message.value = "日期格式应为 yyyy-MM-dd";
    return;
  }
  const json = window.ScheduleBridge?.setTermStart?.(raw);
  if (json) applyState(json);
  message.value = "已保存校历起点";
  syncTermStartLocal();
}

function resetTermStart() {
  const json = window.ScheduleBridge?.clearTermStart?.();
  if (json) applyState(json);
  message.value = "已恢复按月份推算";
  syncTermStartLocal();
}
function onOpacityInput(e: Event) {
  const v = Number((e.target as HTMLInputElement).value);
  opacityLocal.value = v;
  window.ScheduleBridge?.setBackgroundOpacity(String(v));
}

const settingsPageTitle = computed(() => {
  if (settingsPage.value === "login") return "登录与同步";
  if (settingsPage.value === "appearance") return "外观";
  if (settingsPage.value === "bg") return "选择背景";
  if (settingsPage.value === "map") return "教学楼地图";
  if (settingsPage.value === "about") return "关于";
  if (settingsPage.value === "term") return "校历起点";
  return "设置";
});

function fmtDate(d: Date) {
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

function handleAndroidBack(): boolean {
  if (selected.value) {
    closeCourse();
    return true;
  }
  if (settingsPage.value) {
    backToSettingsMenu();
    return true;
  }
  if (settingsMenuOpen.value) {
    closeSettingsMenu();
    return true;
  }
  if (tab.value === "schedule" || tab.value === "attendance") {
    switchTab("today");
    return true;
  }
  return false;
}

watch(tab, (t) => {
  if (t === "schedule") {
    nextTick(() => {
      measurePaneWidth();
      setTrackTransform(0, false);
      resizeObs?.disconnect();
      if (gridWrapEl.value && typeof ResizeObserver !== "undefined") {
        resizeObs = new ResizeObserver(() => measurePaneWidth());
        resizeObs.observe(gridWrapEl.value);
      }
    });
  }
});

/* ---------------- 宽屏 / 移动端导航 ---------------- */
/** 只有一套导航在 DOM：宽屏侧栏、移动端浮动 Dock。隐藏版本不存在，也就不需要惰性隐藏。 */
const isWide = ref(false);
let wideMedia: MediaQueryList | null = null;

function syncWide() {
  isWide.value = !!wideMedia?.matches;
}

onMounted(() => {
  initTheme();
  syncThemeState();
  themeUnsub = onThemeChange(() => syncThemeState());

  window.__handleAndroidBack = handleAndroidBack;
  const raw = window.ScheduleBridge?.getState?.();
  if (raw) applyState(raw);
  applyAttendance(window.ScheduleBridge?.getAttendanceState?.());
  preloadBg(bgUrl.value);
  refreshOta();
  viewWeek.value = liveTeachingWeek.value;
  clockTick.value += 1;
  // 首帧仍在后台时不浪费时钟；回到前台由 visibilitychange 拉起（J29-02）
  if (document.visibilityState === "visible") {
    startClock();
    setDecorationsPaused(false);
  } else {
    setDecorationsPaused(true);
  }
  document.addEventListener("visibilitychange", onAppVisible);

  try {
    wideMedia = window.matchMedia("(min-width: 1000px)");
    syncWide();
    wideMedia.addEventListener("change", syncWide);
  } catch {
    wideMedia = null;
  }

  nextTick(() => {
    measurePaneWidth();
    setTrackTransform(0, false);
    if (gridWrapEl.value && typeof ResizeObserver !== "undefined") {
      resizeObs = new ResizeObserver(() => measurePaneWidth());
      resizeObs.observe(gridWrapEl.value);
    }
    // WebView 首帧有时宽度为 0，再补测几次
    window.setTimeout(() => {
      measurePaneWidth();
      setTrackTransform(dragX.value, false);
    }, 50);
    window.setTimeout(() => {
      measurePaneWidth();
      setTrackTransform(dragX.value, false);
    }, 200);
  });
});

onBeforeUnmount(() => {
  clearSettleTimer();
  if (dragRaf) cancelAnimationFrame(dragRaf);
  resizeObs?.disconnect();
  resizeObs = null;
  stopClock();
  themeUnsub?.();
  themeUnsub = null;
  document.removeEventListener("visibilitychange", onAppVisible);
  try {
    wideMedia?.removeEventListener("change", syncWide);
  } catch {
    /* ignore */
  }
  wideMedia = null;
  if (window.__handleAndroidBack === handleAndroidBack) {
    delete window.__handleAndroidBack;
  }
  if (window.onOtaState === applyOta) {
    delete window.onOtaState;
  }
  if (window.onAttendanceState === applyAttendance) {
    delete window.onAttendanceState;
  }
});
</script>

<template>
  <UApp>
    <div
      class="app-shell"
      :class="{ 'has-bg': hasBg, 'dark-bg': darkBg }"
      :style="shellStyle"
    >
      <div v-if="hasBg" class="bg-layer fade-in" aria-hidden="true" />

      <!-- 宽屏侧栏（移动端不渲染这份 DOM） -->
      <nav v-if="isWide" class="side-nav" aria-label="主导航">
        <div class="side-brand">
          <span class="side-brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 6.6S9.9 4.7 5.2 4.7v10.8c4.7 0 6.8 1.9 6.8 1.9s2.1-1.9 6.8-1.9V4.7C14.1 4.7 12 6.6 12 6.6z" />
              <path d="M12 6.6v10.8" />
            </svg>
          </span>
          <div class="side-brand-text">
            <p class="side-brand-title">SZTU教务</p>
            <p class="side-brand-sub">{{ snap.term || "未同步学期" }}</p>
          </div>
        </div>

        <button
          type="button"
          class="side-item"
          :class="{ active: tab === 'today' }"
          :aria-current="tab === 'today' ? 'page' : undefined"
          @click="switchTab('today')"
        >
          <span class="ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="8.2" />
              <path d="M12 7.6V12l3 1.8" />
            </svg>
          </span>
          <span>今日</span>
        </button>

        <button
          type="button"
          class="side-item"
          :class="{ active: tab === 'schedule' }"
          :aria-current="tab === 'schedule' ? 'page' : undefined"
          @click="switchTab('schedule')"
        >
          <span class="ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3.2" y="4.4" width="17.6" height="16.2" rx="3" />
              <path d="M3.2 9.4h17.6M8.2 2.6v3.6M15.8 2.6v3.6" />
              <path d="M7.4 13h3v3h-3zM13.6 13h3v3h-3z" />
            </svg>
          </span>
          <span>课表</span>
        </button>

        <button
          type="button"
          class="side-item"
          :class="{ active: tab === 'attendance' }"
          :aria-current="tab === 'attendance' ? 'page' : undefined"
          @click="switchTab('attendance')"
        >
          <span class="ico" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M8 4.5h8" />
              <rect x="5" y="3.2" width="14" height="17.6" rx="3" />
              <path d="M8.2 10.2h7.6M8.2 14h7.6M8.2 17.2h4.4" />
            </svg>
          </span>
          <span>出勤</span>
        </button>

        <p class="side-foot">
          {{ sessionLabel }}<br />
          上次同步 {{ syncedText }}
        </p>
      </nav>

      <div class="main-col">
        <header class="top-bar">
          <div class="head-left">
            <p class="head-date num">
              {{ tab === "today" ? todayHeader.date : tab === "attendance" ? "出勤记录" : monthLabel }}
            </p>
            <p class="head-sub">
              <template v-if="tab === 'today'">
                {{ todayHeader.weekday }} · {{ todayHeader.week || "教学周未同步" }}
              </template>
              <template v-else-if="tab === 'attendance'">
                {{ attendance.viewTerm || snap.term || "学期未对应" }}
              </template>
              <template v-else>{{ snap.term || "未同步学期" }}</template>
            </p>
          </div>
          <div class="head-actions">
            <span v-if="syncing" class="sync-badge">{{ snap.syncStage || "同步中" }}</span>
            <!-- 顶部快捷按钮：每次点击都在浅色 ↔ 深色之间立即切换（系统模式在「外观」里） -->
            <button
              type="button"
              class="icon-btn"
              :aria-label="themeToggleLabel"
              :title="themeToggleLabel"
              @click="onToggleTheme"
            >
              <svg v-if="resolvedTheme === 'dark'" viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="4.2" />
                <path d="M12 2.8v2.2M12 19v2.2M4.6 4.6l1.6 1.6M17.8 17.8l1.6 1.6M2.8 12H5M19 12h2.2M4.6 19.4l1.6-1.6M17.8 6.2l1.6-1.6" />
              </svg>
              <svg v-else viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M20.5 14.4A8.6 8.6 0 0 1 9.6 3.5a8.7 8.7 0 1 0 10.9 10.9z" />
              </svg>
            </button>
            <button
              type="button"
              class="icon-btn glass gear"
              :class="{ open: settingsMenuOpen || !!settingsPage }"
              aria-label="设置"
              @click="toggleSettingsMenu"
            >
              <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="3" />
                <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9c.3.6.9 1 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" />
              </svg>
            </button>
          </div>
        </header>

        <div class="page-host">
          <!-- 今日 -->
          <div
            class="tab-pane-host"
            :class="{ active: tab === 'today' }"
            :style="{ transform: paneTransform('today') }"
            :aria-hidden="tab !== 'today'"
            :inert="tab !== 'today'"
          >
            <section class="today-pane">
              <div class="today-scroll">
                <!-- 标志性主卡：装饰花形只做视觉，不是按钮，也不接收焦点 -->
                <article class="hero" aria-labelledby="hero-title">
                  <div class="hero-info">
                    <p class="hero-status" :class="'tone-' + heroState.tone">
                      <span class="dot" aria-hidden="true" />
                      <span>{{ heroState.status }}</span>
                    </p>
                    <h1 id="hero-title" class="hero-title">{{ heroState.title }}</h1>
                    <p v-if="heroState.meta" class="hero-meta">{{ heroState.meta }}</p>
                    <p class="hero-foot">
                      <template v-for="(bit, i) in heroFoot" :key="bit">
                        <span v-if="i > 0" class="sep" aria-hidden="true">·</span>
                        <span :class="{ term: i === heroFoot.length - 1 && !!snap.term }">{{ bit }}</span>
                      </template>
                    </p>
                  </div>
                  <div class="hero-art" aria-hidden="true">
                    <svg class="hero-wave" viewBox="0 0 120 200" preserveAspectRatio="none">
                      <path d="M78 0 C 40 34, 108 74, 66 114 C 34 146, 58 172, 40 200 L120 200 L120 0 Z" />
                    </svg>
                    <div class="hero-badge">
                      <svg class="hero-petals" viewBox="0 0 100 100">
                        <!-- 八个同色圆 + 中心实心圆：并集形成连续封闭的圆润八瓣轮廓，
                             不使用透明花瓣叠加，阴影也会跟着真实轮廓走 -->
                        <g fill="currentColor">
                          <circle
                            v-for="k in 8"
                            :key="k"
                            cx="50"
                            cy="18"
                            r="15.5"
                            :transform="`rotate(${(k - 1) * 45} 50 50)`"
                          />
                          <circle cx="50" cy="50" r="26" />
                        </g>
                      </svg>
                      <svg class="hero-mark" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M12 6.6S9.9 4.7 5.2 4.7v10.8c4.7 0 6.8 1.9 6.8 1.9s2.1-1.9 6.8-1.9V4.7C14.1 4.7 12 6.6 12 6.6z" />
                        <path d="M12 6.6v10.8" />
                      </svg>
                    </div>
                  </div>
                </article>

                <!-- 首页状态条（J29-02）：未登录 / 缓存可看 / 同步失败 / 解析需核对 分开说。
                     「去登录 / 重新同步」按钮是新增业务入口，默认关闭（HOME_BANNER_ACTIONS）。 -->
                <div
                  v-if="HOME_STATUS_BANNER && homeBanner"
                  class="home-banner"
                  :class="'tone-' + homeBanner.tone"
                  role="status"
                  aria-live="polite"
                >
                  <div class="hb-text">
                    <p class="hb-title">{{ homeBanner.title }}</p>
                    <p class="hb-desc">{{ homeBanner.desc }}</p>
                  </div>
                  <button
                    v-if="HOME_BANNER_ACTIONS && homeBanner.action === 'login'"
                    type="button"
                    class="hb-action"
                    @click="login"
                  >
                    去登录
                  </button>
                  <button
                    v-else-if="HOME_BANNER_ACTIONS && homeBanner.action === 'refresh'"
                    type="button"
                    class="hb-action"
                    :disabled="syncing"
                    @click="refresh"
                  >
                    重新同步
                  </button>
                </div>

                <button type="button" class="att-summary" :class="{ 'is-alert': (attendance.today.confirmedAbsences || 0) > 0 }" @click="openScheduleAttendance">
                  <span class="att-summary-copy">
                    <span class="att-summary-kicker">本学期出勤</span>
                    <strong class="att-summary-title">
                      <template v-if="(attendance.today.confirmedAbsences || 0) > 0">已确认缺勤 {{ attendance.today.confirmedAbsences }}次</template>
                      <template v-else>{{ attendance.today.headline || "未同步" }}</template>
                    </strong>
                    <span class="att-summary-meta">{{ attendance.scheduleTerm || snap.term || "学期未同步" }}</span>
                    <span v-if="(attendance.today.pendingCount || 0) > 0" class="att-summary-pending">待确认 {{ attendance.today.pendingCount }} 条</span>
                  </span>
                  <span class="att-summary-go" aria-hidden="true">›</span>
                </button>

                <!-- 右栏自然流：标题紧贴列表。移动端 display:contents 让子元素直接参与
                     外层纵向排列，宽屏才变成独立一列（标题不会被主卡高度推下去）。 -->
                <div class="today-right">
                  <div v-if="todayCourses.length" class="section-head">
                    <h2 class="section-title">今日课程</h2>
                    <span class="section-note">{{ todayListNote }}</span>
                  </div>
                  <div v-if="todayCourses.length" class="course-list">
                    <button
                      v-for="c in todayCourses"
                      :key="'t-' + c.id"
                      type="button"
                      class="course-card"
                      :class="['tone-' + toneOf(c.name), { 'is-live': liveCourse?.id === c.id }]"
                      @click="openCourse(c, $event)"
                    >
                      <span class="cc-time num">
                        <span>{{ c.startTime || "—" }}</span>
                        <span class="dur">{{ c.endTime || "—" }}</span>
                      </span>
                      <span class="cc-rule" aria-hidden="true" />
                      <span class="cc-body">
                        <span class="cc-name">{{ c.name }}</span>
                        <span class="cc-meta">
                          <template v-if="c.place">{{ c.place }}</template>
                          <template v-if="c.place && c.teacher"> · </template>
                          <template v-if="c.teacher">{{ c.teacher }}</template>
                          <template v-if="!c.place && !c.teacher">—</template>
                        </span>
                      </span>
                      <span v-if="liveCourse?.id === c.id" class="cc-tag">正在上课</span>
                      <span v-else-if="weekMatchClassOf(c.id) === 'unknown'" class="cc-tag cc-tag-warn">待核对</span>
                    </button>
                  </div>

                  <div v-else-if="todayStatus === 'no_class'" class="state-card">
                  <span class="state-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M4 8.5h16M7.5 4.5v3M16.5 4.5v3" />
                      <rect x="3.5" y="5.5" width="17" height="14.5" rx="3" />
                    </svg>
                  </span>
                  <p class="state-title">今天没有课</p>
                    <p class="state-desc">本周这一天没有安排，去课表看看整周计划</p>
                  </div>

                  <div v-else-if="todayStatus === 'week_unknown'" class="state-card">
                  <span class="state-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <circle cx="12" cy="12" r="8.5" />
                      <path d="M9.6 9.4a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .9-1 1.6v.3M12 16.6h.01" />
                    </svg>
                  </span>
                  <p class="state-title">周次待核对</p>
                    <p class="state-desc">有课程但周次无法解析，已在课表中保留</p>
                  </div>
                </div>
              </div>
            </section>
          </div>

          <!-- 课表 -->
          <div
            class="tab-pane-host"
            :class="{ active: tab === 'schedule' }"
            :style="{ transform: paneTransform('schedule') }"
            :aria-hidden="tab !== 'schedule'"
            :inert="tab !== 'schedule'"
          >
            <section
              class="schedule-pane"
              @touchstart.passive="onTouchStart"
              @touchmove="onTouchMove"
              @touchend.passive="onTouchEnd"
              @touchcancel.passive="onTouchEnd"
            >
              <div class="grid-outer">
                <div class="grid-wrap" ref="gridWrapEl">
                  <div class="slide-track" ref="slideTrackEl" :style="trackStyle">
                  <div
                    v-for="pane in weekPanes"
                    :key="pane.key"
                    class="week-pane"
                    :style="paneStyle"
                  >
                    <div
                      class="timetable"
                      :style="{
                        gridTemplateColumns: `30px repeat(7, minmax(0, 1fr))`,
                        gridTemplateRows: `${DAY_HEAD_PX}px repeat(${maxSection}, ${rowPx}px)`,
                        height: gridBodyH > 0 ? `${gridBodyH}px` : '100%',
                      }"
                    >
                      <div class="cell corner" />
                      <div
                        v-for="d in 7"
                        :key="pane.key + '-h' + d"
                        class="cell day-head"
                        :class="{ 'is-today': pane.key === 'cur' && d === todayWeekday() }"
                      >
                        <span class="wd">周{{ WEEKDAY[d] }}</span>
                        <span class="date">{{ fmtDate(pane.dates[d - 1]) }}</span>
                      </div>

                      <template v-for="sec in sectionRows" :key="pane.key + '-r' + sec">
                        <div class="cell side" :style="{ gridColumn: 1, gridRow: sec + 1 }">
                          <span class="sec-n">{{ sec }}</span>
                          <span class="sec-t">{{ SECTION_TIMES[sec]?.[0] }}</span>
                          <span class="sec-t">{{ SECTION_TIMES[sec]?.[1] }}</span>
                        </div>
                        <div
                          v-for="d in 7"
                          :key="pane.key + '-g' + sec + '-' + d"
                          class="cell slot"
                          :style="{ gridColumn: d + 1, gridRow: sec + 1 }"
                        />
                      </template>

                      <div
                        v-for="c in pane.courses"
                        :key="pane.key + '-' + c.id"
                        class="course"
                        :class="['tone-' + toneOf(c.name), { 'is-today': pane.key === 'cur' && liveCourse?.id === c.id }]"
                        :style="{
                          gridColumn: c.weekday + 1,
                          gridRow: `${c.start + 1} / ${c.end + 2}`,
                        }"
                        @click.stop="openCourse(c, $event)"
                      >
                        <p class="cname">{{ c.name }}</p>
                        <p v-if="c.teacher" class="cmeta">{{ c.teacher }}</p>
                        <p v-if="c.place" class="cmeta">{{ c.place }}</p>
                      </div>
                    </div>

                    <div
                      v-if="pane.week >= 1 && pane.week <= maxWeek && !pane.courses.length && snap.courses.length"
                      class="pane-empty"
                    >
                      本周暂无课程
                    </div>
                  </div>
                </div>

                  <div v-if="!snap.courses.length && !syncing" class="empty-hint">
                    <p>还没有课表</p>
                    <p class="sub">请点右上角设置登录教务同步</p>
                  </div>
                </div>
              </div>

              <div class="week-bar">
                <button type="button" class="week-btn" aria-label="上一周" :disabled="viewWeek <= 1" @click="prevWeek">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M14.5 5.5L8 12l6.5 6.5" />
                  </svg>
                </button>
                <button type="button" class="week-label num" @click="goCurrentWeek">
                  第 {{ viewWeek }} 周
                </button>
                <button type="button" class="week-btn" aria-label="下一周" :disabled="viewWeek >= maxWeek" @click="nextWeek">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M9.5 5.5L16 12l-6.5 6.5" />
                  </svg>
                </button>
              </div>

              <button
                v-if="showBackCurrent"
                type="button"
                class="fab-back"
                aria-label="返回本周"
                @click="goCurrentWeek"
              >
                <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M9 14L4 9l5-5" />
                  <path d="M20 20v-7a4 4 0 0 0-4-4H4" />
                </svg>
              </button>
            </section>
          </div>

          <!-- 出勤 -->
          <div
            class="tab-pane-host"
            :class="{ active: tab === 'attendance' }"
            :style="{ transform: paneTransform('attendance') }"
            :aria-hidden="tab !== 'attendance'"
            :inert="tab !== 'attendance'"
          >
            <section class="attendance-pane">
              <div class="att-head">
                <label class="att-term">
                  <span>学期</span>
                  <select :value="attendance.viewTerm" @change="onPickAttendanceTerm">
                    <option v-if="!attendance.terms.length" value="">学期未对应</option>
                    <option v-for="term in attendance.terms" :key="term.id" :value="term.id">
                      {{ term.id }}{{ term.schedule ? " · 当前课表" : "" }}
                    </option>
                  </select>
                </label>
                <p class="att-range">
                  {{ attendance.view.note || "还没有查询范围" }}
                </p>
                <div class="att-range-edit">
                  <label>开始<input v-model="rangeStartInput" type="date" /></label>
                  <label>结束<input v-model="rangeEndInput" type="date" /></label>
                  <button type="button" class="btn btn-soft" @click="confirmAttendanceRange">确认此范围</button>
                </div>
                <p v-if="!attendance.view.rangeConfirmed" class="att-flag">学期日期范围待确认</p>
              </div>

              <div class="att-summary-block">
                <p class="att-count" :class="{ 'is-alert': (attendance.view.confirmedAbsences || 0) > 0 }">
                  <template v-if="(attendance.view.confirmedAbsences || 0) > 0">已确认缺勤 {{ attendance.view.confirmedAbsences }}次</template>
                  <template v-else>{{ attendance.view.headline || "未同步" }}</template>
                </p>
                <p v-if="(attendance.view.pendingCount || 0) > 0" class="att-pending">待确认 {{ attendance.view.pendingCount }} 条</p>
              </div>

              <div class="seg" role="group" aria-label="出勤筛选">
                <button type="button" class="seg-btn" :class="{ active: absenceOnly }" :aria-pressed="absenceOnly" @click="absenceOnly = true">缺勤</button>
                <button type="button" class="seg-btn" :class="{ active: !absenceOnly }" :aria-pressed="!absenceOnly" @click="absenceOnly = false">全部记录</button>
              </div>

              <div v-if="visibleAttendance.length" class="course-list">
                <button
                  v-for="row in visibleAttendance"
                  :key="row.id"
                  type="button"
                  class="course-card att-card"
                  :class="{ 'is-alert': row.kind === 'confirmed_absence', open: openAttendanceId === row.id }"
                  @click="openAttendanceId = openAttendanceId === row.id ? '' : row.id"
                >
                  <span class="cc-body">
                    <span class="cc-name att-name">{{ row.title || "—" }}</span>
                    <span class="cc-meta">{{ row.date }} · {{ row.time || "—" }}</span>
                    <span v-if="openAttendanceId === row.id" class="att-detail">{{ row.detail }}</span>
                  </span>
                  <span class="cc-tag" :class="{ 'cc-tag-warn': row.kind === 'pending' || row.kind === 'unknown', 'cc-tag-alert': row.kind === 'confirmed_absence' }">
                    {{ row.status || "—" }}
                  </span>
                </button>
              </div>
              <div v-else class="state-card att-empty">
                <p class="state-title">{{ attendance.view.listEmpty || attendance.view.headline || "未同步" }}</p>
                <p class="state-desc">{{ attendance.view.note || "同步之后才会出现记录" }}</p>
                <p v-if="syncing" class="att-range">{{ snap.syncStage || message || "正在同步出勤" }}</p>
                <button type="button" class="btn btn-primary" :disabled="syncing" @click="syncData('attendance')">
                  {{ snap.syncButtonLabel || (snap.loggedIn ? "同步数据" : "登录并同步") }}
                </button>
              </div>
            </section>
          </div>
        </div>

        <!-- 移动端浮动 Dock：独立滑动胶囊，快速连点以最后一次为准 -->
        <nav
          v-if="!isWide"
          class="dock"
          :data-index="TAB_ORDER[tab]"
          aria-label="主导航"
        >
          <span class="dock-capsule" aria-hidden="true" />
          <button
            type="button"
            class="dock-tab"
            :class="{ active: tab === 'today' }"
            :aria-current="tab === 'today' ? 'page' : undefined"
            @click="switchTab('today')"
          >
            <span class="ico" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="8.2" />
                <path d="M12 7.6V12l3 1.8" />
              </svg>
            </span>
            <span>今日</span>
          </button>
          <button
            type="button"
            class="dock-tab"
            :class="{ active: tab === 'schedule' }"
            :aria-current="tab === 'schedule' ? 'page' : undefined"
            @click="switchTab('schedule')"
          >
            <span class="ico" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3.2" y="4.4" width="17.6" height="16.2" rx="3" />
                <path d="M3.2 9.4h17.6M8.2 2.6v3.6M15.8 2.6v3.6" />
                <path d="M7.4 13h3v3h-3zM13.6 13h3v3h-3z" />
              </svg>
            </span>
            <span>课表</span>
          </button>
          <button
            type="button"
            class="dock-tab"
            :class="{ active: tab === 'attendance' }"
            :aria-current="tab === 'attendance' ? 'page' : undefined"
            @click="switchTab('attendance')"
          >
            <span class="ico" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <path d="M8 4.5h8" />
                <rect x="5" y="3.2" width="14" height="17.6" rx="3" />
                <path d="M8.2 10.2h7.6M8.2 14h7.6M8.2 17.2h4.4" />
              </svg>
            </span>
            <span>出勤</span>
          </button>
        </nav>
      </div>

      <Teleport to="body">
        <div v-if="selected" class="sheet-root" @click.self="closeCourse">
          <div class="sheet-mask" @click="closeCourse" />
          <div class="sheet-panel" @click.stop role="dialog" aria-modal="true" :aria-label="selected.name">
            <div class="sheet-handle" />
            <h2 class="sheet-title">{{ selected.name }}</h2>
            <p class="sheet-sub">
              周{{ WEEKDAY[selected.weekday] }} · {{ selected.startTime }}–{{ selected.endTime }}
            </p>
            <div class="sheet-rows">
              <div class="sheet-row"><span class="k">教师</span><span class="v">{{ selected.teacher || "—" }}</span></div>
              <div class="sheet-row"><span class="k">地点</span><span class="v">{{ selected.place || "—" }}</span></div>
              <div class="sheet-row">
                <span class="k">节次</span>
                <span class="v">{{ selected.sectionLabel || `第${selected.start}-${selected.end}节` }}</span>
              </div>
              <div class="sheet-row"><span class="k">周次</span><span class="v">{{ weeksDisplay(selected.weeks) }}</span></div>
              <div class="sheet-row"><span class="k">时间</span><span class="v num">{{ selected.timeLabel || `${selected.startTime}–${selected.endTime}` }}</span></div>
            </div>
            <button type="button" class="sheet-close" @click="closeCourse">关闭</button>
          </div>
        </div>
      </Teleport>

      <Teleport to="body">
        <Transition name="menu-fade">
          <div v-if="settingsMenuOpen" class="menu-mask" @click="closeSettingsMenu" />
        </Transition>
        <Transition name="menu-pop">
          <div v-if="settingsMenuOpen" class="menu-panel" @click.stop role="menu" aria-label="设置菜单">
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('login')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" />
                  <path d="M10 17l5-5-5-5" />
                  <path d="M15 12H3" />
                </svg>
              </span>
              <span class="menu-label">登录与同步</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('appearance')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="4" />
                  <path d="M12 2.9v2M12 19.1v2M4.8 4.8l1.5 1.5M17.7 17.7l1.5 1.5M2.9 12h2M19.1 12h2M4.8 19.2l1.5-1.5M17.7 6.3l1.5-1.5" />
                </svg>
              </span>
              <span class="menu-label">外观</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('bg')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="4" width="18" height="16" rx="3" />
                  <circle cx="9" cy="10" r="1.6" />
                  <path d="M3.5 16.5l4.5-4 4 3 3-2 5.5 4" />
                </svg>
              </span>
              <span class="menu-label">选择背景</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('map')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M9 20l-6-3V4l6 3 6-3 6 3v13l-6-3-6 3z" />
                  <path d="M9 7v13M15 4v13" />
                </svg>
              </span>
              <span class="menu-label">教学楼地图</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('term')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="5" width="18" height="16" rx="3" />
                  <path d="M8 3v4M16 3v4M3 11h18" />
                </svg>
              </span>
              <span class="menu-label">校历起点</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
            <button type="button" class="menu-item" role="menuitem" @click="openSettingsPage('about')">
              <span class="menu-ico" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 10.5v6M12 7.6h.01" />
                </svg>
              </span>
              <span class="menu-label">关于</span>
              <span class="menu-chev" aria-hidden="true">›</span>
            </button>
          </div>
        </Transition>

        <Transition name="settings-panel">
          <div v-if="settingsPage" class="settings-root" @click.self="closeSettingsPage">
            <div class="settings-mask" @click="closeSettingsPage" />
            <div class="settings-panel" @click.stop role="dialog" aria-modal="true" :aria-label="settingsPageTitle">
              <header class="settings-head">
                <button type="button" class="icon-btn" aria-label="返回" @click="backToSettingsMenu">
                  <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M15 18l-6-6 6-6" />
                  </svg>
                </button>
                <h2>{{ settingsPageTitle }}</h2>
                <button type="button" class="icon-btn" aria-label="关闭" @click="closeSettingsPage">
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
                    <path d="M6 6l12 12M18 6L6 18" />
                  </svg>
                </button>
              </header>

              <div class="settings-body" :class="{ 'map-body': settingsPage === 'map' }">
                <!-- 官方登录 / 会话信息 -->
                <template v-if="settingsPage === 'login'">
                  <div class="card">
                    <h3 class="card-title">教务课表</h3>
                    <p class="card-desc">{{ snap.jwxtDetail || sessionLabel }}</p>
                    <div class="kv"><span class="k">学期</span><span class="v">{{ snap.term || "—" }}</span></div>
                    <div class="kv"><span class="k">当前教学周</span><span class="v">{{ liveTeachingWeek || "—" }}</span></div>
                    <div class="kv"><span class="k">上次同步</span><span class="v">{{ syncedText }}</span></div>
                    <div class="kv"><span class="k">课程数</span><span class="v">{{ snap.courses.length }}</span></div>
                  </div>
                  <div class="card">
                    <h3 class="card-title">教学辅助出勤</h3>
                    <p class="card-desc">{{ snap.hwsysDetail || "未登录" }}</p>
                    <div class="kv"><span class="k">查看中的学期</span><span class="v">{{ attendance.viewTerm || "—" }}</span></div>
                  </div>
                  <div v-if="message || snap.syncDetail" class="msg">{{ message || snap.syncDetail }}</div>
                  <button type="button" class="btn btn-primary" :disabled="syncing" @click="syncData('full')">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                      <path d="M21 12a9 9 0 0 1-15.5 6.3L4 16" />
                      <path d="M3 12a9 9 0 0 1 15.5-6.3L20 8" />
                      <path d="M4 16v4H8M20 8V4h-4" />
                    </svg>
                    {{ syncing ? (snap.syncStage || "同步中…") : (snap.syncButtonLabel || "登录并同步") }}
                  </button>
                  <p v-if="attendance.storageWarning" class="tip">这台设备暂时没法安全保存登录，重启后需要重新登录。</p>
                  <p class="tip">缺登录时会依次打开教务和教学辅助系统的官网。同步数据只更新课表和出勤，不会更新 App。</p>
                </template>

                <!-- 外观 -->
                <template v-else-if="settingsPage === 'appearance'">
                  <div class="card">
                    <h3 class="card-title">主题</h3>
                    <p class="card-desc">选择浅色、深色，或跟随手机系统。</p>
                    <div class="seg" role="group" aria-label="主题模式">
                      <button
                        v-for="opt in THEME_OPTIONS"
                        :key="opt.id"
                        type="button"
                        class="seg-btn"
                        :class="{ active: themeMode === opt.id }"
                        :aria-pressed="themeMode === opt.id"
                        @click="pickThemeMode(opt.id)"
                      >
                        {{ opt.label }}
                      </button>
                    </div>
                    <div class="kv">
                      <span class="k">当前外观</span>
                      <span class="v">{{ resolvedTheme === "dark" ? "深色" : "浅色" }}</span>
                    </div>
                  </div>
                </template>

                <!-- 背景 -->
                <template v-else-if="settingsPage === 'bg'">
                  <div class="card">
                    <h3 class="card-title">课表背景</h3>
                    <p class="card-desc">从相册选图后按屏幕比例裁切，壁纸覆盖整个 App；卡片始终为实色，保证可读。</p>
                    <button type="button" class="btn btn-soft" @click="pickBg">选择并裁切壁纸</button>
                    <button v-if="hasBg" type="button" class="btn btn-plain" @click="clearBg">清除背景</button>
                    <label v-if="hasBg" class="field">
                      <span>背景浓度 {{ Math.round(opacityLocal * 100) }}%</span>
                      <input
                        class="range"
                        type="range"
                        min="0.25"
                        max="0.98"
                        step="0.01"
                        :value="opacityLocal"
                        @input="onOpacityInput"
                      />
                    </label>
                  </div>
                </template>

                <!-- 校历起点 -->
                <template v-else-if="settingsPage === 'term'">
                  <div class="card">
                    <h3 class="card-title">校历起点（第 1 周周一）</h3>
                    <p class="card-desc">
                      默认按「含 9/1 或 3/1 的那一周」推算；与实际开学日不同时，在这里指定真实的第一周周一，
                      今日页、周课表与小组件会一起使用这个结果。
                    </p>
                    <div class="kv">
                      <span class="k">当前来源</span>
                      <span class="v">{{ snap.termStartSource === "CALIBRATED" ? "用户校准" : "按月份推算" }}</span>
                    </div>
                    <input v-model="termStartInput" class="input" type="date" />
                    <button type="button" class="btn btn-soft" @click="saveTermStart">保存校历起点</button>
                    <button type="button" class="btn btn-plain" @click="resetTermStart">恢复按月份推算</button>
                  </div>
                </template>

                <!-- 教学楼地图 -->
                <template v-else-if="settingsPage === 'map'">
                  <div
                    ref="mapScrollEl"
                    class="map-scroll"
                    @touchstart.passive="onMapTouchStart"
                    @touchmove="onMapTouchMove"
                    @touchend.passive="onMapTouchEnd"
                    @touchcancel.passive="onMapTouchEnd"
                  >
                    <img
                      class="map-img"
                      :src="campusMapUrl"
                      alt="教学楼地图"
                      draggable="false"
                      :style="{ width: `${mapScale * 100}%` }"
                    />
                  </div>
                  <div class="map-zoom-bar">
                    <button type="button" class="map-zoom-btn" aria-label="缩小" :disabled="mapScale <= MAP_MIN" @click="zoomMapBy(-0.4)">−</button>
                    <span class="map-zoom-label">{{ Math.round(mapScale * 100) }}%</span>
                    <button type="button" class="map-zoom-btn" aria-label="放大" :disabled="mapScale >= MAP_MAX" @click="zoomMapBy(0.4)">+</button>
                  </div>
                </template>

                <!-- 关于 / 更新 -->
                <template v-else>
                  <div class="card">
                    <h3 class="card-title">SZTU教务</h3>
                    <div class="kv"><span class="k">版本</span><span class="v">{{ snap.version || ota.localVersionName || "2.1.0" }}</span></div>
                    <div class="kv"><span class="k">学期</span><span class="v">{{ snap.term || "—" }}</span></div>
                    <div class="kv"><span class="k">上次同步</span><span class="v">{{ syncedText }}</span></div>
                  </div>
                  <div class="card">
                    <div class="section-head" style="padding: 0">
                      <h3 class="card-title">在线更新</h3>
                      <button
                        type="button"
                        class="btn btn-soft btn-mini"
                        :disabled="ota.checking || ota.downloading"
                        @click="checkAppUpdate"
                      >
                        {{ ota.checking ? "检查中…" : "检查更新" }}
                      </button>
                    </div>
                    <p v-if="ota.hasUpdate" class="card-desc">
                      发现 {{ ota.remoteVersionName }}（{{ ota.remoteVersionCode }}）
                      <span v-if="ota.changelog"> · {{ ota.changelog }}</span>
                    </p>
                    <p v-else-if="ota.error" class="card-desc err">{{ ota.error }}</p>
                    <p v-else class="card-desc">当前已是最新，或尚未检查。这里的检查更新只看 App 版本，不会同步课表或出勤。</p>
                    <div v-if="ota.downloading" class="ota-progress">
                      <div class="ota-bar" :style="{ width: `${Math.max(ota.progress, 3)}%` }" />
                      <span>下载中 {{ ota.progress }}%（若长时间不动请用浏览器）</span>
                    </div>
                    <button
                      v-if="ota.hasUpdate"
                      type="button"
                      class="btn btn-primary"
                      :disabled="ota.downloading"
                      @click="downloadAppUpdate"
                    >
                      {{ ota.downloading ? "下载中…" : "下载并安装" }}
                    </button>
                    <button
                      v-if="ota.hasUpdate || ota.error"
                      type="button"
                      class="btn btn-plain"
                      @click="openApkBrowser"
                    >
                      浏览器下载
                    </button>
                  </div>
                  <p class="tip">
                    深圳技术大学教务集成：官方 SSO 登录 + 课表同步 + 桌面小组件。非本周时课表页右下角可一键返回本周。
                  </p>
                </template>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>
    </div>
  </UApp>
</template>
