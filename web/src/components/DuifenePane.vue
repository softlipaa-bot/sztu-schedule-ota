<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  formatClock,
  formatRemain,
  phaseLabel,
  typeLabel,
  watchPhaseLabel,
  type DuifeneState,
} from "@/lib/duifene";

const props = defineProps<{
  state: DuifeneState;
  active: boolean;
  chooseCourse: (courseId: string, classId: string) => string;
}>();

const emit = defineEmits<{
  login: [account: string, password: string, save: boolean];
  logout: [];
  refreshCourses: [];
  select: [courseId: string, classId: string];
  start: [];
  stop: [];
  webPassword: [password: string, save: boolean];
  clearRecords: [];
}>();

const loginOpen = ref(false);
const settingsOpen = ref(false);
const webOpen = ref(false);
const clearOpen = ref(false);
const clearing = ref(false);
const courseOpen = ref(false);
const courseShown = ref(false);
const courseError = ref("");
const courseRoot = ref<HTMLElement | null>(null);
const courseList = ref<HTMLElement | null>(null);
const courseClip = ref<HTMLElement | null>(null);
const clipHeight = ref("0px");
const nowMs = ref(Date.now());
let clockTimer = 0;
let reduceMotion = false;
const account = ref("");
const password = ref("");
const savePassword = ref(false);
const webPassword = ref("");
const saveWebPassword = ref(false);

function closeSheets() {
  loginOpen.value = false;
  settingsOpen.value = false;
  webOpen.value = false;
  clearOpen.value = false;
  password.value = "";
  webPassword.value = "";
}

const courseBusy = computed(() => ["starting", "running", "retrying", "stopping"].includes(props.state.watchPhase));
const selectedCourse = computed(() => props.state.courses.find((item) => item.key === props.state.selectedKey) || null);
const activityRemain = computed(() => {
  if (props.state.activityEndsAt) return Math.max(0, Math.floor((props.state.activityEndsAt - nowMs.value) / 1000));
  return props.state.remainSeconds;
});
const listenRemain = computed(() => {
  if (props.state.listenEndsAt) return Math.max(0, Math.floor((props.state.listenEndsAt - nowMs.value) / 1000));
  return props.state.listenRemainSeconds;
});

function syncClock() {
  window.clearInterval(clockTimer);
  clockTimer = 0;
  if (!props.active || document.visibilityState === "hidden") return;
  nowMs.value = Date.now();
  clockTimer = window.setInterval(() => {
    nowMs.value = Date.now();
  }, 1000);
}

function closeCourses() {
  if (!courseOpen.value && !courseShown.value) return;
  courseOpen.value = false;
  const clip = courseClip.value;
  if (!clip || reduceMotion) {
    courseShown.value = false;
    clipHeight.value = "0px";
    return;
  }
  clipHeight.value = `${clip.offsetHeight}px`;
  requestAnimationFrame(() => {
    clipHeight.value = "0px";
  });
}

function openCourses() {
  courseShown.value = true;
  courseOpen.value = true;
  courseError.value = "";
  nextTick(() => {
    const list = courseList.value;
    const clip = courseClip.value;
    const selected = list?.querySelector<HTMLElement>("[aria-checked='true']");
    selected?.scrollIntoView({ block: "nearest" });
    if (!clip) return;
    if (reduceMotion) {
      clipHeight.value = "auto";
      return;
    }
    clipHeight.value = "0px";
    requestAnimationFrame(() => {
      clipHeight.value = `${clip.scrollHeight}px`;
    });
  });
}

function toggleCourses() {
  if (courseBusy.value || !props.state.loggedIn) return;
  if (courseOpen.value) closeCourses();
  else openCourses();
}

function onClipEnd(event: TransitionEvent) {
  if (event.propertyName !== "height" || event.target !== courseClip.value) return;
  if (courseOpen.value) clipHeight.value = "auto";
  else {
    courseShown.value = false;
    clipHeight.value = "0px";
  }
}

function choose(courseId: string, classId: string) {
  const error = props.chooseCourse(courseId, classId);
  if (error) {
    courseError.value = error;
    return;
  }
  courseError.value = "";
  closeCourses();
}

function onCourseKey(event: KeyboardEvent) {
  if (!courseOpen.value) {
    if (event.key === "ArrowDown" || event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      openCourses();
    }
    return;
  }
  const radios = [...(courseList.value?.querySelectorAll<HTMLElement>("[role='radio']") || [])];
  const index = radios.findIndex((node) => node === document.activeElement);
  if (event.key === "Escape") {
    event.preventDefault();
    closeCourses();
    courseRoot.value?.querySelector<HTMLElement>(".df-course-trigger")?.focus();
  } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    event.preventDefault();
    const next = event.key === "ArrowDown" ? Math.min(radios.length - 1, index + 1) : Math.max(0, index - 1);
    radios[next]?.focus();
  }
}

