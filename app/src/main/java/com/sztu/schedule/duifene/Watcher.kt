package com.sztu.schedule.duifene

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

/**
 * 活动发现和数字取码是两套调度。
 * 发现间隔沿用设置（默认 3–5 秒）；只有缺码数字活动才按 0.2 秒取材料。
 * 截止前 5 秒优先取码。移植自 duifene_auto_sign ce1608e，不把所有接口改成 0.2 秒。
 */
class Watcher(
    private val settings: DuifeneSettings,
    private val api: ActivitySource,
    private val processor: ActivityProcessor,
    private val log: (String) -> Unit,
    private val clock: DfClock = SystemDfClock,
    private val sleeper: (Double) -> Unit = { seconds ->
        try {
            if (seconds > 0) Thread.sleep((seconds * 1000.0).toLong())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    },
) {
    var course: Course? = null
    var pollDelay: () -> Double = { settings.pollDelaySeconds() }
    var inMonitorWindow: () -> Boolean = { defaultWindow() }

    private val stopFlag = AtomicBoolean(false)
    private val loopThread = AtomicReference<Thread?>(null)
    private var thread: Thread? = null
    private var consecutiveErrors = 0
    internal var numericFailures = 0
    var lastTickMs: Long = 0L
        private set
    private var inWindow: Boolean? = null
    private val seenActivities = mutableSetOf<String>()
    internal var numericDeadline: ZonedDateTime? = null
    internal val pendingNumeric = linkedMapOf<String, AppCheckIn>()
    var onOutcome: (AppCheckIn, Outcome) -> Unit = { _, _ -> }
    var onTick: () -> Unit = {}
    var stopDetail: String = ""

    val isRunning: Boolean get() = thread?.isAlive == true

    fun requestStop() {
        stopFlag.set(true)
        loopThread.get()?.interrupt()
    }

    fun arm(): Boolean {
        if (!api.appAuthenticated) return false
        stopFlag.set(false)
        processor.reset()
        consecutiveErrors = 0
        numericFailures = 0
        inWindow = null
        seenActivities.clear()
        numericDeadline = null
        pendingNumeric.clear()
        stopDetail = ""
        log(prefix(course, "开始监听"))
        return true
    }

    fun start() {
        if (!api.appAuthenticated) throw AuthError("登录状态已失效，请重新登录账号")
        stopJoin()
        stopFlag.set(false)
        processor.reset()
        consecutiveErrors = 0
        numericFailures = 0
        inWindow = null
        seenActivities.clear()
        numericDeadline = null
        pendingNumeric.clear()
        log(prefix(course, "开始监听"))
        thread = Thread({ runLoop() }, "duifene-watcher").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        val wasRunning = isRunning
        val current = course
        stopFlag.set(true)
        stopJoin()
        if (wasRunning) log(prefix(current, "已停止监听"))
    }

    private fun stopJoin() {
        val running = thread
        if (running != null && running.isAlive && running !== Thread.currentThread()) {
            running.join(5_000)
        }
        thread = null
    }

    fun runLoop() {
        loopThread.set(Thread.currentThread())
        var nextDiscovery = 0.0
        var nextMaterial = 0.0
        while (!stopFlag.get()) {
            try {
                val now = mono()
                if (now >= nextDiscovery && !numericIsUrgent()) {
                    tick()
                    nextDiscovery = mono() + pollDelay()
                }
                if (pendingNumeric.isNotEmpty() && mono() >= nextMaterial) {
                    numericTick()
                    nextMaterial = mono() + numericPollDelay()
                }
                consecutiveErrors = 0
            } catch (error: AuthError) {
                stopDetail = "auth"
                log(prefix(course, "${error.message}，监听已停止"))
                stopFlag.set(true)
                break
            } catch (error: PermissionDenied) {
                stopDetail = "permission"
                log(prefix(course, "监听失败：${error.message}，监听已停止"))
                stopFlag.set(true)
                break
            } catch (error: WebSessionError) {
                stopDetail = "web:" + (error.message ?: "")
                log(prefix(course, "监听失败：${error.message}，监听已停止"))
                stopFlag.set(true)
                break
            } catch (error: Exception) {
                consecutiveErrors += 1
                val backoff = min(
                    settings.pollMinS * 2.0.pow(consecutiveErrors.toDouble()),
                    settings.errorBackoffMaxS,
                )
                log(prefix(course, "监控异常：${error.message}（${backoff.toInt()} 秒后重试）"))
                sleep(backoff)
                continue
            }
            if (stopFlag.get()) break
            val after = mono()
            val waits = mutableListOf<Double>()
            if (pendingNumeric.isNotEmpty()) waits += (nextMaterial - after).coerceAtLeast(0.0)
            if (!numericIsUrgent()) waits += (nextDiscovery - after).coerceAtLeast(0.0)
            sleep(if (waits.isEmpty()) pollDelay() else waits.min())
        }
        loopThread.compareAndSet(Thread.currentThread(), null)
    }

    fun numericPollDelay(): Double {
        val steps = min(numericFailures, 4)
        val raw = DuifenePolicy.NUMERIC_POLL_S * 2.0.pow(steps.toDouble())
        return min(raw, DuifenePolicy.NUMERIC_BACKOFF_MAX_S)
    }

    fun numericIsUrgent(): Boolean {
        val deadline = numericDeadline ?: return false
        if (pendingNumeric.isEmpty()) return false
        val remaining = java.time.Duration.between(clock.shanghaiNow(), deadline).toMillis() / 1000.0
        return remaining > 0.0 && remaining <= DuifenePolicy.NUMERIC_PRIORITY_WINDOW_S
    }

    internal fun setPendingNumeric(activities: List<AppCheckIn>) {
        val ids = activities.map { it.activityId }.toSet()
        if (activities.isEmpty() || pendingNumeric.keys.intersect(ids).isEmpty()) {
            numericFailures = 0
        }
        pendingNumeric.clear()
        for (activity in activities) pendingNumeric[activity.activityId] = activity
        numericDeadline = if (activities.isEmpty()) {
            null
        } else {
            activities.minOf { parseCheckinTime(it.validDate) }
        }
    }

    fun numericTick() {
        lastTickMs = clock.wallMs()
        if (stopFlag.get()) return
        val current = course
        if (current == null || !inMonitorWindow()) {
            setPendingNumeric(emptyList())
            return
        }
        if (api.limiter.isBlocking(clock.wallMs())) return
        val now = clock.shanghaiNow()
        val pending = pendingNumeric.values.filter { !it.hasEnded(now) }
        if (pending.isEmpty()) {
            setPendingNumeric(emptyList())
            return
        }
        val keep = mutableListOf<AppCheckIn>()
        try {
            val refreshed = api.refreshNumericMaterials(current, pending)
            for (activity in refreshed) {
                if (stopFlag.get()) break
                val outcome = processor.process(activity, current)
                if (outcome != Outcome.DISCARDED) onOutcome(activity, outcome)
                if (outcome == Outcome.DEFERRED) keep += activity
            }
        } catch (error: AuthError) {
            throw error
        } catch (error: PermissionDenied) {
            throw error
        } catch (error: WebSessionError) {
            throw error
        } catch (error: AppApiError) {
            val previous = numericPollDelay()
            numericFailures += 1
            val delay = numericPollDelay()
            if (numericFailures == 1 || (previous < DuifenePolicy.NUMERIC_BACKOFF_MAX_S && delay >= DuifenePolicy.NUMERIC_BACKOFF_MAX_S)) {
                log(prefix(current, "数字取码异常：${error.message}（${trimDelay(delay)} 秒后重试）"))
            }
            return
        }
        numericFailures = 0
        setPendingNumeric(keep)
        onTick()
    }

    fun tick() {
        lastTickMs = clock.wallMs()
        if (stopFlag.get()) return
        if (!api.appAuthenticated) throw AuthError("登录状态已失效，请重新登录账号")
        val current = course
        val inside = inMonitorWindow()
        if (inside != inWindow) {
            inWindow = inside
            val window = "${settings.monitorStartHour}–${settings.monitorEndHour} 时"
            log(
                prefix(
                    current,
                    if (inside) "进入监听时间窗（$window），开始轮询" else "当前不在监听时间窗（$window），暂不轮询",
                )
            )
        }
        if (!inside || current == null) {
            setPendingNumeric(emptyList())
            return
        }
        val discovered = api.fetchActiveActivities(current, includeNumericMaterials = false)
        if (discovered.isEmpty() && pendingNumeric.isNotEmpty()) {
            val who = api.identity
            val now = clock.shanghaiNow()
            val kept = pendingNumeric.values.filter { activity ->
                who != null && activity.studentId == who.userId &&
                    activity.classId == current.classId && activity.hasValidTimes &&
                    !activity.hasEnded(now) && !activity.isDeleted && !activity.isAttended &&
                    activity.ageSeconds(now)?.let { it in 0.0..(settings.staleSignMinutes * 60.0) } == true
            }
            setPendingNumeric(kept)
            consecutiveErrors = 0
            onTick()
            return
        }
        val pending = mutableListOf<AppCheckIn>()
        for (activity in discovered) {
            if (stopFlag.get()) break
            if (activity.activityId !in seenActivities) {
                seenActivities += activity.activityId
                log(prefix(current, "发现进行中的签到 ${activity.activityId}"))
            }
            val outcome = processor.process(activity, current)
            if (outcome != Outcome.DISCARDED) onOutcome(activity, outcome)
            if (activity.checkType == "1" && !activity.validCode() && activity.hasValidTimes && outcome == Outcome.DEFERRED) {
                pending += activity
            }
        }
        setPendingNumeric(pending)
        consecutiveErrors = 0
        onTick()
    }

    private fun defaultWindow(): Boolean {
        val hour = clock.shanghaiNow().hour
        return hour >= settings.monitorStartHour && hour < settings.monitorEndHour
    }

    private fun mono(): Double = clock.monoMs() / 1000.0

    private fun sleep(seconds: Double) {
        sleeper(seconds.coerceAtLeast(0.0))
    }

    private fun prefix(target: Course?, message: String): String =
        if (target == null) message else "课程: ${target.name} $message"

    private fun trimDelay(delay: Double): String =
        if (delay % 1.0 == 0.0) delay.toInt().toString() else delay.toString()
}
