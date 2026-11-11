package com.sztu.schedule.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * 强智课表 HTML 解析。
 * 可见 `.kbcontent` 负责课名/教师/周次；title 弹层只用来补「上课地点」教室号。
 */
object ScheduleHtmlParser {
    private val dayNames = listOf("", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    private val labelNoise = Regex(
        """^(备注|课程学分|课程属性|课程名称|上课时间|上课地点|教师|授课教师|班级|行政班|教学班|学分|学时|通知单编号|通知单)\s*[：:]?"""
    )

    fun parse(html: String): ScheduleSnapshot {
        val doc = Jsoup.parse(html)
        val term = doc.select("select[name=xnxq01id] option[selected], #xnxq01id option[selected]")
            .firstOrNull()?.text()?.trim().orEmpty()
            .ifBlank {
                Regex("""20\d{2}-20\d{2}-\d""").find(html)?.value.orEmpty()
            }

        val courses = linkedMapOf<String, Course>()
        val table = findScheduleTable(doc)
        if (table != null) parseTable(table, courses)

        if (courses.isEmpty()) {
            doc.select(".kbcontent").forEach { el ->
                if (el.hasClass("kbcontent1")) return@forEach
                if (el.attr("style").contains("display:none", ignoreCase = true)) return@forEach
                val weekday = guessWeekdayFromElement(el)
                if (weekday !in 1..7) return@forEach
                val visible = cellText(el)
                val title = collectTitles(el)
                val course = parseBlock(visible, title, weekday, 1 to 2) ?: return@forEach
                courses.putIfAbsent(course.id, course)
            }
        }

        val sorted = courses.values.sortedWith(compareBy({ it.weekday }, { it.startSection }))
        return ScheduleSnapshot(
            courses = sorted,
            term = term,
            studentName = "",
            className = "",
            syncedAt = System.currentTimeMillis(),
            teachingWeek = TeachingWeek.current(term),
        )
    }

    private fun findScheduleTable(doc: org.jsoup.nodes.Document): Element? {
        doc.selectFirst("#timetable")?.let { return it }
        doc.selectFirst("table.timetable, table#kbtable")?.let { return it }
        for (t in doc.select("table")) {
            val head = t.select("tr").take(3).joinToString(" ") { it.text() }
            if (head.contains("星期") && (head.contains("一") || head.contains("二"))) {
                if (t.text().contains("节") && !isHoursSummaryTable(t)) return t
            }
        }
        return null
    }

    private fun isHoursSummaryTable(table: Element): Boolean {
        val t = table.text()
        val hoursHits = Regex("""课堂讲授""").findAll(t).count()
        val hasPlace = Regex("""[A-Z]-\d-\d|实验室|机房""").containsMatchIn(t)
        val hasWeek = Regex("""\d+-\d+周|\d+周\s*\[""").containsMatchIn(t)
        return hoursHits >= 3 && !hasPlace && !hasWeek
    }

    private fun parseTable(table: Element, courses: LinkedHashMap<String, Course>) {
        for (row in table.select("tr")) {
            val cells = row.select("td, th")
            if (cells.size < 2) continue
            val timeCell = cells.first()?.text().orEmpty()
            if (!timeCell.contains("节")) continue
            val rowSections = parseSectionsFromTimeCell(timeCell)

            for (i in 1 until cells.size) {
                val weekday = i.coerceIn(1, 7)
                val cell = cells[i]
                val kb = cell.selectFirst(".kbcontent:not(.kbcontent1)")
                val visible = if (kb != null) cellText(kb) else cellText(cell)
                val title = collectTitles(cell)
                // 同一格子可能多门课，按空行拆
                val blocks = splitBlocks(visible).ifEmpty {
                    if (visible.isNotBlank()) listOf(visible) else emptyList()
                }
                for (block in blocks) {
                    val course = parseBlock(block, title, weekday, rowSections) ?: continue
                    courses.putIfAbsent(course.id, course)
                }
            }
        }
    }

    private fun collectTitles(el: Element): String {
        val parts = linkedSetOf<String>()
        el.attr("title").trim().takeIf { it.isNotEmpty() }?.let { parts += it.replace("\r", "\n") }
        el.select("[title]").forEach { child ->
            child.attr("title").trim().takeIf { it.isNotEmpty() }?.let { parts += it.replace("\r", "\n") }
        }
        return parts.joinToString("\n")
    }

    private fun cellText(el: Element): String {
        val copy = el.clone()
        copy.select("script, style, .kbcontent1").remove()
        val html = copy.html()
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</div\\s*>", RegexOption.IGNORE_CASE), "\n")
        return Jsoup.parse(html).wholeText()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * 同一格子常有多段周次（如周三单片机：1,3,5 教室 / 7,9,11… 实验室 / 18 实验室）。
     * 强智用 ----- 分隔；若先按空行切成独立段，后段会丢课名 → parse 失败 → 第 7 周缺实验。
     * 只去掉分隔线，整格交给 splitByCourseHeaders，用 lastName 跨段补课名。
     */
    private fun splitBlocks(text: String): List<String> {
        val normalized = text
            .replace(Regex("""\n\s*-{3,}\s*\n"""), "\n")
            .replace(Regex("""\n\s*={3,}\s*\n"""), "\n")
            .replace(Regex("""^\s*-{3,}\s*$""", RegexOption.MULTILINE), "")
        return splitByCourseHeaders(normalized)
    }

    /**
     * 同格多段（周三单片机：1,3,5 教室 → 7,9,11… 实验室 → 18 实验室）：
     * - 再出现课名 → 新段
     * - 已有周次后再出现周次行（中间不重复课名）→ 新段，并带上一段课名
     */
    private fun splitByCourseHeaders(text: String): List<String> {
        // 丢弃「通知单编号」等噪声，否则会被当成新课名头，铺满整周课表
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !isNoiseLine(it) }
        if (lines.isEmpty()) return emptyList()
        val blocks = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        var curHasWeek = false
        var lastName: String? = null
        for (line in lines) {
            val isWeek = isWeekLine(line)
            val header = isBlockHeader(line)
            if (cur.isNotEmpty() && curHasWeek && (header || isWeek)) {
                blocks += cur
                cur = mutableListOf()
                curHasWeek = false
                // 新段以周次开头时补课名，否则 parseBlock 会失败 / 误把「实验室」当课名
                if (isWeek && !header && lastName != null) {
                    cur += lastName!!
                }
            }
            if (header) lastName = line
            cur += line
            if (isWeek) curHasWeek = true
        }
        if (cur.isNotEmpty()) blocks += cur
        return blocks.map { it.joinToString("\n") }.filter { it.length > 2 }
    }

    /** 教务格子尾部常见垃圾行，绝不能当课名/新段头 */
    private fun isNoiseLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return true
        if (s.startsWith("通知单")) return true
        if (s.startsWith("备注")) return true
        if (labelNoise.containsMatchIn(s) && !s.contains("周")) return true
        return false
    }

