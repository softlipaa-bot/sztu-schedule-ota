# AI_CONTEXT — SZTU教务 (深技大课表)

> Machine-oriented. Read fully before changing this app.

## Identity

- **Folder:** `D:\自研app\SZTU教务`
- **App name (strings):** `SZTU教务` / widget same
- **applicationId:** `com.sztu.schedule`
- **namespace:** `com.sztu.schedule`
- **versionName:** `2.2.1` (versionCode 67)
- **Purpose:** Shenzhen Technology University (SZTU) academic schedule via **official SSO → jwxt HTML scrape**, local cache, HyperOS/MIUI home-screen widget, keep-alive for widget refresh.
- **NOT this app:** Electron 考勤 (`SZTU考勤系统`), Flet 签到助手, 连点助手.

## Stack

- Android Kotlin + Gradle Kotlin DSL（AGP 8.7.3 / Kotlin 2.0.21，minSdk 26 / targetSdk 36）
- WebView UI（`web/` → Vite 构建到 `app/src/main/assets/www`）
- Jsoup HTML 解析（`ScheduleHtmlParser.kt`）
- **没有 Chaquopy / 没有 Python**：2.1.0 起 Python 引擎与 Chaquopy 插件、pip 依赖全部删除
- OTA helpers under `ota/`

## Canonical URLs (do not invent alternatives)

```
SSO:      https://auth.sztu.edu.cn/idp/authcenter/ActionAuthChain?entityId=jiaowu
SCHEDULE: https://jwxt.sztu.edu.cn/jsxsd/xskb/xskb_list.do
MAIN:     https://jwxt.sztu.edu.cn/jsxsd/framework/xsMain.jsp
```

Login success heuristic (Softlipaa LoginActivity): URL on `jwxt.sztu.edu.cn` with path containing `jsxsd|xsMain|framework|xskb`, NOT `LoginToXk`, AND cookie header contains `JSESSIONID`. Strip `; wv` from WebView UA.

## Layout (important paths)

```
SZTU教务/
  README.md                 # human
  AI_CONTEXT.md             # this file
  settings.gradle.kts
  app/
    src/main/java/com/sztu/schedule/
      MainActivity.kt              # WebView 外壳 + ScheduleBridge（含仅主题用的 setSystemBars）
      ScheduleApp.kt               # 小组件闹钟 + 遗留考勤通知清理
      CropBackgroundActivity.kt    # 自有裁切页（主题跟随 AppearanceStore）
      OtaUpdater.kt
      data/  ScheduleRepository / ScheduleHtmlParser / ScheduleScraper / Models
             TeachingWeek / CookieJar / SecretStore / BackgroundStore
             BackgroundImageEndpoint / AppearanceStore
      login/LoginActivity.kt
      widget/ ScheduleWidget(ScheduleWidgetReceiver/ScheduleWidgetUpdater)
              WidgetRefreshScheduler(WidgetBootReceiver) / WidgetPinHelper
  web/
    index.html                # 首屏前定 <html data-theme>（防闪色）
    src/App.vue               # 今日 / 课表 / 设置 全部 UI
    src/lib/theme.ts          # 浅色 / 深色 / 跟随系统
    src/styles/tokens.css     # 语义设计令牌（唯一颜色来源）
    src/styles/app.css        # 组件样式
  tools/                      # run-kotlin-tests.bat / test-web-startup.py / test-web-ui.py
  ota/
  gradlew.bat
```

## Build

```bat
set ANDROID_HOME=D:\MovedFromC\AppDataLocal\Android\Sdk
cd /d D:\自研app\SZTU教务\web && npm run build && cd ..
gradlew.bat assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

顺序不能反：**先 `npm run build`（web → native assets），再 `assembleDebug`**，否则打进旧前端。

## Behaviors agents must preserve

1. Login is WebView SSO only; schedule fetch may use WebView scrape or HTTP with persisted cookie (`ScheduleRepository.persistWebViewCookies`).
2. Widget shows 上节/这节/下节. Refresh sources: user button, `MainActivity.onResume`, `ScheduleApp` start, **section-boundary AlarmManager** (`WidgetRefreshScheduler`), boot/time receivers. Do not remove the scheduler — `updatePeriodMillis=0` alone will freeze slots. Boundaries must include **all** `SectionTimes` starts/ends (esp. `1-3` → **11:00**) **plus today's course times**; old hard-coded list missed 11:00 and left Wednesday morning 单片机 stuck as「这节」until 11:40.
3. Parser targets `#timetable` / `.kbcontent` (skip `.kbcontent1`); tooltip title supplies classroom.

