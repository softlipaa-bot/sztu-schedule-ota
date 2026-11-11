package com.sztu.schedule.data

import com.sztu.schedule.data.ScheduleClock.WeekMatch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 周次匹配契约（报告 J09）：Kotlin 与前端共用同一份样本文件。
 *
 * 前端 `web/src/App.vue` 的 `weekMatchOf` 必须对同一批样本给出同样结果；
 * 以后给前端补测试时直接读 `fixtures/week_samples.json`，不要再各自手写一套断言。
 */
class WeekContractTest {

    private fun samples(): List<JSONObject> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/week_samples.json")
        assertNotNull("缺少 fixtures/week_samples.json", stream)
        val text = stream!!.bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("samples")
        return buildList {
            for (i in 0 until arr.length()) add(arr.getJSONObject(i))
        }
    }

    @Test
    fun shared_samples_match_production_logic() {
        val failures = StringBuilder()
        for (s in samples()) {
            val weeks = s.optString("weeks")
            val week = s.optInt("week")
            val expect = s.optString("expect")
            val actual = when (ScheduleClock.matchWeek(weeks, week)) {
                WeekMatch.MATCH -> "match"
                WeekMatch.NO_MATCH -> "no"
                WeekMatch.UNKNOWN -> "unknown"
            }
            if (actual != expect) {
                failures.append("weeks=\"$weeks\" week=$week expect=$expect actual=$actual\n")
            }
        }
        assertEquals("周次契约样本不一致：\n$failures", "", failures.toString())
    }

    @Test
    fun sample_file_covers_all_three_states() {
        val states = samples().map { it.optString("expect") }.toSet()
        assertEquals(setOf("match", "no", "unknown"), states)
    }
}