function onPointerDown(event: PointerEvent) {
  if (!courseOpen.value) return;
  const target = event.target as Node | null;
  if (target && courseRoot.value?.contains(target)) return;
  closeCourses();
}

function confirmClear() {
  if (clearing.value || !props.state.records.length) return;
  clearing.value = true;
  emit("clearRecords");
  clearing.value = false;
  clearOpen.value = false;
}

watch(
  () => props.active,
  (active) => {
    if (!active) {
      closeSheets();
      courseOpen.value = false;
      courseShown.value = false;
      clipHeight.value = "0px";
    }
    syncClock();
  },
);

watch(
  () => props.state.sessionEpoch,
  () => {
    courseOpen.value = false;
    courseShown.value = false;
    clipHeight.value = "0px";
    courseError.value = "";
  },
);

onMounted(() => {
  reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  document.addEventListener("pointerdown", onPointerDown);
  document.addEventListener("visibilitychange", syncClock);
  syncClock();
});

function submitLogin() {
  const name = account.value.trim();
  const secret = password.value;
  if (!name || !secret) return;
  emit("login", name, secret, savePassword.value);
  password.value = "";
  loginOpen.value = false;
}

function submitWeb() {
  const secret = webPassword.value;
  if (!secret) return;
  emit("webPassword", secret, saveWebPassword.value);
  webPassword.value = "";
  webOpen.value = false;
}

function consumeBack(): boolean {
  if (!props.active) return false;
  if (loginOpen.value || settingsOpen.value || webOpen.value || clearOpen.value) {
    closeSheets();
    return true;
  }
  if (courseOpen.value) {
    closeCourses();
    return true;
  }
  return false;
}

window.__duifeneConsumeBack = consumeBack;
onBeforeUnmount(() => {
  if (window.__duifeneConsumeBack === consumeBack) delete window.__duifeneConsumeBack;
  document.removeEventListener("pointerdown", onPointerDown);
  document.removeEventListener("visibilitychange", syncClock);
  window.clearInterval(clockTimer);
});

function sessionText() {
  if (!props.state.loggedIn) return "未登录";
  if (props.state.needsWebPassword || props.state.session === "web_needed") return "网页凭据待补充";
  if (props.state.session === "active") return "App 会话可用";
  return "会话待确认";
}
</script>