## 考勤（对分易）已于 2.1.0 整体移除

用户 2026-10-31 授权：取消考勤 Tab 与全部内容。**不要恢复或部分恢复**：

- 已删除：`duifene/`（AttendanceCoordinator / DuifeneJsBridge / DuifeneRuntime / WatchForegroundService / WatchSession）、
  `app/src/main/python/`（含 duifene_core / bridge / android_entry / attendance_* / headless_shim）、
  `app/src/test/python/`、`web/src/components/AttendancePane.vue`、`web/src/lib/duifene.ts`、
  `res/drawable/ic_stat_attendance.xml`、Chaquopy 插件与 pip 依赖、manifest 里的 `.duifene.WatchForegroundService`。
- 已删除的权限（仅考勤使用，无小组件调用点）：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、
  `WAKE_LOCK`、`POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- **保留**：`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`（小组件节次闹钟 / boot receiver）、
  小组件接收器、FileProvider、`REQUEST_INSTALL_PACKAGES`（OTA）。
- 升级兼容：`ScheduleApp.LegacyAttendanceCleanup` 在升级后首次进程启动时 `cancel(1001)` 并删除通知渠道
  `sztu_watch`（一次性，prefs `sztu_cleanup`）。**不得为此新增任何后台服务。**
- 不要读取/迁移 Electron 项目的 `duifenyi.ini`；备份规则里对它的 exclude 只是历史残留，无害。

## 课表周次与今日页（审查报告 J02）

- `ScheduleClock.matchWeek()` 返回三态 `MATCH / NO_MATCH / UNKNOWN`；空周次或无法解析 → `UNKNOWN`（保留课程并标记待核对），**不能**当成「本周不授课」。
- `buildTodayView()` 在 `teachingWeek > 0` 且本周无课时必须返回空列表 + `TodayStatus.NO_CLASS`；**禁止** `ifEmpty { today }` 之类回退（会让单周课在双周重现）。前端 `todayCourses`/`todayStatus` 保持同样语义。
- 节次判定统一为 `start <= now < end`（下课整分钟不算「这节」）。
- `liveTeachingWeek` 必须依赖 `clockTick`，否则跨周驻留时不会重算。

## 首页主卡 / 今日列表状态语义（2.1.0）

- 主卡状态**只能由真实数据推导**，不许出现「运行中 / 已连接」这类与事实无关的措辞：
  未登录 / 缓存可看(CACHED_ONLY) / 同步失败(`lastSyncError`) / 解析需核对(`needsResync`) 沿用
  `home-banner` 的准确文案；其余按 `start <= now < end` → 正在上课 / 下一节 / 今日课程已结束 / 今日无课。
- **UNKNOWN 周次的课必须继续展示**，但**不参与** live / next / done 判定（`todayWeekClass`
  → `confirmedTodayCourses` / `uncertainTodayCourses`）。此时主卡写「周次待核对」，列表项加 `.cc-tag-warn`。
- 时间字段缺失/不可解析（`parseHm` 返回 -1 或 `end <= start`）一律视为**时间待核对**：
  `courseMinutes()` 返回 null，`liveCourse`/`nextCourse`/`lastDoneCourse` 都不认，**不允许**把缺时间当成「已结束」。
- 还有待核对课程时，主卡/列表说明写「已确认课程已结束」，不宣称「今日课程已结束」。
- 空课表**只有一个**空态（主卡承担），不要再在下方重复一张大空卡；无课日也不把状态卡拉伸填满屏幕。

## UI / 主题（FlClash 风格改造）

- **颜色只来自 `web/src/styles/tokens.css` 的语义令牌**（page / card / block / text / text-2 / primary /
  primary-soft / primary-text / btn-bg / success / warn / danger …）。组件里不要再写裸色值或 `!important` 覆盖；
  需要新颜色先在 tokens 里加语义变量。
- 浅色卡 `#FFFCFE` / 深色卡 `#282420`；**主色蓝 `#087FFF` 只做装饰**，浅底上的小字用 `--primary-text #0A5FC4`，
  实心主按钮用 `--btn-bg #0066CC`（白字对比度），深色用暖橙 `#FF9E45` + 深色字。
