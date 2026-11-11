package com.sztu.schedule.data

import com.sztu.schedule.data.ScheduleClock.TodayStatus
import com.sztu.schedule.data.ScheduleClock.WeekMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日页/周次判定的离线回归（报告 J02）。
 *
 * 重点：单双周、某几周授课、当周无课、周次无法解析、下课整分钟。
 * 时间与星期都注入，不依赖运行机器时钟。
 */
class ScheduleClockTest {

    private fun course(
        id: String,
        name: String = id,
        weekday: Int = 3,
        weeks: String = "1-16",
        start: String = "08:30",
        end: String = "09:10",
    ) = Course(
        id = id,
        name = name,
        teacher = "",
        place = "教室",
        weeks = weeks,
        weekday = weekday,
        startSection = 1,
        endSection = 2,
        startTime = start,
        endTime = end,
    )

    // --- matchWeek 三态 ----------------------------------------------------

    @Test
    fun odd_week_course_is_not_matched_in_even_week() {
        assertEquals(WeekMatch.NO_MATCH, ScheduleClock.matchWeek("1-15单周", 8))
        assertEquals(WeekMatch.MATCH, ScheduleClock.matchWeek("1-15单周", 7))
    }

    @Test
    fun even_week_course_is_not_matched_in_odd_week() {
        assertEquals(WeekMatch.NO_MATCH, ScheduleClock.matchWeek("2-16双周", 7))
        assertEquals(WeekMatch.MATCH, ScheduleClock.matchWeek("2-16双周", 8))
    }

    @Test
    fun specific_weeks_list_matches_only_listed_weeks() {
        val weeks = "第7周,第9周,第11周"
        assertTrue(ScheduleClock.matchWeek(weeks, 7) == WeekMatch.MATCH)
        assertTrue(ScheduleClock.matchWeek(weeks, 9) == WeekMatch.MATCH)
        assertTrue(ScheduleClock.matchWeek(weeks, 11) == WeekMatch.MATCH)
        assertEquals(WeekMatch.NO_MATCH, ScheduleClock.matchWeek(weeks, 8))
        assertEquals(WeekMatch.NO_MATCH, ScheduleClock.matchWeek(weeks, 10))
    }

    @Test
    fun blank_or_unparsable_weeks_is_unknown_not_no_match() {
        assertEquals(WeekMatch.UNKNOWN, ScheduleClock.matchWeek("", 5))
        assertEquals(WeekMatch.UNKNOWN, ScheduleClock.matchWeek("   ", 5))
        assertEquals(WeekMatch.UNKNOWN, ScheduleClock.matchWeek("实践周", 5))
        // UNKNOWN 视为“可能上课”，不能隐藏课程
        assertTrue(ScheduleClock.matchesWeek("实践周", 5))
    }

    @Test
    fun bracket_and_section_suffix_are_ignored() {
        assertEquals(WeekMatch.MATCH, ScheduleClock.matchWeek("1-16周[01-02节]", 8))
        assertEquals(WeekMatch.NO_MATCH, ScheduleClock.matchWeek("1-6周[01-02节]", 8))
    }

    // --- buildTodayView -----------------------------------------------------

    @Test
    fun no_class_this_week_stays_empty_instead_of_falling_back() {
        // 单周课在第 8 周不应出现在今日页
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", weeks = "1-15单周")),
            teachingWeek = 8,
            weekday = 3,
            nowMinute = 8 * 60,
        )
        assertTrue("本周无课必须为空", view.today.isEmpty())
        assertEquals(TodayStatus.NO_CLASS, view.status)
        assertNull(view.current)
        assertNull(view.next)
        assertNull(view.previous)
    }

    @Test
    fun matched_course_is_visible() {
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", weeks = "1-15单周")),
            teachingWeek = 7,
            weekday = 3,
            nowMinute = 8 * 60,
        )
        assertEquals(1, view.today.size)
        assertEquals(TodayStatus.OK, view.status)
    }

    @Test
    fun unparsable_weeks_keeps_course_and_marks_unknown() {
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", weeks = "实践周")),
            teachingWeek = 7,
            weekday = 3,
            nowMinute = 8 * 60,
        )
        assertEquals(1, view.today.size)
        assertEquals(TodayStatus.WEEK_UNKNOWN, view.status)
    }

    @Test
    fun unknown_teaching_week_does_not_filter() {
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", weeks = "1-15单周")),
            teachingWeek = 0,
            weekday = 3,
            nowMinute = 8 * 60,
        )
        assertEquals(1, view.today.size)
    }

    @Test
    fun class_ends_exactly_now_is_not_current() {
        // 08:30-09:10 的课，09:10 整点应算已结束（start <= now < end）
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", start = "08:30", end = "09:10")),
            teachingWeek = 3,
            weekday = 3,
            nowMinute = 9 * 60 + 10,
        )
        assertNull("下课整分钟不能还算这节", view.current)
        assertEquals("A", view.previous?.id)
    }

    @Test
    fun class_is_current_inside_range_and_next_after_previous_ends() {
        val morning = course("A", start = "08:30", end = "09:10")
        val later = course("B", start = "09:20", end = "10:00")
        val inside = ScheduleClock.buildTodayView(
            all = listOf(morning, later), teachingWeek = 3, weekday = 3, nowMinute = 8 * 60 + 40,
        )
        assertEquals("A", inside.current?.id)
        // A 结束后、B 开始前：上节是 A，下节是 B
        val between = ScheduleClock.buildTodayView(
            all = listOf(morning, later), teachingWeek = 3, weekday = 3, nowMinute = 9 * 60 + 15,
        )
        assertEquals("A", between.previous?.id)
        assertEquals("B", between.next?.id)
    }

    @Test
    fun real_fixture_course_boundaries_0830_to_0955() {
        // 今日页 fixture 的真实时间：08:30–09:55（前端状态卡用同一组边界）。
        // 统一规则 start <= now < end：08:30 整点算上课，09:55 整点已下课。
        val c = course("A", weekday = 6, weeks = "1-18", start = "08:30", end = "09:55")
        fun view(nowMinute: Int) = ScheduleClock.buildTodayView(
            all = listOf(c),
            teachingWeek = 8,
            weekday = 6,
            nowMinute = nowMinute,
        )

        val beforeStart = view(8 * 60 + 29)
        assertNull("08:29 还没开始，不能算正在上课", beforeStart.current)
        assertEquals("A", beforeStart.next?.id)

        assertEquals("A", view(8 * 60 + 30).current?.id) // 上课整分钟
        assertEquals("A", view(9 * 60 + 54).current?.id) // 下课前最后一分钟

        val atEnd = view(9 * 60 + 55)
        assertNull("09:55 下课整分钟不算正在上课", atEnd.current)
        assertEquals("A", atEnd.previous?.id)
        assertNull(atEnd.next)
    }

    @Test
    fun other_weekday_courses_are_ignored() {
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("A", weekday = 1), course("B", weekday = 3)),
            teachingWeek = 3,
            weekday = 3,
            nowMinute = 8 * 60,
        )
        assertEquals(listOf("B"), view.today.map { it.id })
    }

    @Test
    fun sunday_and_monday_are_distinct_weekdays() {
        val view = ScheduleClock.buildTodayView(
            all = listOf(course("SUN", weekday = 7), course("MON", weekday = 1)),
            teachingWeek = 3,
            weekday = 7,
            nowMinute = 8 * 60,
        )
        assertEquals(listOf("SUN"), view.today.map { it.id })
    }
}
