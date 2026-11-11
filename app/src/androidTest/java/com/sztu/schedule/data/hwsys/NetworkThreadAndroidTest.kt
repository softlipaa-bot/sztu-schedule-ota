package com.sztu.schedule.data.hwsys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * 在 Android 运行环境里从主线程进入 Repository，确认真实 HttpURLConnection 不在主线程。
 * 令牌是测试用的假 JWT，不是任何人的登录态。
 */
@RunWith(AndroidJUnit4::class)
class NetworkThreadAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun syncWithCandidateTokenDoesNotUseTheMainThread() = runBlocking(Dispatchers.Main) {
        val session = HwsysSessionStore(context)
        val expMs = System.currentTimeMillis() + 3_600_000L
        session.installProcessSession(fakeJwt(expMs / 1000), "", "thread-probe", expMs)
        val repo = AttendanceRepository(context, session, HwsysHttp(), HwsysHttp())
        val outcome = repo.sync("2026-2027-1")
        assertFalse(NetworkThreadGuard.lastWasMain)
        assertEquals("出勤查询", NetworkThreadGuard.lastStage)
        assertFalse(NetworkThreadGuard.lastThreadName.contains("main", ignoreCase = true))
        assertTrue(outcome is FetchOutcome.Failed || outcome is FetchOutcome.Success)
        if (outcome is FetchOutcome.Failed) {
            assertFalse(outcome.message.contains("NetworkOnMainThread"))
            assertFalse(outcome.message.contains("eyJ"))
        }
    }

    @Test
    fun tokenRefreshDoesNotUseTheMainThread() = runBlocking(Dispatchers.Main) {
        val session = HwsysSessionStore(context)
        session.installProcessSession(fakeJwt(10), fakeJwt(4_000_000_000L), "thread-probe", 1L)
        val repo = AttendanceRepository(context, session, HwsysHttp(), HwsysHttp())
        val access = repo.prepareAccess()
        assertFalse(NetworkThreadGuard.lastWasMain)
        assertEquals("登录续期", NetworkThreadGuard.lastStage)
        assertTrue(access is HwsysAccess.Failed || access is HwsysAccess.NeedsLogin)
        if (access is HwsysAccess.Failed) {
            assertFalse(access.message.contains("NetworkOnMainThread"))
        }
    }

    private fun fakeJwt(expSeconds: Long): String {
        fun part(json: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        return part("""{"alg":"none"}""") + "." + part("""{"exp":$expSeconds,"iat":1}""") + ".sig"
    }
}