- 主卡 32px 圆角、内卡 20px、手机 16px 边距 / 12px 间距、点击目标 ≥44px。
- 主卡右侧约 1/4 是淡色波浪分区 + 花形徽章：八瓣轮廓由 8 个同色圆 + 中心圆**并集**得到（实心、不用透明花瓣），
  `drop-shadow` 跟随轮廓（不能用圆形 `box-shadow`，否则像按钮）；外圈花瓣慢速自转，**中央书本图标是兄弟节点、不随花瓣旋转**。
  徽章 `aria-hidden`，整卡不是按钮、不占焦点。
- **常驻装饰动效只在主卡**；课程卡不得一起呼吸。Dock 选中胶囊是独立平移层（180ms），Sheet 只做短入场位移。
- `--deco-play` 控制装饰动画播放；`visibilitychange` 隐藏时暂停（不新增 JS 高频 timer），
  `prefers-reduced-motion` 下动画关闭。
- 触摸/鼠标不要蓝框：`-webkit-tap-highlight-color: transparent` + 只在 `:focus-visible` 画焦点环。

## 主题（浅色 / 深色 / 跟随系统）

- 状态机在 `web/src/lib/theme.ts`：模式 `light|dark|system`，持久化到 `localStorage["sztu_theme"]`。
- 顶部快捷按钮**只在浅 ↔ 深之间切换**（不能三态轮换，否则系统本就浅色时点第一次像没反应）；
  「跟随系统」只在 设置 → 外观。**只有 system 模式跟随系统变化**，显式选择不被系统覆盖。
- `themeMode` 必须从 `getThemeMode()` 回填（`syncThemeState`），否则外观页选中态永远停在初始值。
- 首屏防闪色：`web/index.html` 的内联脚本在样式表之前写 `<html data-theme>`；同时切 `.dark` 类让 @nuxt/ui 弹层跟随。
- 原生侧：
  - 桥方法 `ScheduleBridge.setSystemBars(dark)` —— **仅主题用途**，写 `AppearanceStore`（prefs `sztu_appearance`）
    并更新系统栏图标 + `rootView`（FrameLayout）与 WebView 底色。系统栏是透明的，**必须一起改 root 底色**，
    只改 decorView 会露出旧底色。
  - `MainActivity` 声明 `configChanges` 含 `uiMode`，系统深浅变化时不重建 Activity，改用
    `window.onSztuSystemTheme(bool)` 推给页面；页面启动时由 `DOCUMENT_START_SCRIPT` 注入 `window.__sztuSystemDark`。
  - 自有原生弹窗用 `themedDialog()`（`Theme.SztuSchedule.Dialog` / `.Dialog.Dark`）按 `AppearanceStore` 选择，
    否则浅色主体会出现深色系统下的浅色弹窗。
  - `CropBackgroundActivity`：看图画布保持黑色，但底部动作区/说明/系统栏按 `AppearanceStore` 走同一套令牌；
    **后台解码与裁切逻辑不要动**。

## 会话、缓存与校历（审查报告 J03/J05/J06/J07/J08）

