declare global {
  interface Window {
    __duifeneConsumeBack?: () => boolean;
  }
}

export type DuifeneCourse = {
  courseId: string;
  classId: string;
  name: string;
  key: string;
};

export type DuifeneRecord = {
  eventId: number;
  at: number;
  phase: string;
  summary: string;
  course: string;
};

export type DuifeneState = {
  loggedIn: boolean;
  accountMasked: string;
  userName: string;
  session: string;
  storageWarning: boolean;
  coursesPhase: string;
  coursesError: string;
  courses: DuifeneCourse[];
  selectedKey: string;
  watching: boolean;
  watchPhase: string;
  activityPhase: string;
  activitySummary: string;
  activityType: string;
  remainSeconds: number | null;
  listenRemainSeconds: number | null;
  activityEndsAt: number | null;
  listenEndsAt: number | null;
  willRetry: boolean;
  sessionEpoch: number;
  lastQueryAt: number;
  canStart: boolean;
  needsWebPassword: boolean;
  locationPermission: string;
  records: DuifeneRecord[];
  message: string;
  settings: {
    monitorStartHour: number;
    monitorEndHour: number;
    pollMinS: number;
    pollMaxS: number;
    numericPollS: number;
    numericBackoff: string;
    numericPriorityS: number;
    smallClassMax: number;
    smallClassDelayS: number;
    headcountRatio: number;
    staleSignMinutes: number;
    cooldownSeconds: number;
    maxListenMinutes: number;
    sourceCommit: string;
  };
};

export function blankDuifene(): DuifeneState {
  return {
    loggedIn: false,
    accountMasked: "",
    userName: "",
    session: "none",
    storageWarning: false,
    coursesPhase: "idle",
    coursesError: "",
    courses: [],
    selectedKey: "",
    watching: false,
    watchPhase: "idle",
    activityPhase: "none",
    activitySummary: "未发现活动",
    activityType: "",
    remainSeconds: null,
    listenRemainSeconds: null,
    activityEndsAt: null,
    listenEndsAt: null,
    willRetry: false,
    sessionEpoch: 0,
    lastQueryAt: 0,
    canStart: false,
    needsWebPassword: false,
    locationPermission: "unknown",
    records: [],
    message: "",
    settings: {
      monitorStartHour: 6,
      monitorEndHour: 23,
      pollMinS: 3,
      pollMaxS: 5,
      numericPollS: 0.2,
      numericBackoff: "0.4/0.8/1.6/2",
      numericPriorityS: 5,
      smallClassMax: 5,
      smallClassDelayS: 5,
      headcountRatio: 0.2,
      staleSignMinutes: 30,
      cooldownSeconds: 300,
      maxListenMinutes: 120,
      sourceCommit: "",
    },
  };
}

export function parseDuifene(raw: string | null | undefined): DuifeneState {
  const base = blankDuifene();
  if (!raw) return base;
  try {
    const parsed = JSON.parse(raw) as Partial<DuifeneState>;
    return {
      ...base,
      ...parsed,
      courses: Array.isArray(parsed.courses) ? parsed.courses : [],
      records: Array.isArray(parsed.records) ? parsed.records.slice(-100) : [],
      settings: { ...base.settings, ...(parsed.settings ?? {}) },
    };
  } catch {
    return base;
  }
}

export function phaseLabel(phase: string): string {
  switch (phase) {
    case "waiting_material":
      return "等待材料";
    case "waiting_condition":
      return "等待提交条件";
    case "submitting":
      return "提交中";
    case "success":
      return "成功";
    case "already":
      return "已签到";
    case "pending_confirm":
      return "待确认";
    case "failed":
      return "失败";
    case "expired":
      return "已过期";
    case "voided":
      return "已作废";
    default:
      return "未发现活动";
  }
}

export function typeLabel(type: string): string {
  switch (type) {
    case "1":
      return "数字码";
    case "2":
      return "二维码";
    case "3":
      return "定位";
    case "4":
      return "汉字";
    default:
      return type ? "未知类型" : "—";
  }
}

export function formatRemain(seconds: number | null): string {
  if (seconds == null) return "—";
  const whole = Math.max(0, Math.floor(seconds));
  const minutes = Math.floor(whole / 60);
  const rest = whole % 60;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}

export function watchPhaseLabel(phase: string, willRetry: boolean): string {
  if (willRetry || phase === "retrying") return "暂时失败，会自动重试";
  switch (phase) {
    case "starting":
      return "正在开始";
    case "running":
      return "监听中";
    case "stopping":
      return "正在停止";
    case "auth_required":
      return "需要重新登录";
    case "permission_denied":
      return "没有权限";
    case "system_timeout":
      return "系统已停止";
    case "web_stopped":
      return "网页材料读取失败";
    default:
      return "未监听";
  }
}

export function formatClock(ms: number): string {
  if (!ms) return "还没有成功查询";
  const date = new Date(ms);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
