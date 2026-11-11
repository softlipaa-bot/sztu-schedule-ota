package com.sztu.schedule.data.hwsys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * 出勤查询的离线契约。401 正文来自 2026-11-09 对官网未登录请求的实响应；
 * 其余 JSON 只复用官网前端出现过的字段名，不代表某位同学的记录。
 */
class AttendanceLogicTest {
    private val now = Instant.parse("2026-11-09T00:00:00Z") // 上海 11-09 08:00

    @Test
    fun live_401_is_auth_failure_not_an_empty_term() {
        val body = """{"status_code":401,"detail":"请先登录","data":null}"""
        val envelope = parseEnvelope(401, body)
        assertTrue(envelope.authRequired)
        assertTrue(envelope.records.isEmpty())
        val view = buildView(
            loggedIn = false,
            hasAccount = false,
            termId = "2026-2027-1",
            cache = null,
            error = envelope.detail,
            errorAt = now.toEpochMilli(),
            now = now,
            isScheduleTerm = true,
            displayStart = "",
            displayEnd = "",
            displayConfirmed = false,
            displaySource = "",
        )
        assertEquals("logged_out", view.phase)
        assertNull(view.confirmedAbsences)
        assertFalse(view.headline.contains("缺勤"))
        assertEquals("未登录", view.headline)
    }

    @Test
    fun closed_unsigned_is_confirmed_absence_and_open_unsigned_is_not() {
        val records = listOf(
            raw("ended", "示例课", "2026-11-09 07:00:00", "2026-11-09 07:40:00", 0),
            raw("open", "示例课", "2026-11-09 10:00:00", "2026-11-09 11:40:00", 0),
            raw("same-name", "示例课", "2026-10-24 08:30:00", "2026-10-24 09:55:00", 10),
            raw("missing", "另一门", "2026-10-01 08:30:00", "2026-10-01 09:55:00", null),
        )
        val batch = classifyBatch(records, "2026-2027-1", now)
        assertEquals(4, batch.rows.size)
        assertEquals("confirmed_absence", batch.rows.first { it.id == "ended" }.kind)
        assertEquals("未签", batch.rows.first { it.id == "ended" }.status)
        assertEquals("pending", batch.rows.first { it.id == "open" }.kind)
        assertEquals("settled", batch.rows.first { it.id == "same-name" }.kind)
        assertEquals("unknown", batch.rows.first { it.id == "missing" }.kind)
        assertEquals(1, batch.rows.count { it.kind == "confirmed_absence" })
    }

    @Test
    fun same_course_name_with_different_ids_stays_two_rows() {
        val batch = classifyBatch(
            listOf(
                raw("a", "高等数学", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0),
                raw("b", "高等数学", "2026-10-08 08:30:00", "2026-10-08 09:55:00", 0),
            ),
            "2026-2027-1",
            now,
        )
        assertEquals(2, batch.rows.size)
        assertEquals(2, batch.rows.count { it.kind == "confirmed_absence" })
    }