- Cookie 按 host 分开存（`cookie_jwxt` / `cookie_auth`，见 `CookieJar.kt`）；**禁止**再把两个域拼成一个 header。旧字段 `jwxt_cookie` 会被丢弃并由 WebView CookieManager 重新采集。
- `SessionState` 三态（NONE / CACHED_ONLY / SERVER_VERIFIED）：只有最近 12h 内真实同步成功才算已验证；「有课表」不再是登录有效证明。
- 课表缓存：`schedule.json` 原子写（tmp→rename）+ `schedule.json.bak` 兜底；解析器升级只置 `needs_resync`，**不再删除旧课表**。
- 空学期 vs 页面结构变化：`looksLikeTimetablePage()` 有表容器 + 0 门课 = 正常空学期（保存为空）；无表容器 = 结构变化（保留旧课表并提示）。
- 教学周：`TeachingWeek.effectiveWeek1Monday()` 优先用用户校准的学期起点（`sztu_calendar` prefs，桥方法 `setTermStart`/`clearTermStart`，前端「校历起点」页）；没有校准时才按 9/1、3/1 猜。`weeksFrom()` 两端都归一到 00:00。
- WebView 边界：带原生桥的 WebView 只允许 `https://appassets.androidplatform.net` 与 `file:///android_asset/`（`MainActivity.isAllowedLocalUrl`）；其它 URL 交外部浏览器。
- 备份：`res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 排除 `sztu_schedule.xml`、`sztu_calendar.xml`、课表缓存。
- 凭证存储：cookie 经 `SecretStore`（Android Keystore AES-GCM）封装后才落盘（`cookie_*_sealed`）；Keystore 不可用时 `seal()` 返回 null，只保留进程内副本并置 `secure_storage_unavailable`，**不得退回明文**。历史明文键 `cookie_jwxt`/`cookie_auth`/`jwxt_cookie` 会在每次采集时清除。

## 背景图 / 首页状态 / 日志（审查增量 J29-01 · J29-02）

- 背景图**不得**再以 `data:image/jpeg;base64,...` 跨桥：`BackgroundStore.toDataUrl()` 已删除。
  现在 `snapshotJson()` 只发 `bgImageUrl`（形如 `/bg/schedule_bg.jpg?v=<stamp>`，stamp = mtime*31+size），
  WebView 通过 `WebViewAssetLoader` 的受限端点 `BackgroundImageEndpoint`（前缀 `/bg/`）取图。
- 端点安全边界：**只认 `schedule_bg.jpg` 一个文件名**（`isAllowedPath` 完全相等比较，容忍一个前导 `/`），
  只读 `filesDir` 且规范化后必须直接位于该目录（二次校验），只回 `image/jpeg` + `nosniff` + 长缓存。
  不要为了便利加目录列表、后缀匹配或 `startsWith`。回归用例：`BackgroundImageEndpointTest`（8 项，含穿越/大小写/后缀/编码变体）。
- 前端 `bgUrl()` 只接受 `/...` 或 `http(s)`，显式丢弃 `data:`；`hasBg` = 地址非空**且**已预解码成功，
  因此首屏先无背景，`.bg-layer.fade-in` 淡入 240ms（`prefers-reduced-motion` 时关闭）。
  淡入动画**不能**用 `animation-fill-mode: both`，否则浓度滑杆改不动（已用 fixture 断言锁定）。
- 背景图的解码/编码在 `CropBackgroundActivity.bgIo` 后台线程；`BackgroundStore.saveFromUri` 用
  `inSampleSize` 有界解码（落盘最长边 1600）。
- 首页状态条 `homeBanner` 必须区分四态：**未登录 / 缓存可看(CACHED_ONLY) / 同步失败(`lastSyncError`) /
  解析需核对(`needsResync`)**，不能都落到「还没有课表」。登录页标题用 `sessionLabel`（会话三态），
  不要再用「有课表 = 已登录」。
- **功能开关（用户约束 2026-10-29：新增业务入口/新功能须先经用户同意）**：`web/src/App.vue` 顶部
  `HOME_STATUS_BANNER`（状态条纯文案，默认 true）与 `HOME_BANNER_ACTIONS`（状态条上的「去登录 / 重新同步」
  按钮 = 首页新增业务入口，**默认 false**）。未获用户同意前不要改成 true，也不要绕过开关在别处加同类入口；
  `tools/test-web-startup.py` 已断言默认状态下 `.hb-action` 数量为 0。
- 时钟：`clockTick` 仍是 30s 定时器，但 `visibilitychange` 隐藏时 `stopClock()`、可见时先补一次 tick 再 `startClock()`。
  不要退回「一直跑」的写法（fixture `timers` 断言的正是这个）。

## 安全区所有权（2.1.1 修复）

- **系统栏 / 刘海避让只有一个来源：原生。** `MainActivity` 的 root 监听器把
  `systemBars` 与 `displayCutout` **取并集**后一次性 `padding` 到 FrameLayout，
  并在返回的 `WindowInsetsCompat` 里**只把这两类置零**（`Builder.setInsets(..., Insets.NONE)`）。
  **不要整体返回 `CONSUMED`** —— 那会吞掉 IME，破坏现代 WebView 的键盘 viewport 调整；
  官方指引：已处理的 inset 类型在返回值置零（首选）或 consume。
- 网页侧 `web/src/styles/tokens.css` 定义 `--safe-top/bottom/left/right`：
  默认 `env(safe-area-inset-*)`（独立浏览器），`:root[data-native-insets="1"]` 时全部 `0px`。
  `data-native-insets` 由 `web/index.html` 首屏脚本（检测到 `ScheduleBridge`）设置，`theme.ts` 兜底。
- **所有使用安全区的地方只能引用这几个变量**，不要再直接写 `env(safe-area-inset-*)`；
  否则原生环境下会二次避让（2.1.0 的真机现象：状态栏下多出 60–70dp 空白）。
- 回归：`tools/test-web-ui.py` 用 CDP `Emulation.setSafeAreaInsetsOverride`（`{insets:{top:40,bottom:24,left:12}}`）
  造真实 `env()`：原生标记下顶栏 `padding-top` 必须 10px、Dock 14px、外壳左右 0；
  独立浏览器下必须是 50px / 38px / 12px。

## 出勤查询（2.2.0，用户已授权）

只读接入教学辅助系统 `https://acm.sztu.edu.cn:40130/hwsys`。**不要恢复对分易、签到、定位、监听、邮件、Python 或前台服务。**

