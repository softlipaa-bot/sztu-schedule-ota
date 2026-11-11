package com.sztu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析预览：改完必跑，避免「通知单编号」当课名、实验周丢失再进包。
 */
class ScheduleHtmlParserTest {

    private fun fixture(): String {
        val fromCp = javaClass.classLoader?.getResourceAsStream("fixtures/wed_mcu_cell.html")
        if (fromCp != null) return fromCp.bufferedReader().readText()
        // 直接 java -cp 跑时没有 test resources
        val fallback = java.io.File("src/test/resources/fixtures/wed_mcu_cell.html")
        check(fallback.exists()) { "fixture missing: ${fallback.absolutePath}" }
        return fallback.readText(Charsets.UTF_8)
    }

    /** 与 App.vue / ScheduleRepository 一致的周次匹配（精简版） */
    private fun matchesWeek(weeks: String, week: Int): Boolean {
        val s = weeks.replace("（", "(").replace("）", ")")
        if (s.contains("单周") && week % 2 == 0) return false
        if (s.contains("双周") && week % 2 == 1) return false
        val body = s.substringBefore("(").substringBefore("[").trim()
        if (body.isEmpty()) return false
        for (part in body.split(Regex("""[,，、]"""))) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if ("-" in p || "~" in p || "—" in p || "～" in p) {
                val bits = p.split(Regex("""[-~—～]""")).mapNotNull { it.trim().toIntOrNull() }
                if (bits.size >= 2 && week in bits[0]..bits[1]) return true
            } else {
                p.toIntOrNull()?.let { if (it == week) return true }
            }
        }
        return false
    }

    @Test
    fun preview_and_assert_fixture() {
        val snap = ScheduleHtmlParser.parse(fixture())

        println("======== PARSE PREVIEW ======== ")
        println("term=${snap.term} courses=${snap.courses.size}")
        snap.courses.forEach { c ->
            println(
                "W${c.weekday} 第${c.startSection}-${c.endSection}节 | ${c.name} | ${c.teacher} | ${c.place} | ${c.weeks}"
            )
        }
        println("======== END PREVIEW ======== ")

        // 绝不能出现通知单当课名
        assertTrue(
            "通知单 mixed into names: ${snap.courses.map { it.name }}",
            snap.courses.none { it.name.contains("通知单") },
        )

        val mcu = snap.courses.filter { it.name.contains("单片机") && it.weekday == 3 }
        assertEquals("单片机应拆成 3 段周次", 3, mcu.size)
        assertTrue(mcu.any { it.weeks.contains("1,3,5") && it.place.contains("C-5-311") })
        assertTrue(mcu.any { it.weeks.contains("7,9,11") && it.place.contains("实验室") })
        assertTrue(mcu.any { it.weeks.contains("18") && it.place.contains("实验室") })

        // 第 7 周周三上午应有实验
        val week7Wed = mcu.filter { matchesWeek(it.weeks, 7) }
        assertEquals("week7 应命中实验室段", 1, week7Wed.size)
        assertEquals(1, week7Wed[0].startSection)
        assertEquals(4, week7Wed[0].endSection)
        assertEquals("08:30", week7Wed[0].startTime)
        assertEquals("11:40", week7Wed[0].endTime)

        // 纯通知单格子 → 0 门课
        assertFalse(snap.courses.any { it.weekday == 3 && it.startSection >= 5 && it.name.contains("通知") })

        // 正常课仍在
        assertTrue(snap.courses.any { it.name.contains("概率论") })
        assertTrue(snap.courses.any { it.name.contains("数据结构") })
    }

    @Test
    fun class_line_before_course_is_not_used_as_name() {
        val html = """
            <table id="timetable">
              <tr><th></th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>1-4节</td><td></td><td></td><td><div class="kbcontent">
                班级：25级物联网2班<br/>
                单片机基础与实践<br/>
                7,9,11,13,15,17(周)[01-02-03-04节]<br/>
                C-1-323
              </div></td></tr>
            </table>
        """.trimIndent()
        val course = ScheduleHtmlParser.parse(html).courses.single()
        assertEquals("单片机基础与实践", course.name)
        assertEquals(3, course.weekday)
        assertEquals("C-1-323", course.place)
        assertEquals("08:30", course.startTime)
        assertEquals("11:40", course.endTime)
    }
}