    @Test
    fun duplicate_id_does_not_merge_by_name() {
        val batch = classifyBatch(
            listOf(
                raw("same", "课程甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0),
                raw("same", "课程乙", "2026-10-02 08:30:00", "2026-10-02 09:55:00", 0),
            ),
            "2026-2027-1",
            now,
        )
        assertEquals(1, batch.rows.size)
        assertEquals("课程甲", batch.rows.single().title)
        assertEquals(1, batch.duplicateIds)
    }

    @Test
    fun other_term_rows_are_not_counted_and_block_a_clean_zero() {
        val cache = cache(
            records = listOf(raw("x", "别的学期", "2026-03-01 08:30:00", "2026-03-01 09:55:00", 0, term = "2025-2026-2")),
            complete = true,
            confirmed = true,
        )
        val view = viewOf(cache, isScheduleTerm = false)
        assertEquals(0, view.rows.size)
        assertEquals(0, view.confirmedAbsences)
        assertFalse(view.headline.contains("暂无缺勤"))
        assertTrue(view.note.contains("其他学期"))
    }

    @Test
    fun unconfirmed_range_cannot_claim_a_clean_semester() {
        val cache = cache(records = emptyList(), complete = true, confirmed = false)
        val view = viewOf(cache, isScheduleTerm = true)
        assertEquals("没有已确认缺勤", view.headline)
        assertTrue(view.note.contains("待确认"))
        assertFalse(view.headline.contains("暂无缺勤"))
    }

    @Test
    fun complete_confirmed_current_term_can_say_none() {
        val cache = cache(records = emptyList(), complete = true, confirmed = true)
        val view = viewOf(cache, isScheduleTerm = true)
        assertEquals("本学期暂无缺勤记录", view.headline)
        assertEquals(0, view.confirmedAbsences)
    }

    @Test
    fun historical_zero_does_not_claim_full_coverage() {
        val cache = cache(records = emptyList(), complete = true, confirmed = true)
        val view = viewOf(cache, isScheduleTerm = false)
        assertEquals("该学期查询范围内没有已确认缺勤", view.headline)
        assertTrue(view.note.contains("不代表官方系统覆盖了全部课程"))
    }

    @Test
    fun failure_without_cache_is_not_zero_absences() {
        val view = buildView(
            loggedIn = true,
            hasAccount = true,
            termId = "2026-2027-1",
            cache = null,
            error = "网络请求失败",
            errorAt = now.toEpochMilli(),
            now = now,
            isScheduleTerm = true,
            displayStart = "2026-09-01",
            displayEnd = "2026-11-09",
            displayConfirmed = false,
            displaySource = "hwsys_term_util_heuristic",
        )
        assertEquals("failed", view.phase)
        assertEquals("获取失败", view.headline)
        assertNull(view.confirmedAbsences)
        assertEquals("获取失败", view.listEmpty)
    }

    @Test
    fun failure_keeps_previous_counts() {
        val cache = cache(
            records = listOf(raw("ended", "示例课", "2026-10-24 08:30:00", "2026-10-24 09:55:00", 0)),
            complete = true,
            confirmed = true,
        )
        val view = buildView(
            loggedIn = true,
            hasAccount = true,
            termId = "2026-2027-1",
            cache = cache,
            error = "网络请求失败",
            errorAt = now.toEpochMilli(),
            now = now,
            isScheduleTerm = true,
            displayStart = cache.dateStart,
            displayEnd = cache.dateEnd,
            displayConfirmed = true,
            displaySource = "user",
        )
        assertEquals(1, view.confirmedAbsences)
        assertEquals("已确认缺勤 1次", view.headline)
        assertTrue(view.note.contains("获取失败"))
        assertTrue(view.note.contains("上次成功"))
    }

    @Test
    fun unsynced_account_does_not_show_zero() {
        val view = buildView(
            loggedIn = true,
            hasAccount = true,
            termId = "2026-2027-1",
            cache = null,
            error = "",
            errorAt = 0L,
            now = now,
            isScheduleTerm = true,
            displayStart = "2026-09-01",
            displayEnd = "2027-01-31",
            displayConfirmed = false,
            displaySource = "hwsys_term_util_heuristic",
        )
        assertEquals("unsynced", view.phase)
        assertEquals("未同步", view.headline)
        assertNull(view.confirmedAbsences)
    }

    @Test
    fun segments_cover_every_day_once() {
        val start = LocalDate.of(2026, 9, 1)
        val end = start.plusDays(180)
        val parts = segmentRange(start, end, 180)
        assertEquals(2, parts.size)
        assertEquals(start, parts[0].start)
        assertEquals(start.plusDays(179), parts[0].end)
        assertEquals(start.plusDays(180), parts[1].start)
        assertEquals(end, parts[1].end)
        assertEquals(parts[0].end.plusDays(1), parts[1].start)
    }

    @Test
    fun heuristic_is_explicitly_unconfirmed_and_not_march_first() {
        val range = heuristicRange(parseTerm("2026-2027-1")!!)
        assertEquals(LocalDate.of(2026, 9, 1), range.start)
        assertEquals(LocalDate.of(2027, 1, 31), range.end)
        val spring = heuristicRange(parseTerm("2025-2026-2")!!)
        assertEquals(LocalDate.of(2026, 2, 1), spring.start)
        assertFalse(spring.start.monthValue == 3 && spring.start.dayOfMonth == 1)
    }

    @Test
    fun paging_merges_pages_and_stops_when_a_later_page_repeats() {
        val calls = ArrayList<Int?>()
        val api = AttendanceApi { _, _, _, page ->
            calls += page
            if (page == null) pageOf(total = 2, raw("p1", "甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0))
            else pageOf(total = 2, raw("p2", "乙", "2026-10-08 08:30:00", "2026-10-08 09:55:00", 10))
        }
        val outcome = fetchTerm(
            api, "token", "abc", parseTerm("2026-2027-1")!!,
            DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)),
            true, "user", now.toEpochMilli(), { true },
        )
        assertTrue(outcome is FetchOutcome.Success)
        val cache = (outcome as FetchOutcome.Success).cache
        assertTrue(cache.queryComplete)
        assertEquals(listOf("p1", "p2"), cache.records.map { it.stableId })
        assertEquals(listOf(null, 2), calls)

        val repeat = AttendanceApi { _, _, _, page ->
            if (page == null) pageOf(total = 4, raw("only", "甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0))
            else pageOf(total = 4, raw("only", "甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0))
        }
        val incomplete = fetchTerm(
            repeat, "token", "abc", parseTerm("2026-2027-1")!!,
            DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)),
            true, "user", now.toEpochMilli(), { true },
        ) as FetchOutcome.Success
        assertFalse(incomplete.cache.queryComplete)
        val shown = viewOf(incomplete.cache, true)
        assertEquals("查询未完成，已看到 1 次缺勤", shown.headline)
        assertFalse(shown.headline.contains("暂无缺勤"))
    }

    @Test
    fun auth_error_does_not_become_a_successful_empty_cache() {
        val api = AttendanceApi { _, _, _, _ -> parseEnvelope(401, """{"status_code":401,"detail":"请先登录","data":null}""") }
        val outcome = fetchTerm(
            api, "token", "abc", parseTerm("2026-2027-1")!!,
            DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)),
            false, "hwsys_term_util_heuristic", now.toEpochMilli(), { true },
        )
        assertTrue(outcome is FetchOutcome.Failed)
        assertTrue((outcome as FetchOutcome.Failed).auth)
    }

    @Test
    fun stale_generation_drops_the_result() {
        var current = true
        val api = AttendanceApi { _, _, _, _ ->
            current = false
            pageOf(total = null, raw("p1", "甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0))
        }
        val outcome = fetchTerm(
            api, "token", "abc", parseTerm("2026-2027-1")!!,
            DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)),
            true, "user", now.toEpochMilli(), { current },
        )
        assertTrue(outcome is FetchOutcome.Stale)
    }

    @Test
    fun attendance_url_uses_the_official_query_shape() {
        val url = attendanceUrl(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 9))
        assertTrue(url.startsWith("https://acm.sztu.edu.cn:40130/api/signin_my_attendance?query="))
        val json = java.net.URLDecoder.decode(url.substringAfter("query="), "UTF-8")
        val query = JSONObject(json)
        assertEquals("2026-09-01", query.getJSONObject("param").getString("date_start"))
        assertEquals("2026-11-09", query.getJSONObject("param").getString("date_end"))
        assertFalse(url.startsWith("http://"))
    }