- 2026-11-09 核对过的线上事实：`GET /api/signin_my_attendance?query={"param":{"date_start","date_end"}}`，未登录实响应 `{"status_code":401,"detail":"请先登录","data":null}`。官网状态是 0 未签、10 已签、20 请假、30 免听、40 迟到。页面没有「缺勤」这个词。
- 「已确认缺勤」只统计：状态 0、有稳定记录 ID、且 `time_end` 按 Asia/Shanghai 严格早于现在。窗口未结束、没有结束时间、状态缺失、没有 ID、请求失败，都不算缺勤。
- 官网没有学期起止接口。`term_util` 的 9/1、2/1、7/15 只是前端估计，界面必须标「待确认」，用户点「确认此范围」后才算已确认。未确认或结果不完整时，不能说「本学期暂无缺勤记录」。
- 登录入口是 `https://acm.sztu.edu.cn/sso/ssologin?sclient_id=hwsys`，和教务 SSO 分开。远程登录 WebView 没有 `ScheduleBridge`。令牌只进 `SecretStore`（prefs `sztu_hwsys`），缓存按账号哈希 + 学期放在 `hwsys_attendance/`。这两处都排除备份。
- 设置里的「登录与同步」一次只引导失效的那一边，然后同步课表和当前选定学期的出勤。关于页「检查更新」仍是 OTA，不并进这个按钮。
- **还没有用真实账号走完登录和出勤列表。** 不要把离线测试或 401 写成已经打通。
- 2.2.1：出勤请求和令牌续期必须在 `Dispatchers.IO`。`lifecycleScope.launch` 仍在主线程，只负责登录页和界面。从出勤页同步时先走教学辅助系统，教务取消不能挡住已有的 ACM 会话。SSO 仍是 `https://acm.sztu.edu.cn/sso/ssologin?sclient_id=hwsys`，教务 `entityId=jiaowu` 不变。

## 导航布局（2.2.0）

- 主 tab 是 **今日 / 课表 / 出勤**。设置仍由齿轮进入。首页状态条上的「去登录 / 重新同步」继续保持 `HOME_BANNER_ACTIONS = false`。
- 手机：浮动 Dock（图标 + 标签 + 独立滑动胶囊），快速连点以最后一次为准（不锁操作）。
- 宽屏 ≥1000px：左侧栏（216px）+ 两栏内容；**两套导航不会同时在 DOM**（`isWide` 用 `matchMedia` 驱动 `v-if`）。
- 非激活页 `.tab-pane-host` 必须同时有 `aria-hidden="true"` 与 `inert`（隐藏页不能仍被 Tab 聚焦）。
- 宽屏 `today-scroll` 是 `align-content: start` + `grid-auto-rows: min-content`；右栏用 `.today-right` 自然流
  （移动端 `display: contents`，宽屏才是独立一列），避免「标题被主卡行高推下去、留下大断层」。
- 课表 `.grid-outer` 只负责页面边距，`.grid-wrap` 是无 padding 的裁剪 viewport：
  padding 留在可裁剪层上会让相邻周的节次轴从两侧露出来。`measurePaneWidth()` 取 `clientWidth` 减 computed padding。

