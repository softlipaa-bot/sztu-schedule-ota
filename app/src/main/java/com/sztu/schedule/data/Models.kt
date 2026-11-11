package com.sztu.schedule.data

import org.json.JSONArray
import org.json.JSONObject

data class Course(
    val id: String,
    val name: String,
    val teacher: String,
    val place: String,
    val weeks: String,
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val startTime: String,
    val endTime: String,
) {
    val timeLabel: String get() = "$startTime–$endTime"
    val sectionLabel: String
        get() = if (startSection == endSection) "第${startSection}节"
        else "第${startSection}-${endSection}节"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("teacher", teacher)
        .put("place", place)
        .put("weeks", weeks)
        .put("weekday", weekday)
        .put("startSection", startSection)
        .put("endSection", endSection)
        .put("startTime", startTime)
        .put("endTime", endTime)

    companion object {
        fun fromJson(o: JSONObject): Course = Course(
            id = o.optString("id"),
            name = o.optString("name"),
            teacher = o.optString("teacher"),
            place = o.optString("place"),
            weeks = o.optString("weeks"),
            weekday = o.optInt("weekday"),
            startSection = o.optInt("startSection"),
            endSection = o.optInt("endSection"),
            startTime = o.optString("startTime"),
            endTime = o.optString("endTime"),
        )
    }
}

data class ScheduleSnapshot(
    val courses: List<Course>,
    val term: String,
    val studentName: String,
    val className: String,
    val syncedAt: Long,
    val teachingWeek: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("term", term)
        .put("studentName", studentName)
        .put("className", className)
        .put("syncedAt", syncedAt)
        .put("teachingWeek", teachingWeek)
        .put("courses", JSONArray().also { arr ->
            courses.forEach { arr.put(it.toJson()) }
        })

    companion object {
        fun empty() = ScheduleSnapshot(emptyList(), "", "", "", 0L, 0)

        fun fromJson(raw: String): ScheduleSnapshot {
            if (raw.isBlank()) return empty()
            val o = JSONObject(raw)
            val arr = o.optJSONArray("courses") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    add(Course.fromJson(arr.getJSONObject(i)))
                }
            }
            return ScheduleSnapshot(
                courses = list,
                term = o.optString("term"),
                studentName = o.optString("studentName"),
                className = o.optString("className"),
                syncedAt = o.optLong("syncedAt"),
                teachingWeek = o.optInt("teachingWeek"),
            )
        }
    }
}

/** 深技大节次时间（与教务一致） */
object SectionTimes {
    private val ranges = mapOf(
        1 to ("08:30" to "09:10"),
        2 to ("09:15" to "09:55"),
        3 to ("10:15" to "10:55"),
        4 to ("11:00" to "11:40"),
        5 to ("11:45" to "12:25"),
        6 to ("14:00" to "14:45"),
        7 to ("15:15" to "15:25"),
        8 to ("15:45" to "16:30"),
        9 to ("17:00" to "17:10"),
        10 to ("17:15" to "17:55"),
        11 to ("19:00" to "19:40"),
        12 to ("20:10" to "20:20"),
        13 to ("20:30" to "21:10"),
        14 to ("21:40" to "21:50"),
        15 to ("18:00" to "18:40"),
    )

    private val blockRanges = mapOf(
        "1-2" to ("08:30" to "09:55"),
        "3-4" to ("10:15" to "11:40"),
        "5" to ("11:45" to "12:25"),
        "6-7" to ("14:00" to "15:25"),
        "8-9" to ("15:45" to "17:10"),
        "10" to ("17:15" to "17:55"),
        "11-12" to ("19:00" to "20:20"),
        "13-14" to ("20:30" to "21:50"),
        "15" to ("18:00" to "18:40"),
        "3-5" to ("10:15" to "12:25"),
        "1-3" to ("08:30" to "11:00"),
        "1-4" to ("08:30" to "11:40"),
        "6-9" to ("14:00" to "17:10"),
        "11-14" to ("19:00" to "21:50"),
    )

    fun range(start: Int, end: Int): Pair<String, String> {
        val key = if (start == end) "$start" else "$start-$end"
        blockRanges[key]?.let { return it }
        val s = ranges[start]?.first ?: "08:00"
        val e = ranges[end]?.second ?: ranges[start]?.second ?: "22:00"
        return s to e
    }

    /** 小组件闹钟：所有单节/连堂起止点（含 1-3 节 11:00 等，避免周三早上卡住） */
    fun allBoundaryHm(): List<Pair<Int, Int>> {
        val set = linkedSetOf<Pair<Int, Int>>()
        fun addHm(hm: String) {
            val p = hm.split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: return
            val m = p.getOrNull(1)?.toIntOrNull() ?: return
            set += h to m
        }
        ranges.values.forEach { (a, b) ->
            addHm(a)
            addHm(b)
        }
        blockRanges.values.forEach { (a, b) ->
            addHm(a)
            addHm(b)
        }
        set += 0 to 1 // 跨日切「今日」
        return set.sortedWith(compareBy({ it.first }, { it.second }))
    }
}