<template>
  <section class="duifene-pane">
    <div class="duifene-scroll">
      <article class="card">
        <div class="df-card-top">
          <h2 class="card-title">账号</h2>
          <button type="button" class="btn btn-mini btn-plain" @click="settingsOpen = true">参数</button>
        </div>
        <p v-if="!state.loggedIn" class="card-desc">登录后只读取这个学生已加入的教学班。不会改动教务或出勤的登录。</p>
        <template v-else>
          <div class="kv"><span class="k">账号</span><span class="v">{{ state.accountMasked || "已登录" }}</span></div>
          <div class="kv"><span class="k">会话</span><span class="v">{{ sessionText() }}</span></div>
          <p v-if="state.userName" class="card-desc">{{ state.userName }}</p>
        </template>
        <p v-if="state.storageWarning" class="msg">这台设备暂时不能安全保存凭证，退出后需要重新登录。</p>
        <p v-if="state.message" class="card-desc">{{ state.message }}</p>
        <button v-if="!state.loggedIn" type="button" class="btn btn-primary" @click="loginOpen = true">登录对分易</button>
        <div v-else class="row-2">
          <button type="button" class="btn btn-soft" @click="loginOpen = true">切换账号</button>
          <button type="button" class="btn btn-plain" @click="emit('logout')">退出</button>
        </div>
        <button v-if="state.needsWebPassword" type="button" class="btn btn-primary" @click="webOpen = true">补充网页登录</button>
      </article>

      <article class="card" ref="courseRoot">
        <div class="df-card-top">
          <h2 class="card-title">课程</h2>
          <button type="button" class="btn btn-mini btn-plain" :disabled="!state.loggedIn || courseBusy || state.coursesPhase === 'loading'" @click="emit('refreshCourses')">
            {{ state.coursesPhase === "loading" || state.coursesPhase === "refreshing" ? "读取中" : "刷新" }}
          </button>
        </div>
        <p v-if="!state.loggedIn" class="card-desc">登录后才能选择课程</p>
        <p v-else-if="state.coursesPhase === 'loading' && !state.courses.length" class="card-desc">正在读取已加入的课程</p>
        <p v-else-if="state.coursesPhase === 'error' && !state.courses.length" class="card-desc">{{ state.coursesError || "读取失败" }}</p>
        <p v-else-if="(state.coursesPhase === 'empty' || !state.courses.length) && state.coursesPhase !== 'loading'" class="card-desc">没有已加入的课程</p>
        <div v-else class="df-picker" @keydown="onCourseKey">
          <button
            type="button"
            class="df-course-trigger"
            :aria-expanded="courseOpen"
            aria-controls="df-course-list"
            :disabled="courseBusy"
            @click="toggleCourses"
          >
            <span class="df-course-copy">
              <span class="df-course-kicker">当前课程</span>
              <span class="df-course-name">{{ selectedCourse?.name || "选择要签到的课程" }}</span>
              <span class="df-course-hint">{{ courseBusy ? "停止监听后可切换课程" : "点击切换课程" }}</span>
            </span>
            <span class="df-chevron" :class="{ open: courseOpen }" aria-hidden="true">⌄</span>
          </button>
          <div
            id="df-course-list"
            ref="courseClip"
            class="df-course-clip"
            :style="{ height: courseShown ? clipHeight : '0px' }"
            :inert="!courseOpen"
            @transitionend="onClipEnd"
          >
            <div
              v-if="courseShown"
              ref="courseList"
              class="df-course-list"
              role="radiogroup"
              aria-label="选择课程"
            >
              <button
                v-for="course in state.courses"
                :key="course.key"
                type="button"
                class="df-course"
                role="radio"
                :aria-checked="state.selectedKey === course.key"
                :class="{ active: state.selectedKey === course.key }"
                :disabled="courseBusy"
                @click="choose(course.courseId, course.classId)"
              >
                <span class="df-check-mark" aria-hidden="true">{{ state.selectedKey === course.key ? "✓" : "" }}</span>
                <span class="df-course-name">{{ course.name }}</span>
              </button>
            </div>
          </div>
        </div>
        <p v-if="state.coursesError && state.courses.length" class="card-desc">{{ state.coursesError }}</p>
        <p v-if="courseError" class="card-desc">{{ courseError }}</p>
        <button v-if="state.coursesPhase === 'error' && !state.courses.length" type="button" class="btn btn-plain" @click="emit('refreshCourses')">重试</button>
      </article>

      <article class="card">
        <h2 class="card-title">监听</h2>
        <div class="kv"><span class="k">课程</span><span class="v">{{ selectedCourse?.name || "未选择" }}</span></div>
        <div class="kv"><span class="k">状态</span><span class="v">{{ watchPhaseLabel(state.watchPhase, state.willRetry) }}</span></div>
        <div class="kv"><span class="k">最近成功查询</span><span class="v">{{ formatClock(state.lastQueryAt) }}</span></div>
        <div class="kv"><span class="k">活动类型</span><span class="v">{{ typeLabel(state.activityType) }}</span></div>
        <div class="kv"><span class="k">活动剩余</span><span class="v">{{ formatRemain(activityRemain) }}</span></div>
        <div class="kv"><span class="k">本次剩余</span><span class="v">{{ state.watching ? formatRemain(listenRemain) : `最长 ${state.settings.maxListenMinutes} 分钟` }}</span></div>
        <p class="card-desc">离开这个页面不会停止监听。到时间、断网、登录失效或系统限制时会停，并显示实际状态。</p>
        <button v-if="!state.watching" type="button" class="btn btn-primary" :disabled="!state.canStart" @click="emit('start')">开始监听</button>
        <button v-else type="button" class="btn btn-plain" :disabled="state.watchPhase === 'stopping'" @click="emit('stop')">停止监听</button>
      </article>

      <article class="card">
        <h2 class="card-title">活动</h2>
        <p class="df-phase" :data-phase="state.activityPhase">{{ phaseLabel(state.activityPhase) }}</p>
        <p class="card-desc">{{ state.activitySummary || "未发现活动" }}</p>
      </article>

      <article class="card">
        <div class="df-card-top">
          <h2 class="card-title">最近记录</h2>
          <button type="button" class="df-text-btn" :disabled="!state.records.length || clearing" @click="clearOpen = true">清空</button>
        </div>
        <p v-if="!state.records.length" class="card-desc">暂无最近记录</p>
        <div v-else class="df-records">
          <div v-for="row in [...state.records].reverse()" :key="row.eventId" class="df-record">
            <span class="df-record-time">{{ formatClock(row.at) }}</span>
            <span class="df-record-phase">{{ phaseLabel(row.phase) }}</span>
            <span class="df-record-course">{{ row.course || row.summary }}</span>
          </div>
        </div>
      </article>
    </div>

    <Teleport to="body">
    <div v-if="loginOpen && active" class="sheet-root" @click.self="closeSheets">
      <div class="sheet-mask" @click="closeSheets" />
      <div class="sheet-panel" role="dialog" aria-modal="true" aria-label="登录对分易" @click.stop>
        <div class="sheet-handle" />
        <h2 class="sheet-title">登录对分易</h2>
        <p class="sheet-sub">只用于对分易，不会写入教务或出勤。</p>
        <label class="field">账号<input v-model="account" class="input" autocomplete="off" maxlength="64" /></label>
        <label class="field">密码<input v-model="password" class="input" type="password" autocomplete="off" maxlength="128" /></label>
        <label class="df-check"><input v-model="savePassword" type="checkbox" />在这台设备上保存密码</label>
        <button type="button" class="btn btn-primary" @click="submitLogin">登录</button>
        <button type="button" class="sheet-close" @click="closeSheets">关闭</button>
      </div>
    </div>

    <div v-if="webOpen && active" class="sheet-root" @click.self="closeSheets">
      <div class="sheet-mask" @click="closeSheets" />
      <div class="sheet-panel" role="dialog" aria-modal="true" aria-label="网页登录" @click.stop>
        <div class="sheet-handle" />
        <h2 class="sheet-title">补充网页登录</h2>
        <p class="sheet-sub">读取数字码或二维码时需要同一学生的网页会话。不保存则只在本次使用。</p>
        <label class="field">密码<input v-model="webPassword" class="input" type="password" autocomplete="off" maxlength="128" /></label>
        <label class="df-check"><input v-model="saveWebPassword" type="checkbox" />在这台设备上保存密码</label>
        <button type="button" class="btn btn-primary" @click="submitWeb">确认</button>
        <button type="button" class="sheet-close" @click="closeSheets">关闭</button>
      </div>
    </div>

    <div v-if="clearOpen && active" class="sheet-root" @click.self="closeSheets">
      <div class="sheet-mask" @click="closeSheets" />
      <div class="sheet-panel" role="dialog" aria-modal="true" aria-label="清空最近记录" @click.stop>
        <div class="sheet-handle" />
        <h2 class="sheet-title">清空最近记录？</h2>
        <p class="sheet-sub">仅清除本机显示记录，不影响已签到结果和当前监听。</p>
        <div class="row-2">
          <button type="button" class="btn btn-plain" @click="closeSheets">取消</button>
          <button type="button" class="btn btn-primary" @click="confirmClear">清空</button>
        </div>
      </div>
    </div>

    <div v-if="settingsOpen && active" class="sheet-root" @click.self="closeSheets">
      <div class="sheet-mask" @click="closeSheets" />
      <div class="sheet-panel" role="dialog" aria-modal="true" aria-label="对分易设置" @click.stop>
        <div class="sheet-handle" />
        <h2 class="sheet-title">监听参数</h2>
        <p class="sheet-sub">这些是当前生效值，移植自 bytehola/duifene_auto_sign（{{ state.settings.sourceCommit.slice(0, 7) }}）。</p>
        <div class="kv"><span class="k">时间窗</span><span class="v">{{ state.settings.monitorStartHour }}:00–{{ state.settings.monitorEndHour }}:00</span></div>
        <p class="card-desc">只在这个时段发现活动，按北京时间计算。</p>
        <div class="kv"><span class="k">发现间隔</span><span class="v">{{ state.settings.pollMinS }}–{{ state.settings.pollMaxS }} 秒</span></div>
        <p class="card-desc">查找新活动的等待，不包含网络耗时。</p>
        <div class="kv"><span class="k">取码等待</span><span class="v">{{ state.settings.numericPollS }} 秒</span></div>
        <p class="card-desc">只有缺数字码时才使用。失败后等待 {{ state.settings.numericBackoff }} 秒，封顶 2 秒。</p>
        <div class="kv"><span class="k">截止优先</span><span class="v">最后 {{ state.settings.numericPriorityS }} 秒</span></div>
        <p class="card-desc">临近截止时先取材料，暂缓发现查询。</p>
        <div class="kv"><span class="k">人数门槛</span><span class="v">{{ Math.round(state.settings.headcountRatio * 100) }}%</span></div>
        <p class="card-desc">小班少于 {{ state.settings.smallClassMax }} 人时，活动开始 {{ state.settings.smallClassDelayS }} 秒后再提交。</p>
        <div class="kv"><span class="k">单次最长</span><span class="v">{{ state.settings.maxListenMinutes }} 分钟</span></div>
        <p class="card-desc">到点自动停止。系统也可能更早收回前台服务。</p>
        <button type="button" class="sheet-close" @click="closeSheets">关闭</button>
      </div>
    </div>
    </Teleport>
  </section>
</template>
