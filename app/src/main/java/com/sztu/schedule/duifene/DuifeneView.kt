package com.sztu.schedule.duifene

import org.json.JSONArray
import org.json.JSONObject

data class DuifeneRecord(
    val eventId: Long,
    val atMs: Long,
    val phase: String,
    val summary: String,
    val courseLabel: String,
)

data class DuifeneView(
    val loggedIn: Boolean = false,
    val accountMasked: String = "",
    val userName: String = "",
    val session: String = "none",
    val storageWarning: Boolean = false,
    val coursesPhase: String = "idle",
    val coursesError: String = "",
    val courses: List<Course> = emptyList(),
    val selectedKey: String = "",
    val watching: Boolean = false,
    val watchPhase: String = "idle",
    val activityPhase: String = "none",
    val activitySummary: String = "未发现活动",
    val activityType: String = "",
    val remainSeconds: Long? = null,
    val listenRemainSeconds: Long? = null,
    val activityEndsAt: Long? = null,
    val listenEndsAt: Long? = null,
    val willRetry: Boolean = false,
    val sessionEpoch: Int = 0,
    val lastQueryAt: Long = 0L,
    val canStart: Boolean = false,
    val needsWebPassword: Boolean = false,
    val locationPermission: String = "unknown",
    val records: List<DuifeneRecord> = emptyList(),
    val message: String = "",
    val settings: DuifeneSettings = DuifeneSettings(),
)

fun maskAccount(account: String): String {
    val text = account.trim()
    if (text.length >= 7) return text.take(3) + "****" + text.takeLast(4)
    if (text.isNotEmpty()) return text.take(1) + "***"
    return ""
}

fun DuifeneView.toJson(): JSONObject {
    val root = JSONObject()
    root.put("loggedIn", loggedIn)
    root.put("accountMasked", accountMasked)
    root.put("userName", userName)
    root.put("session", session)
    root.put("storageWarning", storageWarning)
    root.put("coursesPhase", coursesPhase)
    root.put("coursesError", coursesError)
    val courseArray = JSONArray()
    for (course in courses) {
        courseArray.put(
            JSONObject()
                .put("courseId", course.courseId)
                .put("classId", course.classId)
                .put("name", course.name)
                .put("key", "${course.courseId}|${course.classId}")
        )
    }
    root.put("courses", courseArray)
    root.put("selectedKey", selectedKey)
    root.put("watching", watching)
    root.put("watchPhase", watchPhase)
    root.put("activityPhase", activityPhase)
    root.put("activitySummary", activitySummary)
    root.put("activityType", activityType)
    if (remainSeconds == null) root.put("remainSeconds", JSONObject.NULL) else root.put("remainSeconds", remainSeconds)
    if (listenRemainSeconds == null) root.put("listenRemainSeconds", JSONObject.NULL) else root.put("listenRemainSeconds", listenRemainSeconds)
    if (activityEndsAt == null) root.put("activityEndsAt", JSONObject.NULL) else root.put("activityEndsAt", activityEndsAt)
    if (listenEndsAt == null) root.put("listenEndsAt", JSONObject.NULL) else root.put("listenEndsAt", listenEndsAt)
    root.put("willRetry", willRetry)
    root.put("sessionEpoch", sessionEpoch)
    root.put("lastQueryAt", lastQueryAt)
    root.put("canStart", canStart)
    root.put("needsWebPassword", needsWebPassword)
    root.put("locationPermission", locationPermission)
    val recordArray = JSONArray()
    for (record in records.takeLast(DuifenePolicy.RECORD_LIMIT)) {
        recordArray.put(
            JSONObject()
                .put("eventId", record.eventId)
                .put("at", record.atMs)
                .put("phase", record.phase)
                .put("summary", record.summary)
                .put("course", record.courseLabel)
        )
    }
    root.put("records", recordArray)
    root.put("message", message)
    root.put(
        "settings",
        JSONObject()
            .put("monitorStartHour", settings.monitorStartHour)
            .put("monitorEndHour", settings.monitorEndHour)
            .put("pollMinS", settings.pollMinS)
            .put("pollMaxS", settings.pollMaxS)
            .put("numericPollS", DuifenePolicy.NUMERIC_POLL_S)
            .put("numericBackoff", "0.4/0.8/1.6/2")
            .put("numericPriorityS", DuifenePolicy.NUMERIC_PRIORITY_WINDOW_S)
            .put("smallClassMax", settings.smallClassMax)
            .put("smallClassDelayS", settings.smallClassDelayS)
            .put("headcountRatio", settings.headcountRatio)
            .put("staleSignMinutes", settings.staleSignMinutes)
            .put("cooldownSeconds", settings.cooldownSeconds)
            .put("maxListenMinutes", DuifenePolicy.MAX_LISTEN_MINUTES)
            .put("sourceCommit", DuifenePolicy.SOURCE_COMMIT)
    )
    return root
}

fun typeLabel(checkType: String): String = when (checkType) {
    "1" -> "数字码"
    "2" -> "二维码"
    "3" -> "定位"
    "4" -> "汉字"
    "" -> ""
    else -> "未知类型"
}
