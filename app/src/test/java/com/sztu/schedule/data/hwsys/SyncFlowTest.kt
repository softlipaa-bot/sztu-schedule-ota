package com.sztu.schedule.data.hwsys

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class SyncFlowTest {
    @Test
    fun attendancePageStillQueriesAcmWhenJwxtLoginIsCancelled() = runBlocking {
        val steps = mutableListOf<String>()
        var prepares = 0
        val report = runUnified(
            SyncOrigin.ATTENDANCE,
            hooks(
                jwxtReady = false,
                promptJwxt = { steps += "jwxt"; false },
                promptHwsys = { steps += "acm-login"; true },
                prepare = {
                    steps += "prepare"
                    prepares += 1
                    if (prepares == 1) HwsysAccess.NeedsLogin else HwsysAccess.Ready("token")
                },
                schedule = { steps += "schedule"; false to "不应执行课表" },
                attendance = { steps += "attendance"; FetchOutcome.Success(sampleCache()) },
                onStage = { steps += it },
            ),
        )
        assertTrue(report.attendance.ok)
        assertTrue(report.schedule.cancelled)
        assertTrue(report.message.contains("出勤已同步"))
        assertTrue(steps.indexOf("attendance") < steps.indexOf("jwxt"))
        assertFalse(steps.contains("schedule"))
    }

    @Test
    fun fullSyncStillReadsAttendanceAfterJwxtCancel() = runBlocking {
        val steps = mutableListOf<String>()
        val report = runUnified(
            SyncOrigin.FULL,
            hooks(
                jwxtReady = false,
                promptJwxt = { steps += "jwxt"; false },
                prepare = { HwsysAccess.Ready("token") },
                attendance = { steps += "attendance"; FetchOutcome.Success(sampleCache()) },
                onStage = { steps += it },
            ),
        )
        assertTrue(steps.indexOf("jwxt") < steps.indexOf("attendance"))
        assertTrue(report.attendance.ok)
        assertTrue(report.schedule.cancelled)
        assertEquals("出勤已同步，已取消教务登录", report.message)
    }

    @Test
    fun scheduleSuccessKeepsGoingWhenAttendanceFails() = runBlocking {
        val report = runUnified(
            SyncOrigin.FULL,
            hooks(
                jwxtReady = true,
                schedule = { true to "" },
                attendance = { FetchOutcome.Failed("出勤查询失败：网络连接失败（ConnectException）", false) },
            ),
        )
        assertTrue(report.schedule.ok)
        assertFalse(report.attendance.ok)
        assertTrue(report.message.contains("课表已同步"))
        assertTrue(report.message.contains("网络连接失败"))
        assertFalse(report.message.contains("Bearer"))
    }

    @Test
    fun attendanceSuccessSurvivesScheduleFailure() = runBlocking {
        val report = runUnified(
            SyncOrigin.FULL,
            hooks(
                jwxtReady = true,
                schedule = { false to "课表同步失败：会话已过期" },
                attendance = { FetchOutcome.Success(sampleCache()) },
            ),
        )
        assertTrue(report.attendance.ok)
        assertFalse(report.schedule.ok)
        assertTrue(report.message.startsWith("出勤已同步"))
    }

    @Test
    fun expiredAttendancePromptsOnceThenRetries() = runBlocking {
        var prompts = 0
        var queries = 0
        val report = runUnified(
            SyncOrigin.ATTENDANCE,
            hooks(
                jwxtReady = true,
                promptHwsys = { prompts += 1; true },
                prepare = { HwsysAccess.Ready("token") },
                schedule = { true to "" },
                attendance = {
                    queries += 1
                    if (queries == 1) FetchOutcome.Failed("出勤查询失败：会话已失效（HTTP 401）", true)
                    else FetchOutcome.Success(sampleCache())
                },
            ),
        )
        assertEquals(1, prompts)
        assertEquals(2, queries)
        assertTrue(report.attendance.ok)
    }

    @Test
    fun cancellingTheOnlyReloginDoesNotLoop() = runBlocking {
        var prompts = 0
        val report = runUnified(
            SyncOrigin.ATTENDANCE,
            hooks(
                jwxtReady = true,
                promptHwsys = { prompts += 1; false },
                prepare = { HwsysAccess.Ready("token") },
                schedule = { true to "" },
                attendance = { FetchOutcome.Failed("出勤查询失败：会话已失效（HTTP 403）", true) },
            ),
        )
        assertEquals(1, prompts)
        assertFalse(report.attendance.ok)
        assertTrue(report.attendance.needsLogin)
    }

    @Test
    fun secondClickDoesNotStartAnotherSync() {
        val running = AtomicBoolean(false)
        assertTrue(claimSync(running))
        assertFalse(claimSync(running))
        releaseSync(running)
        assertTrue(claimSync(running))
    }

    @Test
    fun httpFailuresStaySpecificAndDoNotLeakTokens() {
        val expired = interpretHttp("SSO交换", 401, """{"status_code":401,"detail":"请先登录","access_token":"secret-token"}""")
        assertEquals("auth", expired!!.kind)
        assertTrue(expired.message!!.contains("会话已失效"))
        assertFalse(expired.message!!.contains("secret-token"))
        val denied = interpretHttp("登录续期", 403, """{"status_code":403,"detail":"forbidden"}""")
        assertEquals("auth", denied!!.kind)
        val changed = interpretHttp("SSO交换", 200, """{"status_code":200,"data":{}}""")
        assertEquals("parse", changed!!.kind)
        assertTrue(changed.message!!.contains("响应格式变化"))
    }

    @Test
    fun jwxtPageIsNotAnAcmTokenPage() {
        assertTrue(isJwxtPage("https://jwxt.sztu.edu.cn/jsxsd/framework/xsMain.jsp"))
        assertFalse(isAcmTokenCarrier("https://jwxt.sztu.edu.cn/jsxsd/framework/xsMain.jsp"))
        assertTrue(isAcmTokenCarrier("https://acm.sztu.edu.cn:40130/hwsys/ssologin_callback?code=abc"))
        assertTrue(isHwsysAppUrl("https://acm.sztu.edu.cn:40130/hwsys/home"))
        assertFalse(isAcmTokenCarrier("http://acm.sztu.edu.cn/sso/ssologin?sclient_id=hwsys"))
        assertFalse(isAcmTokenCarrier("https://evil.example/hwsys/home"))
        assertEquals(
            "https://acm.sztu.edu.cn/sso/ssologin?sclient_id=hwsys",
            HwsysUrls.SSO_START,
        )
    }

    @Test
    fun cancellationIsNotReportedAsAttendanceFailure() {
        val api = AttendanceApi { _, _, _, _ -> throw CancellationException("stop") }
        try {
            fetchTerm(
                api,
                "token",
                "abc",
                parseTerm("2026-2027-1")!!,
                DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)),
                true,
                "user",
                1L,
            ) { true }
            throw AssertionError("应该继续抛出取消")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    private fun sampleCache() = CacheBody(
        accountHash = "abc",
        termId = "2026-2027-1",
        dateStart = "2026-09-01",
        dateEnd = "2026-11-09",
        rangeConfirmed = true,
        rangeSource = "user",
        fetchedAt = 1L,
        queryComplete = true,
        foreignDropped = false,
        identityIncomplete = false,
        records = emptyList(),
    )

    private fun hooks(
        jwxtReady: Boolean = true,
        promptJwxt: suspend () -> Boolean = { true },
        promptHwsys: suspend () -> Boolean = { true },
        prepare: suspend () -> HwsysAccess = { HwsysAccess.Ready("token") },
        schedule: suspend () -> Pair<Boolean, String> = { true to "" },
        attendance: suspend () -> FetchOutcome = { FetchOutcome.Success(sampleCache()) },
        onStage: (String) -> Unit = {},
    ) = SyncHooks(
        jwxtHasSession = { jwxtReady },
        scheduleErrorLooksLikeAuth = { it.contains("过期") || it.contains("未登录") || it.contains("重新登录") },
        promptJwxt = promptJwxt,
        promptHwsys = promptHwsys,
        prepareAccess = prepare,
        syncSchedule = schedule,
        syncAttendance = attendance,
        onStage = onStage,
    )
}
