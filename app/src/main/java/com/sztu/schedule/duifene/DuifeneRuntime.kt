package com.sztu.schedule.duifene

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import java.time.format.DateTimeParseException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
class DuifeneRuntime private constructor(
    private val app: Application,
) {
    private val settings = DuifeneSettings()
    private val vault = DuifeneVault(app)
    private val hub = CredentialHub(
        saved = { vault.password() },
        persist = { account, password -> vault.savePassword(account, password) },
        forget = { vault.clearPassword() },
    )
    private val transport = AndroidDfTransport(settings)
    private val location = DeviceLocation(app)
    private val appApi = DfAppApi(transport, settings, vault)
    private val webApi = StudentWebApi(
        settings,
        transport,
        { hub.current(appApi.identity?.account) },
        AndroidQrDecoder(),
    )
    private val limiter = RateLimiter(settings)
    private val api = DuifeneApi(
        appApi,
        webApi,
        settings,
        limiter,
        credentials = { hub.current(appApi.identity?.account) },
    )
    private val gate = WatchRunGate()
    private val binding = RunServiceBinding()
    private val processor = ActivityProcessor(settings, api, log = { /* 不写含材料的日志 */ })
    private val watcher = Watcher(settings, api, processor, log = { line -> onWatchLog(line) })
    private val records = RecordBook()
    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "duifene-io").apply { isDaemon = true }
    }
    private val asyncStart = AsyncStart(io, gate)
    private val runLocal = ThreadLocal<Long>()
    private val epochLocal = ThreadLocal<Int>()
    private var armedRun = 0L
    private val watchThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "duifene-watch").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "duifene-session").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var limitTask: ScheduledFuture<*>? = null
    private var limitRun = 0L
    private var watchScope: RequestScope? = null
    private var watchScopeRun = 0L
    private var listenStartMono = 0L
    private var willRetry = false
    private var sessionEpoch = 0
    private var armedEpoch = 0
    private var loggedIn = false
    private var accountMasked = ""
    private var userName = ""
    private var coursesPhase = "idle"
    private var coursesError = ""
    private var courses: List<Course> = emptyList()
    private var selected: Course? = null
    private var watching = false
    private var watchPhase = "idle"
    private var activityPhase = "none"
    private var activitySummary = "未发现活动"
    private var activityType = ""
    private var activityValid = ""
    private var lastQueryAt = 0L
    private var needsWebPassword = false
    private var message = ""
    private var locationPermission = "unknown"
    private var lastPublish = 0L
    private var uiVisible = false

    init {
        api.sameSession = {
            val epoch = epochLocal.get()
            val run = runLocal.get()
            epoch != null && run != null && epoch == sessionEpoch && gate.activeId == run
        }
        api.allowSubmit = {
            val run = runLocal.get()
            run != null && gate.isCurrent(run)
        }
        api.locationFix = { location.fix() }
        watcher.onOutcome = { activity, outcome -> onOutcome(activity, outcome) }
        watcher.onTick = {
            synchronized(lock) { lastQueryAt = System.currentTimeMillis() }
            publish(false)
        }
        scheduler.scheduleWithFixedDelay({ keepSession() }, 60, 60, TimeUnit.SECONDS)
        io.execute { restore() }
    }

    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) io.execute { refreshIfNeeded() }
    }

    fun snapshotJson(): String = synchronized(lock) { view().toJson().toString() }

    fun notificationLabel(): String = synchronized(lock) {
        val name = selected?.name?.take(24).orEmpty().ifBlank { "已选课程" }
        "正在监听：$name"
    }

    fun login(account: String, password: String, savePassword: Boolean) {
        val name = account.trim()
        if (name.isEmpty() || name.length > 64 || password.isEmpty() || password.length > 128) {
            synchronized(lock) { message = "账号或密码无效" }
            publish(true)
            return
        }
        io.execute {
            stopWatchLocked("stopped")
            location.resume()
            val result = api.loginByPassword(name, password, retainPassword = true)
            synchronized(lock) {
                sessionEpoch += 1
                selected = null
                courses = emptyList()
                records.reset()
                resetActivity()
                needsWebPassword = false
                if (result == "登录成功") {
                    val account = api.identity?.account ?: name
                    hub.accept(account, password, savePassword)
                    api.rememberCredential(hub.current(account))
                    loggedIn = true
                    accountMasked = maskAccount(api.identity?.account ?: name)
                    userName = api.identity?.userName.orEmpty()
                    message = "已登录，尚未开始监听"
                    coursesPhase = "loading"
                    watchPhase = "idle"
                } else {
                    loggedIn = appApi.isAuthenticated
                    message = result
                    coursesPhase = "idle"
                }
            }
            publish(true)
            if (result == "登录成功") loadCourses()
        }
    }

    fun supplyWebPassword(password: String, savePassword: Boolean) {
        if (password.isEmpty() || password.length > 128) {
            synchronized(lock) { message = "密码无效" }
            publish(true)
            return
        }
        val account = appApi.identity?.account
        if (account.isNullOrEmpty()) {
            synchronized(lock) { message = "请先登录对分易" }
            publish(true)
            return
        }
        hub.accept(account, password, savePassword)
        api.rememberCredential(hub.current(account))
        synchronized(lock) {
            needsWebPassword = false
            message = "网页凭据已更新，需要时再开始监听"
        }
        publish(true)
    }

    fun logout() {
        io.execute {
            stopWatchLocked("stopped")
            api.clearSession()
            hub.clearSession()
            vault.clearPassword()
            synchronized(lock) {
                sessionEpoch += 1
                loggedIn = false
                accountMasked = ""
                userName = ""
                courses = emptyList()
                coursesPhase = "idle"
                selected = null
                records.reset()
                resetActivity()
                needsWebPassword = false
                message = "已退出对分易"
                watchPhase = "idle"
            }
            publish(true)
        }
    }

    fun refreshCourses() {
        io.execute {
            if (!gate.canStart()) {
                synchronized(lock) { message = "停止监听后可切换课程" }
                publish(true)
                return@execute
            }
            if (!loggedIn) {
                synchronized(lock) { message = "请先登录对分易" }
                publish(true)
                return@execute
            }
            synchronized(lock) {
                coursesPhase = if (courses.isEmpty()) "loading" else "refreshing"
                coursesError = ""
            }
            publish(false)
            loadCourses()
        }
    }

    fun selectCourse(courseId: String, classId: String): String {
        val result = synchronized(lock) {
            if (!gate.canStart()) return@synchronized "停止监听后可切换课程"
            val course = courses.firstOrNull { it.courseId == courseId && it.classId == classId }
                ?: return@synchronized "这门课不在当前账号已加入的教学班里"
            if (selected?.courseId != course.courseId || selected?.classId != course.classId) {
                sessionEpoch += 1
                records.reset()
                resetActivity()
            }
            selected = course
            message = ""
            ""
        }
        publish(true)
        return result
    }

    fun clearRecords(): String {
        synchronized(lock) {
            records.clear()
            message = "记录已清空"
        }
        publish(true)
        return ""
    }

    fun startWatch() {
        if (!loggedIn) {
            synchronized(lock) { message = "请先登录对分易" }
            publish(true)
            return
        }
        if (selected == null) {
            synchronized(lock) { message = "请先选择一门课" }
            publish(true)
            return
        }
        val accepted = asyncStart.request { id -> launchRun(id) }
        synchronized(lock) {
            if (accepted == "busy") message = "正在处理上一轮监听"
            else {
                watchPhase = "starting"
                watching = true
                willRetry = false
                message = ""
            }
        }
        publish(true)
    }

    fun stopWatch(reason: String = "stopped") {
        stopRun(gate.activeId, reason)
    }

    fun stopFromService(runId: Long, reason: String) {
        stopRun(runId, reason)
    }

    fun onSystemTimeout(runId: Long = gate.activeId) {
        stopRun(runId, "system_timeout")
    }

    fun onServiceStartFailed(runId: Long) {
        stopRun(runId, "service_failed")
        synchronized(lock) {
            if (gate.activeId == runId) message = "系统没有允许启动前台监听"
        }
        publish(true)
    }

    fun onServiceDestroyed(runId: Long) {
        if (gate.activeId == runId && gate.phase != "idle" && gate.phase != "stopped") {
            stopRun(runId, "service_lost")
        }
    }

    private fun launchRun(id: Long) {
        if (gate.activeId != id) return
        if (!appApi.isAuthenticated) {
            gate.finish(id, "auth_required")
            synchronized(lock) {
                if (gate.activeId == id) {
                    watching = false
                    watchPhase = "auth_required"
                    loggedIn = false
                    message = "登录状态已失效，请重新登录"
                }
            }
            publish(true)
            return
        }
        val course = selected
        if (course == null || !gate.markRunning(id)) {
            if (gate.activeId == id) gate.finish(id, "stopped")
            synchronized(lock) {
                if (gate.activeId == id) {
                    watching = false
                    watchPhase = "stopped"
                }
            }
            publish(true)
            return
        }
        val epoch = sessionEpoch
        synchronized(lock) {
            armedRun = id
            armedEpoch = epoch
            watcher.course = course
            watchPhase = "running"
            watching = true
            willRetry = false
            listenStartMono = System.nanoTime() / 1_000_000L
            locationPermission = location.permissionState()
            message = ""
        }
        location.resume()
        if (!watcher.arm()) {
            gate.finish(id, "auth_required")
            synchronized(lock) {
                watching = false
                watchPhase = "auth_required"
                message = "登录状态已失效，请重新登录"
            }
            publish(true)
            return
        }
        val scope = transport.newScope()
        watchScope = scope
        watchScopeRun = id
        try {
            DuifeneWatchService.start(app, id)
        } catch (_: Exception) {
            onServiceStartFailed(id)
            return
        }
        binding.bind(id)
        if (limitRun == id) limitTask?.cancel(false)
        limitRun = id
        limitTask = scheduler.schedule(
            { stopRun(id, "duration") },
            DuifenePolicy.MAX_LISTEN_MINUTES.toLong(),
            TimeUnit.MINUTES,
        )
        watchThread.execute {
            try {
                transport.inScope(scope) {
                    runLocal.set(id)
                    epochLocal.set(epoch)
                    try {
                        watcher.runLoop()
                    } finally {
                        runLocal.remove()
                        epochLocal.remove()
                    }
                }
            } finally {
                finishRun(id)
            }
        }
    }

    private fun stopWatchLocked(reason: String) {
        stopRun(gate.activeId, reason)
    }

    private fun stopRun(id: Long, reason: String) {
        if (id == 0L || !gate.requestStop(id)) return
        watcher.requestStop()
        cancelScope(id)
        location.cancel()
        if (limitRun == id) limitTask?.cancel(false)
        val terminal = if (reason == "system_timeout") "system_timeout" else "stopped"
        if (!gate.finish(id, terminal)) return
        synchronized(lock) {
            if (gate.activeId != id) return@synchronized
            watching = false
            willRetry = false
            watchPhase = terminal
            message = when (reason) {
                "duration" -> "已到 ${DuifenePolicy.MAX_LISTEN_MINUTES} 分钟，监听已停止"
                "system_timeout" -> "系统限制了数据同步前台服务，监听已停止"
                "service_lost" -> "系统停止了前台监听"
                "service_failed" -> "系统没有允许启动前台监听"
                else -> "已停止监听"
            }
        }
        binding.stopIfBound(id) { DuifeneWatchService.stop(app) }
        publish(true)
    }

    private fun finishRun(id: Long) {
        val detail = watcher.stopDetail
        val terminal = when {
            detail == "auth" -> "auth_required"
            detail == "permission" -> "permission_denied"
            detail.startsWith("web:") -> "web_stopped"
            else -> "stopped"
        }
        if (!gate.finish(id, terminal)) return
        synchronized(lock) {
            if (gate.activeId != id) return@synchronized
            watching = false
            willRetry = false
            watchPhase = terminal
            when (terminal) {
                "auth_required" -> {
                    loggedIn = false
                    message = "对分易登录已失效，请重新登录。教务和出勤会话没有被清除。"
                }
                "permission_denied" -> message = "对分易拒绝了当前学生身份的访问，监听已停止"
                "web_stopped" -> {
                    needsWebPassword = "凭据" in detail || "重新登录" in detail
                    message = "网页材料读取失败，App 登录仍保留"
                }
                else -> if (message.isBlank()) message = "已停止监听"
            }
        }
        cancelScope(id)
        if (limitRun == id) limitTask?.cancel(false)
        binding.stopIfBound(id) { DuifeneWatchService.stop(app) }
        publish(true)
    }

    private fun cancelScope(id: Long) {
        if (watchScopeRun == id) watchScope?.cancel()
    }

    private fun onWatchLog(line: String) {
        val id = runLocal.get() ?: return
        if ("后重试" in line) {
            gate.markRetrying(id)
            synchronized(lock) {
                if (gate.activeId == id) {
                    willRetry = true
                    watchPhase = "retrying"
                    message = line.substringAfterLast("课程: ").take(80)
                }
            }
            publish(true)
        } else if (gate.phase == "retrying") {
            gate.markRunningAgain(id)
            synchronized(lock) {
                if (gate.activeId == id) {
                    willRetry = false
                    watchPhase = "running"
                }
            }
        }
    }

    private fun onOutcome(activity: AppCheckIn, outcome: Outcome) {
        if (outcome == Outcome.DISCARDED) return
        val id = runLocal.get() ?: return
        if (gate.activeId != id) return
        synchronized(lock) {
            if (gate.activeId != id) return
            activityType = activity.checkType
            activityValid = activity.validDate
            activityPhase = when (outcome) {
                Outcome.SIGNED -> "success"
                Outcome.PENDING -> "pending_confirm"
                else -> processor.statusPhase
            }
            activitySummary = processor.statusText.ifBlank { "未发现活动" }
            records.note(
                account = appApi.identity?.account.orEmpty(),
                classId = activity.classId,
                activityId = activity.activityId,
                phase = activityPhase,
                summary = activitySummary,
                courseLabel = selected?.name ?: "课程",
                atMs = System.currentTimeMillis(),
            )
        }
        publish(outcome == Outcome.SIGNED || outcome == Outcome.PENDING)
    }

    private fun loadCourses() {
        try {
            api.ensureSessionFresh()
            val loaded = api.fetchCourses()
            synchronized(lock) {
                courses = loaded
                coursesPhase = if (loaded.isEmpty()) "empty" else "ready"
                coursesError = ""
                loggedIn = true
                accountMasked = maskAccount(api.identity?.account.orEmpty())
                userName = api.identity?.userName.orEmpty()
                if (selected != null && loaded.none { it.courseId == selected?.courseId && it.classId == selected?.classId }) {
                    selected = null
                }
            }
        } catch (error: AuthError) {
            synchronized(lock) {
                loggedIn = false
                coursesPhase = "error"
                coursesError = "登录已失效"
                message = "对分易登录已失效"
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (courses.isNotEmpty()) {
                    coursesPhase = "ready"
                    coursesError = "刷新失败"
                    message = "刷新失败"
                } else {
                    coursesPhase = "error"
                    coursesError = "课程读取失败"
                    message = "课程读取失败，可以重试"
                }
            }
        }
        publish(true)
    }

    private fun restore() {
        try {
            appApi.restore()
            if (!appApi.isAuthenticated) {
                synchronized(lock) { loggedIn = false; sessionLabelNone() }
                publish(true)
                return
            }
            synchronized(lock) {
                loggedIn = true
                accountMasked = maskAccount(api.identity?.account.orEmpty())
                userName = api.identity?.userName.orEmpty()
                coursesPhase = "loading"
            }
            publish(false)
            loadCourses()
        } catch (_: Exception) {
            synchronized(lock) {
                loggedIn = false
                message = "暂时无法恢复对分易登录"
            }
            publish(true)
        }
    }

    private fun refreshIfNeeded() {
        if (!appApi.isAuthenticated) return
        try {
            api.ensureSessionFresh()
        } catch (_: AuthError) {
            synchronized(lock) {
                loggedIn = false
                message = "对分易登录已失效"
            }
            publish(true)
        } catch (_: AppApiError) {
            // 断网保留刷新凭据，下次再试。
        }
    }

    private fun keepSession() {
        if (!uiVisible && !watching) return
        refreshIfNeeded()
    }

    private fun sessionLabelNone() {
        coursesPhase = "idle"
    }

    private fun resetActivity() {
        activityPhase = "none"
        activitySummary = "未发现活动"
        activityType = ""
        activityValid = ""
        lastQueryAt = 0L
    }

    private fun view(): DuifeneView {
        val remain = remainSeconds()
        val listenEnds = listenEndsAt()
        val listenRemain = listenEnds?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L) }
        val phaseNow = gate.phase
        val session = when {
            !loggedIn -> "none"
            needsWebPassword -> "web_needed"
            else -> "active"
        }
        return DuifeneView(
            loggedIn = loggedIn,
            accountMasked = accountMasked,
            userName = userName,
            session = session,
            storageWarning = vault.storageWarning(),
            coursesPhase = coursesPhase,
            coursesError = coursesError,
            courses = courses,
            selectedKey = selected?.let { "${it.courseId}|${it.classId}" }.orEmpty(),
            watching = phaseNow == "starting" || phaseNow == "running" || phaseNow == "retrying" || phaseNow == "stopping",
            watchPhase = if (phaseNow == "idle") watchPhase else phaseNow,
            activityPhase = if (watching && activityPhase == "none") "none" else activityPhase,
            activitySummary = activitySummary,
            activityType = activityType,
            remainSeconds = remain,
            listenRemainSeconds = listenRemain,
            activityEndsAt = activityEndsAt(),
            listenEndsAt = listenEnds,
            willRetry = willRetry,
            sessionEpoch = sessionEpoch,
            lastQueryAt = lastQueryAt,
            canStart = loggedIn && selected != null && gate.canStart(),
            needsWebPassword = needsWebPassword,
            locationPermission = location.permissionState(),
            records = records.snapshot(),
            message = message,
            settings = settings,
        )
    }

    private fun listenEndsAt(): Long? {
        if (listenStartMono == 0L || gate.canStart()) return null
        val elapsed = System.nanoTime() / 1_000_000L - listenStartMono
        val remain = DuifenePolicy.MAX_LISTEN_MINUTES * 60_000L - elapsed
        return System.currentTimeMillis() + remain.coerceAtLeast(0L)
    }

    private fun activityEndsAt(): Long? {
        if (activityValid.isBlank()) return null
        return try {
            parseCheckinTime(activityValid).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun remainSeconds(): Long? {
        if (activityValid.isBlank()) return null
        return try {
            val seconds = java.time.Duration.between(java.time.ZonedDateTime.now(SHANGHAI), parseCheckinTime(activityValid)).seconds
            seconds.coerceAtLeast(0L)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun publish(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastPublish < 300_000_000L) return
        lastPublish = now
        val json = snapshotJson()
        listeners.forEach { listener ->
            try {
                listener(json)
            } catch (_: Exception) {
            }
        }
    }

    fun currentRunId(): Long = gate.activeId

    fun serviceTypes(): Int {
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val withLocation = location.permissionState() == "granted" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return if (withLocation) dataSync or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else dataSync
    }

    companion object {
        @Volatile
        private var instance: DuifeneRuntime? = null

        fun install(app: Application): DuifeneRuntime {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: DuifeneRuntime(app).also { instance = it }
            }
        }

        fun get(): DuifeneRuntime = instance ?: error("对分易模块还没有初始化")

        fun peek(): DuifeneRuntime? = instance
    }
}

internal fun DuifeneRuntime.buildNotification(context: android.content.Context): Notification {
    DuifeneWatchService.ensureChannel(context)
    val stop = PendingIntent.getService(
        context,
        1,
        Intent(context, DuifeneWatchService::class.java)
            .setAction(DuifeneWatchService.ACTION_STOP)
            .putExtra(DuifeneWatchService.EXTRA_RUN, currentRunId()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val open = PendingIntent.getActivity(
        context,
        2,
        Intent(context, com.sztu.schedule.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return androidx.core.app.NotificationCompat.Builder(context, DuifeneWatchService.CHANNEL_ID)
        .setSmallIcon(com.sztu.schedule.R.drawable.ic_stat_duifene)
        .setContentTitle("对分易正在监听")
        .setContentText(notificationLabel())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .addAction(0, "停止监听", stop)
        .build()
}
