package com.sztu.schedule.data

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件节次边界闹钟的回归基线（审查增量 J29-03「不得回归」）。
 *
 * `WidgetRefreshScheduler.nextBoundaryMillis()` 用 `SectionTimes.allBoundaryHm()`
 * 生成下一次唤醒点。历史上这份边界是硬编码列表，漏了「1-3 节 11:00」，
 * 导致周三上午的课一直到 11:40 都停在「这节」。这个用例把「必须包含 11:00
 * 以及全部单节起止」钉住，防止以后又被改回硬编码短列表。
 */
class SectionTimesTest {

    private fun boundaries(): Set<Pair<Int, Int>> = SectionTimes.allBoundaryHm().toSet()

    @Test
    fun boundaryListContainsElevenOClock() {
        // 周三「单片机」1-3 节在 11:00 结束，必须能唤醒重绘
        assertTrue("allBoundaryHm() must contain 11:00", (11 to 0) in boundaries())
    }

    @Test
    fun boundaryListContainsEverySectionStartAndEnd() {
        val expected = listOf(
            1 to "08:30", 1 to "09:10",
            2 to "09:15", 2 to "09:55",
            3 to "10:15", 3 to "10:55",
            4 to "11:00", 4 to "11:40",
            5 to "11:45", 5 to "12:25",
            6 to "14:00", 6 to "14:45",
            7 to "15:15", 7 to "15:25",
            8 to "15:45", 8 to "16:30",
            9 to "17:00", 9 to "17:10",
            10 to "17:15", 10 to "17:55",
            11 to "19:00", 11 to "19:40",
            12 to "20:10", 12 to "20:20",
            13 to "20:30", 13 to "21:10",
            14 to "21:40", 14 to "21:50",
        )
        val have = boundaries()
        for ((_, hm) in expected) {
            val h = hm.substringBefore(":").toInt()
            val m = hm.substringAfter(":").toInt()
            assertTrue("allBoundaryHm() must contain $hm", (h to m) in have)
        }
    }

    @Test
    fun boundaryListContainsDayFlipAndIsSortedRange() {
        // 跨日切「今日」的 00:01 也要在列表里，否则跨零点不重绘
        assertTrue((0 to 1) in boundaries())
        for ((h, m) in boundaries()) {
            assertTrue("hour out of range: $h", h in 0..23)
            assertTrue("minute out of range: $m", m in 0..59)
        }
    }

    @Test
    fun sectionRangesStayMonotonicWithinADay() {
        // 单节时间必须递增，避免出现「下一节早于上一节结束」的错表
        var prev = -1
        for (sec in 1..14) {
            val (start, end) = SectionTimes.range(sec, sec)
            val s = start.substringBefore(":").toInt() * 60 + start.substringAfter(":").toInt()
            val e = end.substringBefore(":").toInt() * 60 + end.substringAfter(":").toInt()
            assertTrue("section $sec start must be after previous end ($start)", s > prev)
            assertTrue("section $sec must end after it starts ($start-$end)", e > s)
            prev = e
        }
    }
}
