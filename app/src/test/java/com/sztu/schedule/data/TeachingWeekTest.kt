package com.sztu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 报告 J07 的离线回归：教学周计算核心（不依赖 Context 的部分）。
 * 校准值需要 SharedPreferences，属真机/集成范围，这里只验证纯计算。
 */
class TeachingWeekTest {

    private fun cal(y: Int, m: Int, d: Int): Calendar = Calendar.getInstance().apply {
        set(y, m - 1, d, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun monday(y: Int, m: Int, d: Int) = cal(y, m, d)

    @Test
    fun same_day_as_start_is_week_one() {
        val start = monday(2026, 8, 31)
        assertEquals(1, TeachingWeek.weeksFrom(start, cal(2026, 8, 31)))
    }

    @Test
    fun sunday_of_first_week_is_still_week_one() {
        val start = monday(2026, 8, 31)
        assertEquals(1, TeachingWeek.weeksFrom(start, cal(2026, 9, 6)))
    }

    @Test
    fun next_monday_is_week_two() {
        val start = monday(2026, 8, 31)
        assertEquals(2, TeachingWeek.weeksFrom(start, cal(2026, 9, 7)))
    }

    @Test
    fun tenth_week_is_computed_across_month_boundary() {
        val start = monday(2026, 8, 31)
        // 8/31 + 9 周 = 11/2
        assertEquals(10, TeachingWeek.weeksFrom(start, cal(2026, 11, 2)))
    }

    @Test
    fun dates_before_start_are_clamped_to_week_one() {
        val start = monday(2026, 8, 31)
        assertEquals(1, TeachingWeek.weeksFrom(start, cal(2026, 7, 1)))
    }

    @Test
    fun very_late_dates_are_clamped_to_thirty() {
        val start = monday(2026, 8, 31)
        assertEquals(30, TeachingWeek.weeksFrom(start, cal(2028, 1, 1)))
    }

    @Test
    fun spring_term_reference_monday_is_derived_from_march_first() {
        // 2026-03-01 是周日 → 该周周一应为 2026-02-23
        val monday = TeachingWeek.week1Monday("2025-2026-2", cal(2026, 4, 1))
        assertEquals(2026, monday.get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, monday.get(Calendar.MONTH))
        assertEquals(23, monday.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, monday.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun autumn_term_reference_monday_is_derived_from_september_first() {
        // 2026-09-01 是周二 → 该周周一应为 2026-08-31
        val monday = TeachingWeek.week1Monday("2026-2027-1", cal(2026, 10, 1))
        assertEquals(2026, monday.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, monday.get(Calendar.MONTH))
        assertEquals(31, monday.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MONDAY, monday.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun guessed_and_calibrated_paths_share_the_same_core() {
        // 校准起点必须与 weeksFrom 同一套算术：起点 + 2 周 = 第 3 周
        val calibratedStart = monday(2026, 9, 14)
        assertEquals(3, TeachingWeek.weeksFrom(calibratedStart, cal(2026, 9, 28)))
    }
}