    private fun isWeekLine(line: String): Boolean =
        line.contains("周") && (line.contains("节") || line.contains("[") || line.contains("(周)") || line.contains("（周）"))

    /** 新段课名头：排除地点/教师行，避免「实验室」把段拆碎 */
    private fun isBlockHeader(line: String): Boolean {
        if (!isCourseName(line)) return false
        if (line.contains("实验室") || line.contains("机房") || line.contains("健身房") || line.contains("教学楼")) {
            return false
        }
        if (Regex("""^[A-Za-z]\s*-?\s*\d""").containsMatchIn(line)) return false
        // 教师：短、含顿号逗号且无「课」字
        if (line.length <= 12 && Regex("""[,，、]""").containsMatchIn(line) && !line.contains("课")) {
            return false
        }
        return true
    }

    private fun parseBlock(
        visible: String,
        title: String,
        weekday: Int,
        fallbackSec: Pair<Int, Int>,
    ): Course? {
        if (weekday !in 1..7) return null
        val lines = visible.lines().map { it.trim() }.filter { it.isNotEmpty() && !isNoiseLine(it) }
        val meta = parseTooltip(title)

        val name = meta.name.ifBlank {
            lines.firstOrNull { isCourseName(it) }
                ?.replace(Regex("""^\.+|\.+$"""), "")
                ?.trim()
                .orEmpty()
        }
        if (!isCourseName(name)) return null

        // 无周次行的块（常见于「通知单编号」被误拆成段）直接丢弃
        val weekLine = lines.firstOrNull { isWeekLine(it) }
            ?: meta.weekLine.takeIf { isWeekLine(it) }
            ?: return null

        // 周次行节次优先；若弹层/行跨度更大（上午四节 1-4），取并集
        val fromWeek = sectionsFromWeekLine(weekLine)
        val fromTooltip = sectionsFromWeekLine(meta.weekLine)
        val sec = mergeSections(fromWeek, fromTooltip, fallbackSec)
        val (start, end) = SectionTimes.range(sec.first, sec.second)

        val place = extractPlace(lines, title, meta.place)
        val teacher = meta.teacher.ifBlank {
            lines.firstOrNull { line ->
                line != name && line != place && line != weekLine && isTeacherLine(line)
            }.orEmpty()
        }

        val weeks = weekLine
        val id = sha1("$name|$weekday|${sec.first}-${sec.second}|$weeks|$place")
        return Course(
            id = id,
            name = name,
            teacher = teacher,
            place = place,
            weeks = weeks,
            weekday = weekday,
            startSection = sec.first,
            endSection = sec.second,
            startTime = start,
            endTime = end,
        )
    }