    @Test
    fun token_text_is_redacted_and_login_hosts_stay_on_school_https() {
        val token = "aaaaaaaa.bbbbbbbb.cccccccc"
        val cleaned = redact("失败 $token")
        assertFalse(cleaned.contains(token))
        assertTrue(cleaned.contains("[redacted]"))
        assertEquals(token, extractSsoTokenPlain("https://acm.sztu.edu.cn/sso/cb?access_token_sso=$token"))
        assertNull(extractSsoTokenPlain("https://acm.sztu.edu.cn/sso/cb"))
        assertTrue(allowedSchoolHost("https", "acm.sztu.edu.cn"))
        assertTrue(allowedSchoolHost("https", "auth.sztu.edu.cn"))
        assertFalse(allowedSchoolHost("http", "acm.sztu.edu.cn"))
        assertFalse(allowedSchoolHost("https", "evil-sztu.edu.cn.example"))
    }

    @Test
    fun switching_terms_does_not_reuse_the_other_cache() {
        val fall = cache(records = listOf(raw("a", "甲", "2026-10-01 08:30:00", "2026-10-01 09:55:00", 0)), complete = true, confirmed = true)
        val springView = buildView(
            loggedIn = true,
            hasAccount = true,
            termId = "2025-2026-2",
            cache = fall,
            error = "",
            errorAt = 0L,
            now = now,
            isScheduleTerm = false,
            displayStart = "2026-02-01",
            displayEnd = "2026-07-14",
            displayConfirmed = false,
            displaySource = "hwsys_term_util_heuristic",
        )
        assertEquals("unsynced", springView.phase)
        assertNull(springView.confirmedAbsences)
        assertTrue(springView.rows.isEmpty())
    }