## Kotlin 单元测试入口（重要）

- **不要**在中文路径下直接跑 `gradlew testDebugUnitTest`：Gradle 的 unit-test worker 会 `ClassNotFoundException`（同一 classpath 手动 `java -cp` 跑 JUnit 正常，属 Gradle + 非 ASCII 路径问题）。
- 用 `tools\run-kotlin-tests.bat`：它把项目临时映射到 ASCII 盘符（T..Z）再跑测试，结束后自动卸载映射。当前基线：**90 项** Kotlin 测试全绿
  （`AttendanceLogicTest` 21 + `SyncFlowTest` 10 + `CookieJarTest` 19 + `ScheduleClockTest` 14 + `TeachingWeekTest` 9 +
  `BackgroundImageEndpointTest` 8 + `SectionTimesTest` 4 + `ScheduleHtmlParserTest` 2 + `WeekContractTest` 2 +
  `UnitTestSmokeTest` 1）。
- `ScheduleClockTest.real_fixture_course_boundaries_0830_to_0955` 锁定真实课程边界：08:29 不算上课、08:30 算、
  09:54 还算、**09:55 整点不算**（`start <= now < end`）。
- res XML 注释里**不能出现 `--`**（AAPT 会直接报错「注释中不允许出现字符串 "--"」）。

- 若改过测试后 `gradlew` 报 `this and base files have different roots`，删掉 `app/build/kotlin` 增量缓存再编译。

## 前端回归（tools/*.py）

- `tools/test-web-startup.py --apk <apk>`：Python Playwright + 本机 Chrome，跑 **APK 内真实资源** + 假原生桥，
  **6 场景** `empty-cache` / `cached-course` / `bg-ready` / `sync-error` / `needs-resync` / `session-verified`。
  断言首屏渲染、今日/课表导航、四种首页状态条、背景走受限端点（无 `data:`）且淡入后仍能改浓度、隐藏时停表。
  导航定位用 `nav [aria-current=page]`（2.1.0 按钮不再是 `.tab`）。
- `tools/test-web-ui.py --apk <apk>`：改造后的交互验收——主题点击/持久化/跟随系统、今日/课表/出勤导航与隐藏面板 `inert`、
  主卡 08:29–09:55 边界、UNKNOWN 周次与缺失时间的待核对语义、减少动画与后台暂停、全部设置页、宽屏对齐与裁剪。
- 两者都**不是** Android 真机验证。

## OTA 清单与产物一致性

- `ota/public/update.json` 必须与真正要发布的 APK 对齐：`versionCode` / `versionName` / `sizeBytes` /
  `sha256`（小写）/ `assetName` / `localPath`。`ota/publish.ps1` 已自动写入这些字段。
- `OtaUpdater` 在 `update.json` 带 `sizeBytes`/`sha256` 时会在下载后校验，不符则删除文件并拒绝调起安装；
  字段缺失时行为与旧版一致。发布前务必确认 `sha256` 与 `apkUrl` 指向的**同一个**文件。
- **2.1.0 本轮未做外网 OTA 发布**：`ota/public/update.json` 仍是 2.0.36 的历史记录，
  发布前必须按新 APK 重写，否则「检查更新」会认为没有更新。

## Local JDK 21 构建注意

- Local Microsoft JDK 21 may fail Gradle with `Unable to establish loopback connection` / AF_UNIX `Invalid argument: connect` when using the default temporary path. Setting `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Administrator\.codex\tmp` **for that build process only** works; no global environment change is needed.

## Other

- Landing on 强智 `LoginToXk` captcha page = SSO failed / session dead → re-login, do not parse as schedule.
- MIUI/HyperOS kills widgets without 无限制电量 + 自启动.
- `app/build` and `.gradle` are disposable caches.

## Do not

- Point this app at Electron `persist:jwxt` storage.
- Replace SSO URL entityId without verifying jwxt still accepts `entityId=jiaowu`.
- Commit secrets / `local.properties` SDK paths as universal truth (machine-specific).
- 恢复考勤功能、前台服务、通知权限或 Chaquopy（见上文移除记录）。
- 为动画或主题切换增加业务请求 / 冷却 / 定时器。