    private data class TooltipMeta(
        val name: String = "",
        val place: String = "",
        val teacher: String = "",
        val weekLine: String = "",
    )

    /** 解析教务悬停弹层：课程名称 / 上课地点 / 上课时间 */
    private fun parseTooltip(title: String): TooltipMeta {
        if (title.isBlank()) return TooltipMeta()
        fun field(key: String): String {
            val m = Regex("""$key\s*[：:]\s*(.+)""").find(title)
            return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .substringBefore("\n")
                .trim()
        }
        val nameRaw = field("课程名称")
        // 去掉合班说明：概率论A(25级…) → 概率论A 仍保留括号内班信息也可；取到换行即可
        val name = nameRaw
            .replace(Regex("""^\.+|\.+$"""), "")
            .trim()
        val place = field("上课地点")
        val time = field("上课时间") // 第1周 星期一 [03-04-05节]
        val teacher = field("教师").ifBlank { field("授课教师") }
        return TooltipMeta(
            name = if (name.isNotBlank() && !name.startsWith("备注")) name else "",
            place = place,
            teacher = teacher,
            weekLine = time,
        )
    }

    private fun extractPlace(lines: List<String>, title: String, tooltipPlace: String): String {
        val roomRe = Regex("""([A-Za-z])\s*-?\s*(\d{1,2})\s*-\s*(\d{2,4})""")

        // 1) 本段可见文本优先（多段周次时弹层常只带第一段教室）
        for (line in lines) {
            roomRe.find(line)?.let {
                return cleanPlace("${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}")
            }
        }
        lines.firstOrNull {
            (it.contains("实验室") || it.contains("机房") || it.contains("健身房")) &&
                !it.startsWith("备注")
        }?.let { return cleanPlace(it) }

        // 2) 弹层「上课地点：C-5-353」
        if (tooltipPlace.isNotBlank()) return cleanPlace(tooltipPlace)
        Regex("""上课地点\s*[：:]\s*([^\s\n]+)""")
            .find(title)
            ?.groupValues?.getOrNull(1)
            ?.let { return cleanPlace(it) }

        roomRe.find(title)?.let {
            return cleanPlace("${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}")
        }

        // 3) 【C-1…】楼名兜底
        lines.firstOrNull { it.startsWith("【") || it.contains("教学楼") || it.contains("楼") }
            ?.let { return cleanPlace(it) }

        return ""
    }

    private fun cleanPlace(raw: String): String =
        raw.trim()
            .removePrefix("【").removeSuffix("】")
            .replace(Regex("""^上课地点\s*[：:]"""), "")
            .trim()

    private fun isCourseName(line: String): Boolean {
        val s = line.trim()
        if (s.length < 2) return false
        if (labelNoise.containsMatchIn(s)) return false
        if (s.startsWith("通知单") || s.contains("通知单编号")) return false
        // 课表格子常把班级放在课程名前面；这不是课程名称。
        if (Regex("""^(班级|行政班|教学班)\s*[：:]""").containsMatchIn(s)) return false
        if (s == "备注" || s.startsWith("备注")) return false
        // 地点词不能当课名（多段周次拆段后常只剩「实验室」）
        if (s == "实验室" || s == "机房" || s == "健身房" || s == "教室") return false
        if (s.startsWith("课堂讲授") || s.contains("课内实践")) return false
        if (s.startsWith("(") || s.startsWith("（")) return false
        if (s.startsWith("【")) return false // 地点楼宇
        if (s.startsWith("第") && s.contains("节")) return false
        if (Regex("""^\d+([-~,，、]\d+)*\s*[（(]?周""").containsMatchIn(s)) return false
        if (s.contains("节") && Regex("""\[\d{2}""").containsMatchIn(s)) return false
        if (Regex("""\(周\)|（周）""").containsMatchIn(s) && s.any { it.isDigit() }) return false
        if (Regex("""^[A-Za-z]-?\d{1,2}-\d{2,4}$""").matches(s.replace(" ", ""))) return false
        if (!Regex("""[\u4e00-\u9fff]""").containsMatchIn(s)) return false
        return true
    }

