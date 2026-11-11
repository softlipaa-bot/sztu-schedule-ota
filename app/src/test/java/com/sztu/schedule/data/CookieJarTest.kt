package com.sztu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告 J03/J06 的离线回归：Cookie 作用域、会话三态、空学期与结构变化的区分。
 * 全部是纯函数，不依赖 Android 运行时。
 */
class CookieJarTest {

    @Test
    fun parse_keeps_first_value_for_duplicate_names() {
        val pairs = CookieJar.parse("JSESSIONID=aaa; foo=1; JSESSIONID=bbb")
        assertEquals(listOf("JSESSIONID" to "aaa", "foo" to "1"), pairs)
    }

    @Test
    fun parse_ignores_garbage_pairs() {
        assertEquals(emptyList<Pair<String, String>>(), CookieJar.parse(";;  ;=; a"))
        assertEquals(listOf("a" to "1"), CookieJar.parse(" a = 1 "))
    }

    @Test
    fun same_host_paths_merge_without_losing_entries() {
        val header = CookieJar.headerForSameHost(
            listOf("JSESSIONID=abc; route=r1", "JSESSIONID=def; extra=1", null)
        )
        assertEquals("JSESSIONID=abc; route=r1; extra=1", header)
    }

    @Test
    fun has_session_id_is_case_insensitive_and_requires_value() {
        assertTrue(CookieJar.hasSessionId("JSESSIONID=abc"))
        assertTrue(CookieJar.hasSessionId("jsessionid=abc; x=y"))
        assertFalse(CookieJar.hasSessionId("JSESSIONID="))
        assertFalse(CookieJar.hasSessionId("route=r1"))
        assertFalse(CookieJar.hasSessionId(""))
    }

    @Test
    fun host_is_extracted_for_scoping() {
        assertEquals("jwxt.sztu.edu.cn", CookieJar.hostOf("https://jwxt.sztu.edu.cn/jsxsd/xskb/xskb_list.do"))
        assertEquals("auth.sztu.edu.cn", CookieJar.hostOf("https://auth.sztu.edu.cn/idp/authcenter"))
        assertEquals("", CookieJar.hostOf("not a url"))
    }

    // --- 会话三态 ---------------------------------------------------------

    @Test
    fun fresh_verification_means_server_verified() {
        assertEquals(
            SessionState.SERVER_VERIFIED,
            SessionStateResolver.resolve(
                nowMs = 1_000_000L,
                verifiedAtMs = 1_000_000L - 60_000L,
                verifiedTtlMs = 12 * 3600 * 1000L,
                hasCookie = true,
                hasCachedCourses = true,
            ),
        )
    }

    @Test
    fun stale_verification_downgrades_to_cached_only() {
        assertEquals(
            SessionState.CACHED_ONLY,
            SessionStateResolver.resolve(
                nowMs = 100_000_000L,
                verifiedAtMs = 1_000L,
                verifiedTtlMs = 60_000L,
                hasCookie = true,
                hasCachedCourses = true,
            ),
        )
    }

    @Test
    fun cached_schedule_without_verification_is_not_logged_in_proof() {
        assertEquals(
            SessionState.CACHED_ONLY,
            SessionStateResolver.resolve(0L, 0L, 60_000L, hasCookie = true, hasCachedCourses = true),
        )
    }

    @Test
    fun nothing_at_all_is_none() {
        assertEquals(
            SessionState.NONE,
            SessionStateResolver.resolve(0L, 0L, 60_000L, hasCookie = false, hasCachedCourses = false),
        )
    }

    @Test
    fun clock_skew_does_not_report_verified() {
        // verifiedAt 在未来（用户改过系统时间）→ 不能当作已验证
        assertEquals(
            SessionState.CACHED_ONLY,
            SessionStateResolver.resolve(1_000L, 2_000L, 60_000L, hasCookie = true, hasCachedCourses = true),
        )
    }

    // --- 空学期 vs 结构变化（J06） ----------------------------------------

    @Test
    fun timetable_page_detected_even_with_zero_courses() {
        // 空学期：有课表容器、没有课程内容 → 应判为可解析（正常空学期）
        assertTrue(
            ScheduleRepository.looksLikeTimetablePage(
                "<html><table id=\"timetable\"><tr><td>无课程</td></tr></table></html>"
            )
        )
    }

