package com.sztu.schedule.duifene

import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

interface DfClock {
    fun wallMs(): Long
    fun monoMs(): Long
    fun shanghaiNow(): ZonedDateTime
}

object SystemDfClock : DfClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = System.nanoTime() / 1_000_000L
    override fun shanghaiNow(): ZonedDateTime = ZonedDateTime.now(SHANGHAI)
}

class ManualClock(
    var monoMs: Long = 0L,
    var wallMs: Long = 1_700_000_000_000L,
) : DfClock {
    override fun wallMs(): Long = wallMs
    override fun monoMs(): Long = monoMs
    override fun shanghaiNow(): ZonedDateTime = java.time.Instant.ofEpochMilli(wallMs).atZone(SHANGHAI)

    fun advanceMono(ms: Long) {
        monoMs += ms
    }
}

fun parseCheckinTime(value: String): ZonedDateTime {
    val text = value.trim()
    if (text.isEmpty()) throw DateTimeParseException("empty", value, 0)
    try {
        return OffsetDateTime.parse(text).atZoneSameInstant(SHANGHAI)
    } catch (_: DateTimeParseException) {
    }
    try {
        return LocalDateTime.parse(text).atZone(SHANGHAI)
    } catch (_: DateTimeParseException) {
    }
    val slash = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    return LocalDateTime.parse(text, slash).atZone(SHANGHAI)
}

data class Course(
    val courseId: String,
    val classId: String,
    val name: String,
)

data class AppIdentity(
    val account: String,
    val userId: String,
    val userName: String,
    val userTypeId: String,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

enum class Outcome {
    SIGNED,
    SKIPPED,
    DEFERRED,
    PENDING,
    DISCARDED,
}

data class AppCheckIn(
    val activityId: String,
    val checkType: String,
    val code: String,
    val qrToken: String,
    val roomId: String,
    val classId: String,
    val createDate: String,
    val validDate: String,
    val checkInStatus: String,
    val statusId: String,
    val statusName: String,
    val studentId: String = "",
    val isDeleted: Boolean = false,
) {
    val hasValidTimes: Boolean
        get() = try {
            !parseCheckinTime(validDate).isBefore(parseCheckinTime(createDate))
        } catch (_: DateTimeParseException) {
            false
        }

    val isAttended: Boolean
        get() = checkInStatus == "1" || statusId == "1" || statusName == "出勤"

    fun hasEnded(now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): Boolean {
        val validUntil = try {
            parseCheckinTime(validDate)
        } catch (_: DateTimeParseException) {
            return false
        }
        return now.isAfter(validUntil)
    }

    fun ageSeconds(now: ZonedDateTime = ZonedDateTime.now(SHANGHAI)): Double? {
        val created = try {
            parseCheckinTime(createDate)
        } catch (_: DateTimeParseException) {
            return null
        }
        return Duration.between(created, now).toMillis() / 1000.0
    }

    fun validCode(): Boolean = code.length == 4 && code.all { it in '0'..'9' }

    companion object {
        fun fromPayload(payload: JSONObject): AppCheckIn {
            val nested = payload.optJSONObject("checkIn")
            val checkIn = nested ?: payload
            val activityId = jsonText(checkIn, "id")
            if (activityId.isEmpty() || activityId == "0") {
                throw IllegalArgumentException("当前没有进行中的签到")
            }
            return AppCheckIn(
                activityId = activityId,
                checkType = jsonText(checkIn, "checkInType"),
                code = jsonText(checkIn, "checkInCode").trim(),
                qrToken = jsonText(payload, "qrCode").trim(),
                roomId = jsonText(checkIn, "roomID"),
                classId = jsonText(checkIn, "tClassID"),
                createDate = jsonText(checkIn, "createrDate"),
                validDate = jsonText(checkIn, "validDate"),
                checkInStatus = jsonText(checkIn, "checkInStatus"),
                statusId = jsonText(checkIn, "statusID"),
                statusName = jsonText(checkIn, "statusName"),
                studentId = jsonText(checkIn, "studentID"),
                isDeleted = deletedFlag(checkIn.opt("isDel")),
            )
        }
    }
}

fun jsonText(obj: JSONObject, key: String): String {
    if (!obj.has(key) || obj.isNull(key)) return ""
    val value = obj.get(key)
    return when (value) {
        is String -> value
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble % 1.0 == 0.0) asDouble.toLong().toString() else value.toString()
        }
        is Boolean -> value.toString()
        else -> value.toString()
    }
}

fun jsonCode(obj: JSONObject): String = jsonText(obj, "code")

fun jsonSuccess(obj: JSONObject): Boolean = when (val flag = if (obj.has("success")) obj.opt("success") else null) {
    true -> true
    is Number -> flag.toDouble() != 0.0
    is String -> flag.equals("true", ignoreCase = true) || flag == "1"
    else -> false
}

fun deletedFlag(value: Any?): Boolean {
    if (value == null || value == JSONObject.NULL) return false
    val text = when (value) {
        is Boolean -> if (value) "true" else "false"
        is Number -> {
            val asDouble = value.toDouble()
            if (asDouble % 1.0 == 0.0) asDouble.toLong().toString() else value.toString()
        }
        else -> value.toString()
    }.lowercase()
    return text == "1" || text == "true"
}

open class AuthError(message: String) : Exception(message)

open class AppApiError(message: String) : Exception(message)

class PermissionDenied(message: String) : AppApiError(message)

class WebSessionError(message: String) : AppApiError(message)

class RateLimited(message: String) : Exception(message)

class UnconfirmedSubmit(message: String) : AppApiError(message)

class TransportException(
    message: String,
    val uncertain: Boolean,
    val sent: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
