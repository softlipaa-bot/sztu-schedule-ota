package com.sztu.schedule.data.hwsys

import java.util.concurrent.atomic.AtomicBoolean

enum class SyncOrigin { FULL, ATTENDANCE }

sealed class HwsysAccess {
    data class Ready(val token: String) : HwsysAccess()
    data object NeedsLogin : HwsysAccess()
    data class Failed(val message: String) : HwsysAccess()
}

data class ServiceLeg(
    val tried: Boolean = false,
    val ok: Boolean = false,
    val needsLogin: Boolean = false,
    val cancelled: Boolean = false,
    val detail: String = "",
)

data class SyncReport(val schedule: ServiceLeg, val attendance: ServiceLeg, val message: String)

class SyncHooks(
    val jwxtHasSession: () -> Boolean,
    val scheduleErrorLooksLikeAuth: (String) -> Boolean,
    val promptJwxt: suspend () -> Boolean,
    val promptHwsys: suspend () -> Boolean,
    val prepareAccess: suspend () -> HwsysAccess,
    val syncSchedule: suspend () -> Pair<Boolean, String>,
    val syncAttendance: suspend () -> FetchOutcome,
    val onStage: (String) -> Unit,
)

/**
 * 同一个入口，两条腿互不等待对方成功。
 * 从出勤页进来时先做教学辅助系统，教务取消或失败不会挡住已经有的 ACM 会话。
 */
suspend fun runUnified(origin: SyncOrigin, hooks: SyncHooks): SyncReport {
    val schedule: ServiceLeg
    val attendance: ServiceLeg
    if (origin == SyncOrigin.ATTENDANCE) {
        attendance = attendanceLeg(hooks)
        schedule = scheduleLeg(hooks)
    } else {
        schedule = scheduleLeg(hooks)
        attendance = attendanceLeg(hooks)
    }
    return SyncReport(schedule, attendance, syncReportText(schedule, attendance))
}

fun claimSync(running: AtomicBoolean): Boolean = running.compareAndSet(false, true)

fun releaseSync(running: AtomicBoolean) {
    running.set(false)
}

private suspend fun scheduleLeg(hooks: SyncHooks): ServiceLeg {
    var prompted = false
    if (!hooks.jwxtHasSession()) {
        hooks.onStage("正在登录教务课表")
        prompted = true
        if (!hooks.promptJwxt()) return ServiceLeg(cancelled = true, detail = "已取消教务登录")
    }
    if (!hooks.jwxtHasSession()) return ServiceLeg(tried = true, detail = "教务登录没有完成")
    hooks.onStage("正在同步课表")
    var (ok, error) = hooks.syncSchedule()
    if (!ok && hooks.scheduleErrorLooksLikeAuth(error) && !prompted) {
        hooks.onStage("正在登录教务课表")
        if (!hooks.promptJwxt()) return ServiceLeg(tried = true, cancelled = true, detail = "已取消教务登录")
        if (!hooks.jwxtHasSession()) return ServiceLeg(tried = true, detail = "教务登录没有完成")
        hooks.onStage("正在同步课表")
        val again = hooks.syncSchedule()
        ok = again.first
        error = again.second
    }
    return ServiceLeg(tried = true, ok = ok, detail = if (ok) "" else error.ifBlank { "课表同步失败" })
}

private suspend fun attendanceLeg(hooks: SyncHooks): ServiceLeg {
    var access = hooks.prepareAccess()
    var prompted = false
    if (access is HwsysAccess.NeedsLogin) {
        hooks.onStage("正在登录教学辅助系统")
        prompted = true
        if (!hooks.promptHwsys()) return ServiceLeg(needsLogin = true, cancelled = true, detail = "已取消教学辅助系统登录")
        access = hooks.prepareAccess()
    }
    when (access) {
        is HwsysAccess.Failed -> return ServiceLeg(tried = true, detail = access.message)
        is HwsysAccess.NeedsLogin -> return ServiceLeg(needsLogin = true, detail = "教学辅助系统登录没有完成")
        is HwsysAccess.Ready -> Unit
    }
    hooks.onStage("正在同步出勤")
    var outcome = hooks.syncAttendance()
    if (outcome is FetchOutcome.Failed && outcome.auth && !prompted) {
        hooks.onStage("正在登录教学辅助系统")
        if (!hooks.promptHwsys()) {
            return ServiceLeg(tried = true, needsLogin = true, cancelled = true, detail = "出勤查询失败：会话已失效")
        }
        when (val refreshed = hooks.prepareAccess()) {
            is HwsysAccess.Failed -> return ServiceLeg(tried = true, detail = refreshed.message)
            is HwsysAccess.NeedsLogin -> return ServiceLeg(tried = true, needsLogin = true, detail = "教学辅助系统登录没有完成")
            is HwsysAccess.Ready -> Unit
        }
        hooks.onStage("正在同步出勤")
        outcome = hooks.syncAttendance()
    }
    return when (outcome) {
        is FetchOutcome.Success -> ServiceLeg(tried = true, ok = true)
        FetchOutcome.Stale -> ServiceLeg(detail = "已忽略过期的出勤结果")
        is FetchOutcome.Failed -> ServiceLeg(
            tried = true,
            needsLogin = outcome.auth,
            detail = outcome.message.ifBlank { "出勤查询失败：请求失败" },
        )
    }
}

fun syncReportText(schedule: ServiceLeg, attendance: ServiceLeg): String {
    if (schedule.ok && attendance.ok) return "已同步课表与出勤"
    if (schedule.ok && attendance.needsLogin) return "课表已同步，出勤需重新登录"
    if (schedule.ok && !attendance.ok) {
        val detail = attendance.detail.ifBlank { "出勤获取失败" }
        return "课表已同步。$detail"
    }
    if (attendance.ok && !schedule.ok) {
        val detail = when {
            schedule.cancelled -> "已取消教务登录"
            schedule.detail.isNotBlank() -> schedule.detail
            else -> "课表同步失败"
        }
        return "出勤已同步，$detail"
    }
    if (schedule.cancelled && attendance.cancelled) return "已取消登录"
    if (attendance.detail.isNotBlank() && schedule.detail.isNotBlank()) {
        return "${attendance.detail}；${schedule.detail}"
    }
    if (attendance.detail.isNotBlank()) return attendance.detail
    if (schedule.detail.isNotBlank()) return schedule.detail
    return "没有完成同步"
}