    @Test
    fun login_page_is_not_a_timetable_page() {
        assertFalse(
            ScheduleRepository.looksLikeTimetablePage(
                "<html><input id=\"userAccount\"><input type=\"password\"></html>"
            )
        )
    }

    @Test
    fun blank_html_is_not_a_timetable_page() {
        assertFalse(ScheduleRepository.looksLikeTimetablePage(""))
    }

    // ---- R07（复核报告）：同域不同 path 的同名 Cookie 不能取错 ----

    @Test
    fun r07_same_domain_different_paths_keep_their_own_values() {
        val scopes = listOf(
            "/" to "JSESSIONID=root-value",
            "/jsxsd/" to "JSESSIONID=deep-value"
        )
        // 课表请求必须用 /jsxsd/ 作用域的值，而不是根路径的值
        assertEquals(
            "JSESSIONID=deep-value",
            CookieJar.pickForPath(scopes, "/jsxsd/xskb/xskb_list.do")
        )
        // 其它路径不能拿到 /jsxsd/ 专有的 Cookie
        assertEquals("JSESSIONID=root-value", CookieJar.pickForPath(scopes, "/_UserCenter/MB/index.aspx"))
        assertEquals("JSESSIONID=root-value", CookieJar.pickForPath(scopes, "/"))
    }

    @Test
    fun r07_longest_matching_scope_wins() {
        val scopes = listOf(
            "/" to "a=1",
            "/jsxsd/" to "a=2",
            "/jsxsd/xskb/" to "a=3"
        )
        assertEquals("a=3", CookieJar.pickForPath(scopes, "/jsxsd/xskb/xskb_list.do"))
        assertEquals("a=2", CookieJar.pickForPath(scopes, "/jsxsd/framework/xsMain.jsp"))
        assertEquals("a=1", CookieJar.pickForPath(scopes, "/AppCode/LoginInfo.ashx"))
    }

    @Test
    fun r07_path_prefix_must_be_segment_aligned() {
        val scopes = listOf("/jsxsd/" to "a=deep")
        // /jsxsd2/... 不是 /jsxsd/ 的子路径，不能命中
        assertEquals("", CookieJar.pickForPath(scopes, "/jsxsd2/x.do"))
        assertEquals("a=deep", CookieJar.pickForPath(scopes, "/jsxsd/x.do"))
    }

    @Test
    fun r07_scopes_roundtrip() {
        val scopes = mapOf("/" to "a=1; b=2", "/jsxsd/" to "JSESSIONID=deep")
        val encoded = CookieJar.encodeScopes(scopes)
        val decoded = CookieJar.decodeScopes(encoded).toMap()
        assertEquals(scopes, decoded)
        assertEquals("", CookieJar.encodeScopes(emptyMap()))
        assertEquals(emptyList<Pair<String, String>>(), CookieJar.decodeScopes(""))
    }

    @Test
    fun r07_host_must_match_exactly() {
        assertTrue(CookieJar.isKnownHost("jwxt.sztu.edu.cn"))
        assertTrue(CookieJar.isKnownHost("JWXT.SZTU.EDU.CN"))
        assertTrue(CookieJar.isKnownHost("auth.sztu.edu.cn"))
        // 相似前缀域不能被当作受信域（旧实现用 endsWith 会误判）
        assertFalse(CookieJar.isKnownHost("evil-jwxt.sztu.edu.cn.evil.test"))
        assertFalse(CookieJar.isKnownHost("jwxt.sztu.edu.cn.evil.test"))
        assertFalse(CookieJar.isKnownHost(""))
    }

    @Test
    fun r07_path_of_url() {
        assertEquals("/jsxsd/xskb/xskb_list.do", CookieJar.pathOf("https://jwxt.sztu.edu.cn/jsxsd/xskb/xskb_list.do"))
        assertEquals("/", CookieJar.pathOf("https://jwxt.sztu.edu.cn"))
        assertEquals("/", CookieJar.pathOf("not a url"))
    }
}