    private fun isTeacherLine(line: String): Boolean {
        if (line.length !in 2..24) return false
        if (labelNoise.containsMatchIn(line)) return false
        if (line.startsWith("备注") || line.startsWith("【") || line.startsWith("(")) return false
        if (line.contains("节") || line.contains("周")) return false
        if (line.contains("楼") || line.contains("实验室") || line.contains("机房") || line.contains("馆")) return false
        if (Regex("""^\d""").containsMatchIn(line)) return false
        if (line.contains("班") && line.length > 10) return false
        if (Regex("""课堂讲授|课内实践""").containsMatchIn(line)) return false
        return Regex("""[\u4e00-\u9fff]""").containsMatchIn(line)
    }

    private fun sectionsFromWeekLine(line: String?): Pair<Int, Int>? {
        if (line.isNullOrBlank()) return null
        // [03-04-05节] / [01-02-03-04节] 上午四节
        val m = Pattern.compile("""\[((?:\d{2}-?)+)节\]""").matcher(line)
        if (m.find()) {
            val g = m.group(1) ?: return null
            val parts = g.split("-").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
            if (parts.isNotEmpty()) return parts.first() to parts.last()
        }
        // 第1-4节 / 第01-04节
        val cn = Regex("""第\s*(\d+)\s*[-~—～]\s*(\d+)\s*节""").find(line)
        if (cn != null) return cn.groupValues[1].toInt() to cn.groupValues[2].toInt()
        return null
    }

    private fun mergeSections(vararg opts: Pair<Int, Int>?): Pair<Int, Int> {
        val present = opts.filterNotNull()
        if (present.isEmpty()) return 1 to 2
        val start = present.minOf { it.first }
        val end = present.maxOf { it.second }
        return start to end
    }

    private fun parseSectionsFromTimeCell(text: String): Pair<Int, Int> {
        val paren = Regex("""\((\d{2}(?:,\d{2})*)\)""").find(text)
        if (paren != null) {
            val secs = paren.groupValues[1].split(",").mapNotNull { it.toIntOrNull() }
            if (secs.isNotEmpty()) return secs.first() to secs.last()
        }
        // 第1-2节 / 第01-02节（旧正则把「1-2」误判成第 1 节）
        val range = Regex("""第\s*(\d+)\s*[-~—～]\s*(\d+)\s*节""").find(text)
        if (range != null) {
            return range.groupValues[1].toInt() to range.groupValues[2].toInt()
        }
        // 部分教务页面省略“第”，例如「1-4节」或「01-04节」。
        // 不能回退到 1-2，否则上午四节课会被截断，甚至因跨行布局而漏掉。
        val bareRange = Regex("""(?<![\d-])(\d{1,2})\s*[-~—～]\s*(\d{1,2})\s*节""").find(text)
        if (bareRange != null) {
            return bareRange.groupValues[1].toInt() to bareRange.groupValues[2].toInt()
        }
        val bracket = Pattern.compile("""\[((?:\d{2}-?)+)节\]""").matcher(text)
        if (bracket.find()) {
            val g = bracket.group(1) ?: ""
            val parts = g.split("-").mapNotNull { it.toIntOrNull() }.filter { it > 0 }
            if (parts.isNotEmpty()) return parts.first() to parts.last()
        }
        val cn = Regex("""第\s*(\d+)\s*节""").find(text)
        if (cn != null) {
            val a = cn.groupValues[1].toInt()
            return a to a
        }
        val bare = Regex("""(?<![\d-])(\d{1,2})\s*节""").find(text)
        if (bare != null) {
            val a = bare.groupValues[1].toInt()
            return a to a
        }
        return 1 to 2
    }

    private fun guessWeekdayFromElement(el: Element): Int {
        var cur: Element? = el
        repeat(6) {
            val node = cur ?: return 0
            dayNames.forEachIndexed { idx, name ->
                if (idx > 0 && node.outerHtml().take(400).contains(name)) return idx
            }
            if (node.tagName() == "td") {
                val row = node.parent()
                if (row != null) {
                    val idx = row.select("> td, > th").indexOf(node)
                    if (idx >= 1) return idx.coerceIn(1, 7)
                }
            }
            cur = node.parent()
        }
        return 0
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