    @Test
    fun sync_messages_keep_partial_success() {
        assertEquals("已取消登录", SyncText.compose(true, false, false, false, false, false))
        assertEquals("课表已同步，出勤需重新登录", SyncText.compose(false, true, true, false, false, true))
        assertEquals("课表已同步，出勤获取失败", SyncText.compose(false, true, true, true, false, false))
        assertEquals("出勤已同步，课表同步失败", SyncText.compose(false, true, false, true, true, false))
        assertEquals("已同步课表与出勤", SyncText.compose(false, true, true, true, true, false))
        assertEquals("同步中…", SyncText.buttonLabel(true, true, true, false))
        assertEquals("登录并同步", SyncText.buttonLabel(false, false, true, false))
        assertEquals("重新同步", SyncText.buttonLabel(false, true, true, true))
        assertEquals("同步数据", SyncText.buttonLabel(false, true, true, false))
    }

    @Test
    fun selecting_history_does_not_change_the_schedule_term_summary() {
        val choices = termChoices("2026-2027-1")
        assertTrue(choices.any { it.text == "2026-2027-1" })
        assertTrue(choices.any { it.text == "2025-2026-2" })
        assertEquals("2026-2027-1", parseTerm("2026-2027-1")!!.text)
    }

    private fun viewOf(cache: CacheBody, isScheduleTerm: Boolean): BuiltView = buildView(
        loggedIn = true,
        hasAccount = true,
        termId = cache.termId,
        cache = cache,
        error = "",
        errorAt = 0L,
        now = now,
        isScheduleTerm = isScheduleTerm,
        displayStart = cache.dateStart,
        displayEnd = cache.dateEnd,
        displayConfirmed = cache.rangeConfirmed,
        displaySource = cache.rangeSource,
    )

    private fun cache(records: List<RawAttendance>, complete: Boolean, confirmed: Boolean) = CacheBody(
        accountHash = "abc",
        termId = "2026-2027-1",
        dateStart = "2026-09-01",
        dateEnd = "2027-01-31",
        rangeConfirmed = confirmed,
        rangeSource = if (confirmed) "user" else "hwsys_term_util_heuristic",
        fetchedAt = now.toEpochMilli(),
        queryComplete = complete,
        foreignDropped = records.any { it.term.isNotBlank() && it.term != "2026-2027-1" },
        identityIncomplete = records.any { it.stableId == null },
        records = records,
    )

    private fun raw(
        id: String,
        course: String,
        start: String,
        end: String,
        status: Int?,
        term: String = "2026-2027-1",
    ) = RawAttendance(
        stableId = id,
        title = "课堂事项",
        course = course,
        taskTitle = "第1次",
        term = term,
        timeStart = start,
        timeEnd = end,
        status = status,
        statusPresent = status != null,
    )

    private fun pageOf(total: Int?, record: RawAttendance): Envelope {
        return Envelope(200, "", listOf(record), total, null, false, true)
    }
}
