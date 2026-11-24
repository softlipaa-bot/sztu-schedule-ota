package com.sztu.schedule.duifene

/**
 * 显示记录和“这一活动已经记过这个状态”分开。
 * 清空只删显示，相同状态不会因为下一轮查询再出现；状态变化仍会记下来。
 */
class RecordBook {
    private val shown = ArrayDeque<DuifeneRecord>()
    private val phaseByActivity = mutableMapOf<String, String>()
    private var nextId = 1L

    fun note(
        account: String,
        classId: String,
        activityId: String,
        phase: String,
        summary: String,
        courseLabel: String,
        atMs: Long,
    ) {
        if (phase == "none" || phase == "submitting" || activityId.isBlank()) return
        val key = "$account|$classId|$activityId"
        if (phaseByActivity[key] == phase) return
        phaseByActivity[key] = phase
        shown.addLast(
            DuifeneRecord(
                eventId = nextId++,
                atMs = atMs,
                phase = phase,
                summary = summary,
                courseLabel = courseLabel,
            )
        )
        while (shown.size > DuifenePolicy.RECORD_LIMIT) shown.removeFirst()
    }

    fun clear() {
        shown.clear()
    }

    fun reset() {
        shown.clear()
        phaseByActivity.clear()
    }

    fun snapshot(): List<DuifeneRecord> = shown.toList()
}
