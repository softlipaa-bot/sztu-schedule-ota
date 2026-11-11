package com.sztu.schedule.data

import android.content.Context
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 教学周计算（报告 J07）。
 *
 * 默认仍按「含 9/1 或 3/1 的那一周周一 = 第 1 周」推算，但**不再把它当唯一真相**：
 * - 支持保存本学期校历起点（用户校准后的真实开学周一）；
 * - 支持单日例外（调课/停课/放假）由上层根据 `sztu_calendar` 覆盖；
 * - 计算核心只接受标准输入（termStart/date/courseWeekSet），避免前后端各自猜。
 */
object TeachingWeek {
    private const val PREFS = "sztu_calendar"
    private const val KEY_TERM_START = "term_start_ms"
    private const val KEY_TERM_TAG = "term_start_tag"

    /** 校历起点来源：用于 UI 说明“这个周次是怎么算出来的”。 */
    enum class StartSource { CALIBRATED, GUESSED }

    fun week1Monday(term: String = "", now: Calendar = Calendar.getInstance()): Calendar {
        val spring = termContainsSpring(term)
        val yearHint = Regex("""(20\d{2})""").find(term)?.groupValues?.get(1)?.toIntOrNull()
        val y = yearHint ?: now.get(Calendar.YEAR)
        val ref = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            if (spring) {
                // 春季一般对应学年后半：term 如 2025-2026-2 → 用 2026 年 3/1
                val springYear = if (yearHint != null && term.contains("-")) {
                    Regex("""20\d{2}-?(20\d{2})""").find(term)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (y + 1)
                } else {
                    val m = now.get(Calendar.MONTH) + 1
                    if (m in 1..7) y else y + 1
                }
                set(Calendar.YEAR, springYear)
                set(Calendar.MONTH, Calendar.MARCH)
                set(Calendar.DAY_OF_MONTH, 1)
            } else {
                val fallYear = when {
                    yearHint != null -> y
                    now.get(Calendar.MONTH) + 1 < 8 -> y - 1
                    else -> y
                }
                set(Calendar.YEAR, fallYear)
                set(Calendar.MONTH, Calendar.SEPTEMBER)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 回退到该周周一（含 9/1 或 3/1 的那一周）
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                add(Calendar.DAY_OF_MONTH, -1)
            }
        }
        return ref
    }

    /** 用户校准过的第 1 周周一（按学期隔离）；没有校准则返回 null。 */
    fun calibratedStart(context: Context, term: String): Calendar? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString(KEY_TERM_TAG, "").orEmpty()
        if (tag != termKey(term)) return null
        val ms = prefs.getLong(KEY_TERM_START, 0L)
        if (ms <= 0L) return null
        return Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /** 保存校历起点；传入任意一天会归一到该周周一。 */
    fun setCalibratedStart(context: Context, term: String, day: Calendar) {
        val monday = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
                add(Calendar.DAY_OF_MONTH, -1)
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TERM_START, monday.timeInMillis)
            .putString(KEY_TERM_TAG, termKey(term))
            .apply()
    }

    fun clearCalibration(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun startSource(context: Context, term: String): StartSource =
        if (calibratedStart(context, term) != null) StartSource.CALIBRATED else StartSource.GUESSED

    /** 优先用校准起点；没有校准时退回按月份推算。 */
    fun effectiveWeek1Monday(context: Context, term: String, now: Calendar = Calendar.getInstance()): Calendar =
        calibratedStart(context, term) ?: week1Monday(term, now)

    fun current(term: String = "", now: Calendar = Calendar.getInstance()): Int =
        weeksFrom(week1Monday(term, now), now)

    fun current(context: Context, term: String = "", now: Calendar = Calendar.getInstance()): Int =
        weeksFrom(effectiveWeek1Monday(context, term, now), now)

    /** 纯计算：给定第 1 周周一与今天，返回教学周（1..30）。 */
    fun weeksFrom(start: Calendar, now: Calendar): Int {
        // 两端都归一到当天 00:00，避免校准起点带时分时少算一周
        val startDay = (start.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffMs = today.timeInMillis - startDay.timeInMillis
        val weeks = TimeUnit.MILLISECONDS.toDays(diffMs).toInt() / 7 + 1
        return weeks.coerceIn(1, 30)
    }

    private fun termKey(term: String): String = term.trim().lowercase(Locale.ROOT)

    private fun termContainsSpring(term: String): Boolean {
        val t = term.trim().lowercase(Locale.ROOT)
        return t.endsWith("-2") || t.endsWith("−2") || Regex("""-\s*2\s*$""").containsMatchIn(t)
    }
}
