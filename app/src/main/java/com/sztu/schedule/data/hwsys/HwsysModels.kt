package com.sztu.schedule.data.hwsys

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * 教学辅助系统（hwsys）只读契约。
 *
 * 2026-11-09 对照线上页面 https://acm.sztu.edu.cn:40130/hwsys/home
 * 及其前端资源（MyAttendance、term_util、auth_session、util）核对：
 * - GET /api/signin_my_attendance?query={"param":{"date_start","date_end"}}
 * - 未登录实响应：{"status_code":401,"detail":"请先登录","data":null}
 * - 官方状态：0 未签、10 已签、20 请假、30 免听、40 迟到。页面没有「缺勤」这个词，
 *   统计桶名叫 absent，对应的就是 0「未签」。
 * - 官网出勤页按日期查询，学生端界面最多回溯约半年；没有学期起止接口。
 * - term_util 里的 9/1、2/1、7/15 只是前端猜当前学期用的日期，不能当成校历。
 */
object HwsysUrls {
    const val API_ORIGIN = "https://acm.sztu.edu.cn:40130"
    const val API_BASE = "$API_ORIGIN/api"
    const val ATTENDANCE = "$API_BASE/signin_my_attendance"
    const val SSO_CALLBACK = "$API_BASE/sso_callback"
    const val TOKEN = "$API_BASE/token"
    const val SSO_START = "https://acm.sztu.edu.cn/sso/ssologin?sclient_id=hwsys"
    const val HOME = "https://acm.sztu.edu.cn:40130/hwsys/home"

    /** 单段日期不超过官网学生端「近半年」窗口，避免一次请求被服务端截断。 */
    const val MAX_SEGMENT_DAYS = 180
    const val MAX_PAGES = 50
}

object OfficialSigninStatus {
    const val UNSIGNED = 0
    const val SIGNED = 10
    const val LEAVE = 20
    const val EXEMPT = 30
    const val LATE = 40

    fun label(code: Int): String? = when (code) {
        UNSIGNED -> "未签"
        SIGNED -> "已签"
        LEAVE -> "请假"
        EXEMPT -> "免听"
        LATE -> "迟到"
        else -> null
    }
}

val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")

private val jwtLike = Regex("""[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
private val termExact = Regex("""^(\d{4})-(\d{4})-([1-3])$""")
private val dayExact = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
private val offsetTail = Regex("""[+-]\d{2}:?\d{2}$""")

fun redact(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return jwtLike.replace(raw.trim(), "[redacted]").take(180)
}

data class TermId(val y0: Int, val y1: Int, val sem: Int) {
    val text: String = "$y0-$y1-$sem"
    val index: Int = y0 * 3 + sem
}

fun parseTerm(raw: String?): TermId? {
    val m = termExact.matchEntire(raw?.trim().orEmpty()) ?: return null
    val y0 = m.groupValues[1].toInt()
    val y1 = m.groupValues[2].toInt()
    val sem = m.groupValues[3].toInt()
    if (y1 != y0 + 1) return null
    return TermId(y0, y1, sem)
}

fun termFromIndex(index: Int): TermId? {
    if (index < 3) return null
    var year = index / 3
    var sem = index % 3
    if (sem == 0) {
        sem = 3
        year -= 1
    }
    if (year < 2000 || sem !in 1..3) return null
    return TermId(year, year + 1, sem)
}

/** 当前学期，以及官网 term_util 同样的前后相邻编号。只生成编号，不生成日期。 */
fun termChoices(scheduleTerm: String?, extras: Collection<String> = emptyList()): List<TermId> {
    val ids = LinkedHashSet<String>()
    val base = parseTerm(scheduleTerm)
    if (base != null) {
        for (delta in -4..1) {
            termFromIndex(base.index + delta)?.let { ids.add(it.text) }
        }
    }
    extras.forEach { parseTerm(it)?.let { term -> ids.add(term.text) } }
    return ids.mapNotNull { parseTerm(it) }.sortedByDescending { it.index }
}

data class DateRange(val start: LocalDate, val end: LocalDate)

/**
 * 官网 term_util 的日期估计。调用方必须标成待确认，不能当成教务校历。
 * 秋季 9/1–1/31，春季 2/1–7/14，夏季 7/15–8/31。
 */
fun heuristicRange(term: TermId): DateRange = when (term.sem) {
    1 -> DateRange(LocalDate.of(term.y0, 9, 1), LocalDate.of(term.y1, 1, 31))
    2 -> DateRange(LocalDate.of(term.y1, 2, 1), LocalDate.of(term.y1, 7, 14))
    else -> DateRange(LocalDate.of(term.y1, 7, 15), LocalDate.of(term.y1, 8, 31))
}

fun parseDay(raw: String?): LocalDate? {
    val m = dayExact.matchEntire(raw?.trim().orEmpty()) ?: return null
    return try {
        LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    } catch (_: Exception) {
        null
    }
}

/** 含首尾的连续日期段，相邻段不重叠、也不留空日。 */
fun segmentRange(start: LocalDate, end: LocalDate, maxDays: Int = HwsysUrls.MAX_SEGMENT_DAYS): List<DateRange> {
    if (end.isBefore(start)) return emptyList()
    val span = maxDays.coerceAtLeast(1)
    val out = ArrayList<DateRange>()
    var cursor = start
    while (!cursor.isAfter(end)) {
        val chunkEnd = minOf(end, cursor.plusDays((span - 1).toLong()))
        out += DateRange(cursor, chunkEnd)
        cursor = chunkEnd.plusDays(1)
    }
    return out
}

fun attendanceQueryJson(start: LocalDate, end: LocalDate, page: Int?): String {
    val param = JSONObject()
        .put("date_start", start.toString())
        .put("date_end", end.toString())
    val query = JSONObject().put("param", param)
    if (page != null && page > 1) {
        query.put("page", page)
        query.put("per_page", 200)
    }
    return query.toString()
}

fun allowedSchoolHost(scheme: String?, host: String?): Boolean {
    if (!scheme.equals("https", ignoreCase = true)) return false
    val name = host?.trim()?.lowercase().orEmpty()
    return name == "sztu.edu.cn" || name.endsWith(".sztu.edu.cn")
}

/** 教务页面不能当成教学辅助系统登录成功。 */
fun isJwxtPage(url: String?): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "jwxt.sztu.edu.cn"
}

/**
 * 只在 ACM 的 HTTPS 443 / 40130 上识别令牌。
 * 其它学校域名可以在登录过程中出现，但不能从那里取 ACM 会话。
 */
fun isAcmTokenCarrier(url: String?): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.host?.lowercase() != "acm.sztu.edu.cn") return false
    val port = uri.port
    return port == -1 || port == 443 || port == 40130
}

fun isHwsysAppUrl(url: String?): Boolean {
    if (!isAcmTokenCarrier(url)) return false
    val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
    return path.startsWith("/hwsys")
}

/** 只从跳转地址里取出 SSO 令牌。调用方不得把原地址写进日志。 */
fun extractSsoTokenPlain(url: String?): String? {
    val source = url?.trim().orEmpty()
    if (source.isEmpty()) return null
    val match = Regex("""(?:access_token_sso|access_token)=([^&#\s]+)""").find(source) ?: return null
    val value = try {
        java.net.URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        match.groupValues[1]
    }
    return value.takeIf { it.length > 12 && !it.contains(" ") }
}

fun attendanceUrl(start: LocalDate, end: LocalDate, page: Int? = null): String {
    val encoded = URLEncoder.encode(attendanceQueryJson(start, end, page), StandardCharsets.UTF_8.name())
    return HwsysUrls.ATTENDANCE + "?query=" + encoded
}

fun jwtExpiryEpochMs(token: String?): Long? {
    val parts = token?.split('.').orEmpty()
    if (parts.size != 3 || parts.any { it.isBlank() }) return null
    val payload = parts[1].replace('-', '+').replace('_', '/')
    val pad = (4 - payload.length % 4) % 4
    val bytes = try {
        Base64.getDecoder().decode(payload + "=".repeat(pad))
    } catch (_: IllegalArgumentException) {
        return null
    }
    val json = try {
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (_: Exception) {
        return null
    }
    val exp = json.optLong("exp", -1L)
    if (exp <= 0L) return null
    return exp * 1000L
}

data class RawAttendance(
    val stableId: String?,
    val title: String,
    val course: String,
    val taskTitle: String,
    val term: String,
    val timeStart: String,
    val timeEnd: String,
    val status: Int?,
    val statusPresent: Boolean,
)

data class Envelope(
    val statusCode: Int,
    val detail: String,
    val records: List<RawAttendance>,
    val total: Int?,
    val statsSum: Int?,
    val authRequired: Boolean,
    val shapeOk: Boolean,
)

fun parseEnvelope(httpStatus: Int, body: String?): Envelope {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) {
        val auth = httpStatus == 401 || httpStatus == 403
        return Envelope(
            statusCode = httpStatus,
            detail = if (auth) "请先登录" else "服务没有返回内容",
            records = emptyList(),
            total = null,
            statsSum = null,
            authRequired = auth,
            shapeOk = false,
        )
    }
    val obj = try {
        JSONObject(text)
    } catch (_: Exception) {
        return Envelope(httpStatus, "响应不是 JSON", emptyList(), null, null, httpStatus == 401 || httpStatus == 403, false)
    }
    val statusCode = if (obj.has("status_code")) obj.optInt("status_code") else httpStatus
    val auth = httpStatus == 401 || httpStatus == 403 || statusCode == 401 || statusCode == 403
    val data = obj.opt("data")
    val records = ArrayList<RawAttendance>()
    val shapeOk = when (data) {
        null, JSONObject.NULL -> statusCode == 200
        is JSONArray -> {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                records += parseRecord(item)
            }
            true
        }
        else -> false
    }
    return Envelope(
        statusCode = statusCode,
        detail = redact(detailText(obj.opt("detail"))),
        records = records,
        total = readTotal(obj),
        statsSum = readStatsSum(obj.optJSONObject("stats")),
        authRequired = auth,
        shapeOk = shapeOk && !auth,
    )
}

private fun detailText(value: Any?): String {
    return when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                if (isNotEmpty()) append("; ")
                append(value.optString(i))
            }
        }
        else -> value.toString()
    }
}

private fun readTotal(obj: JSONObject): Int? {
    val meta = obj.optJSONObject("meta")
    val candidates = listOf(
        obj.opt("total"),
        obj.opt("count"),
        meta?.opt("total"),
        meta?.opt("count"),
    )
    for (c in candidates) {
        when (c) {
            is Number -> return c.toInt()
            is String -> c.toIntOrNull()?.let { return it }
        }
    }
    return null
}

private fun readStatsSum(stats: JSONObject?): Int? {
    if (stats == null) return null
    val keys = listOf("signed", "late", "leave", "free", "absent")
    if (keys.none { stats.has(it) }) return null
    return keys.sumOf { stats.optInt(it, 0) }
}

fun parseRecord(obj: JSONObject): RawAttendance {
    val id = firstText(obj, "id", "signinstu_id", "signintask_id")
    val statusPresent = obj.has("signin_status") && !obj.isNull("signin_status")
    val status = if (statusPresent) obj.optInt("signin_status") else null
    return RawAttendance(
        stableId = id?.takeIf { it.isNotBlank() },
        title = obj.optString("signin_title"),
        course = obj.optString("course"),
        taskTitle = obj.optString("signintask_title"),
        term = obj.optString("term"),
        timeStart = obj.optString("time_start"),
        timeEnd = obj.optString("time_end"),
        status = status,
        statusPresent = statusPresent,
    )
}

private fun firstText(obj: JSONObject, vararg keys: String): String? {
    for (key in keys) {
        if (!obj.has(key) || obj.isNull(key)) continue
        val text = obj.optString(key).trim()
        if (text.isNotEmpty() && text != "null") return text
    }
    return null
}

fun parseInstant(raw: String?): Instant? {
    val original = raw?.trim().orEmpty()
    if (original.isEmpty() || original == "null") return null
    if (original.endsWith("Z") || offsetTail.containsMatchIn(original)) {
        return try {
            OffsetDateTime.parse(original.replace(' ', 'T')).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
    if (dayExact.matches(original)) return null
    val normalized = original.replace('T', ' ').substringBefore('.').trim()
    val patterns = arrayOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")
    for (pattern in patterns) {
        try {
            val dateTime = LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern(pattern))
            return dateTime.atZone(SHANGHAI).toInstant()
        } catch (_: DateTimeParseException) {
            continue
        }
    }
    return null
}

fun calendarDate(raw: String?): String {
    val match = Regex("""(\d{4}-\d{2}-\d{2})""").find(raw?.trim().orEmpty()) ?: return "—"
    return match.groupValues[1]
}

fun clockText(raw: String?): String {
    val instant = parseInstant(raw) ?: return ""
    return instant.atZone(SHANGHAI).format(DateTimeFormatter.ofPattern("HH:mm"))
}

data class AttendanceRow(
    val id: String,
    val title: String,
    val date: String,
    val time: String,
    val status: String,
    val kind: String,
    val detail: String,
    val sortAt: Long,
)

data class ClassifiedBatch(
    val rows: List<AttendanceRow>,
    val foreignDropped: Boolean,
    val identityIncomplete: Boolean,
    val duplicateIds: Int,
    val rawCount: Int,
)

fun classifyBatch(records: List<RawAttendance>, requestedTerm: String, now: Instant): ClassifiedBatch {
    var foreign = false
    var unstable = 0
    var dupes = 0
    val kept = LinkedHashMap<String, RawAttendance>()
    for (record in records) {
        val tagged = record.term.trim()
        if (tagged.isNotEmpty() && tagged != requestedTerm) {
            foreign = true
            continue
        }
        val id = record.stableId
        if (id == null) {
            unstable += 1
            kept["unstable-$unstable"] = record
        } else if (kept.containsKey(id)) {
            dupes += 1
        } else {
            kept[id] = record
        }
    }
    val rows = kept.map { (id, record) -> toRow(id, record, now) }
        .sortedWith(compareByDescending<AttendanceRow> { it.sortAt }.thenBy { it.id })
    return ClassifiedBatch(
        rows = rows,
        foreignDropped = foreign,
        identityIncomplete = unstable > 0,
        duplicateIds = dupes,
        rawCount = records.size,
    )
}

private fun toRow(id: String, record: RawAttendance, now: Instant): AttendanceRow {
    val official = if (record.statusPresent) OfficialSigninStatus.label(record.status ?: -1) else null
    val end = parseInstant(record.timeEnd)
    val kind = when {
        official == null -> "unknown"
        record.status != OfficialSigninStatus.UNSIGNED -> "settled"
        record.stableId == null -> "unknown"
        end == null -> "pending"
        end.isBefore(now) -> "confirmed_absence"
        else -> "pending"
    }
    val status = when (kind) {
        "unknown" -> "待核对"
        else -> official ?: "待核对"
    }
    val startClock = clockText(record.timeStart)
    val endClock = clockText(record.timeEnd)
    val time = when {
        startClock.isNotEmpty() && endClock.isNotEmpty() -> "$startClock–$endClock"
        startClock.isNotEmpty() -> startClock
        else -> "—"
    }
    val title = when {
        record.course.isNotBlank() -> record.course.trim()
        record.title.isNotBlank() -> record.title.trim()
        record.taskTitle.isNotBlank() -> record.taskTitle.trim()
        else -> "—"
    }
    val why = when (kind) {
        "confirmed_absence" -> "官方状态是「未签」，签到窗口已结束。"
        "pending" -> "官方状态是「未签」，签到窗口还没结束或没有结束时间，不算缺勤。"
        "unknown" -> "状态缺失、无法识别，或没有记录 ID，不算缺勤。"
        else -> "官方状态是「$status」。"
    }
    val detail = buildString {
        appendLine("课程：${textOrDash(record.course)}")
        appendLine("事项：${textOrDash(record.title)}")
        appendLine("任务：${textOrDash(record.taskTitle)}")
        appendLine("日期：${calendarDate(record.timeStart)}")
        appendLine("时间：$time")
        appendLine("学期标记：${textOrDash(record.term)}")
        append(why)
    }
    val sortAt = parseInstant(record.timeStart)?.toEpochMilli() ?: Long.MIN_VALUE
    return AttendanceRow(id, title, calendarDate(record.timeStart), time, status, kind, detail, sortAt)
}

private fun textOrDash(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() } ?: "—"

data class CacheBody(
    val accountHash: String,
    val termId: String,
    val dateStart: String,
    val dateEnd: String,
    val rangeConfirmed: Boolean,
    val rangeSource: String,
    val fetchedAt: Long,
    val queryComplete: Boolean,
    val foreignDropped: Boolean,
    val identityIncomplete: Boolean,
    val records: List<RawAttendance>,
)

data class BuiltView(
    val phase: String,
    val headline: String,
    val note: String,
    val confirmedAbsences: Int?,
    val pendingCount: Int?,
    val rangeStart: String,
    val rangeEnd: String,
    val rangeConfirmed: Boolean,
    val rangeSource: String,
    val fetchedAt: Long,
    val queryComplete: Boolean,
    val error: String,
    val listEmpty: String,
    val rows: List<AttendanceRow>,
)

fun buildView(
    loggedIn: Boolean,
    hasAccount: Boolean,
    termId: String?,
    cache: CacheBody?,
    error: String,
    errorAt: Long,
    now: Instant,
    isScheduleTerm: Boolean,
    displayStart: String,
    displayEnd: String,
    displayConfirmed: Boolean,
    displaySource: String,
): BuiltView {
    val termOk = parseTerm(termId) != null
    if (!termOk) {
        return emptyView(
            phase = "unmapped",
            headline = "学期未对应",
            note = "课表学期还不能对应到教学辅助系统的学期编号。",
            listEmpty = "学期编号尚未对应，还不能查询",
            error = error,
        )
    }
    val ownCache = cache?.takeIf { it.termId == termId && it.accountHash.isNotBlank() }
    if (!hasAccount && ownCache == null) {
        return emptyView(
            phase = "logged_out",
            headline = "未登录",
            note = "登录教学辅助系统后才能查询这一学期。",
            listEmpty = "登录后才能查询出勤",
            rangeStart = displayStart,
            rangeEnd = displayEnd,
            rangeConfirmed = displayConfirmed,
            rangeSource = displaySource,
        )
    }
    if (ownCache == null && error.isBlank()) {
        return emptyView(
            phase = "unsynced",
            headline = "未同步",
            note = rangeNote(displayStart, displayEnd, displayConfirmed, displaySource),
            listEmpty = "这一学期还没有同步",
            rangeStart = displayStart,
            rangeEnd = displayEnd,
            rangeConfirmed = displayConfirmed,
            rangeSource = displaySource,
        )
    }
    if (ownCache == null) {
        return emptyView(
            phase = "failed",
            headline = "获取失败",
            note = rangeNote(displayStart, displayEnd, displayConfirmed, displaySource),
            listEmpty = "获取失败",
            error = error,
            errorAt = errorAt,
            rangeStart = displayStart,
            rangeEnd = displayEnd,
            rangeConfirmed = displayConfirmed,
            rangeSource = displaySource,
        )
    }
    val batch = classifyBatch(ownCache.records, ownCache.termId, now)
    val confirmed = batch.rows.count { it.kind == "confirmed_absence" }
    val pending = batch.rows.count { it.kind == "pending" || it.kind == "unknown" }
    val queryComplete = ownCache.queryComplete && batch.duplicateIds == 0
    val claimNone = queryComplete && ownCache.rangeConfirmed && pending == 0 && confirmed == 0 &&
        !ownCache.foreignDropped && !batch.identityIncomplete && error.isBlank() && isScheduleTerm
    val headline = when {
        !queryComplete && confirmed > 0 -> "查询未完成，已看到 ${confirmed} 次缺勤"
        !queryComplete -> "查询未完成"
        confirmed > 0 -> "已确认缺勤 ${confirmed}次"
        claimNone -> "本学期暂无缺勤记录"
        !isScheduleTerm -> "该学期查询范围内没有已确认缺勤"
        else -> "没有已确认缺勤"
    }
    val notes = ArrayList<String>()
    notes += rangeNote(ownCache.dateStart, ownCache.dateEnd, ownCache.rangeConfirmed, ownCache.rangeSource)
    if (!ownCache.rangeConfirmed) notes += "学期日期范围待确认，不能把这次结果当成整学期结论。"
    if (!queryComplete) notes += "分页或分段还没完成，现在的数字不是学期总数。"
    if (ownCache.foreignDropped || batch.foreignDropped) notes += "有记录标了其他学期，没有计进来。"
    if (batch.identityIncomplete) notes += "有记录没有官方 ID，没有把它算成缺勤。"
    if (pending > 0) notes += "待确认 ${pending} 条。进行中、未结束或状态未知的，都不算缺勤。"
    if (!isScheduleTerm && queryComplete && confirmed == 0) notes += "不代表官方系统覆盖了全部课程。"
    if (error.isNotBlank()) notes += "获取失败（${formatShanghai(errorAt)}），下面仍是上次成功的结果。"
    if (ownCache.fetchedAt > 0L) notes += "最近成功同步 ${formatShanghai(ownCache.fetchedAt)}。"
    val listEmpty = when {
        !queryComplete -> "查询未完成"
        claimNone -> "本学期暂无缺勤记录"
        confirmed == 0 -> "没有已确认缺勤"
        else -> ""
    }
    val showCount = when {
        !queryComplete && confirmed == 0 -> null
        else -> confirmed
    }
    return BuiltView(
        phase = "ready",
        headline = headline,
        note = notes.filter { it.isNotBlank() }.joinToString(" "),
        confirmedAbsences = showCount,
        pendingCount = pending.takeIf { it > 0 },
        rangeStart = ownCache.dateStart,
        rangeEnd = ownCache.dateEnd,
        rangeConfirmed = ownCache.rangeConfirmed,
        rangeSource = ownCache.rangeSource,
        fetchedAt = ownCache.fetchedAt,
        queryComplete = queryComplete,
        error = error,
        listEmpty = listEmpty,
        rows = batch.rows,
    )
}

private fun rangeNote(start: String, end: String, confirmed: Boolean, source: String): String {
    if (start.isBlank() || end.isBlank()) return "查询日期还没有定。"
    val mark = if (confirmed) "已确认" else "待确认"
    val sourceNote = if (!confirmed && source == "hwsys_term_util_heuristic") {
        "，这是官网学期工具的日期估计，不是教务校历"
    } else {
        ""
    }
    return "查询 $start 至 $end（$mark$sourceNote）。"
}

private fun emptyView(
    phase: String,
    headline: String,
    note: String,
    listEmpty: String,
    error: String = "",
    errorAt: Long = 0L,
    rangeStart: String = "",
    rangeEnd: String = "",
    rangeConfirmed: Boolean = false,
    rangeSource: String = "",
): BuiltView {
    val extra = if (error.isBlank()) note else "$note ${if (errorAt > 0L) "（${formatShanghai(errorAt)}）" else ""} $error".trim()
    return BuiltView(
        phase = phase,
        headline = headline,
        note = extra,
        confirmedAbsences = null,
        pendingCount = null,
        rangeStart = rangeStart,
        rangeEnd = rangeEnd,
        rangeConfirmed = rangeConfirmed,
        rangeSource = rangeSource,
        fetchedAt = 0L,
        queryComplete = false,
        error = error,
        listEmpty = listEmpty,
        rows = emptyList(),
    )
}

fun formatShanghai(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return Instant.ofEpochMilli(epochMs).atZone(SHANGHAI).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}

fun viewToJson(view: BuiltView, includeRows: Boolean): JSONObject {
    val rows = JSONArray()
    if (includeRows) {
        view.rows.forEach { row ->
            rows.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("date", row.date)
                    .put("time", row.time)
                    .put("status", row.status)
                    .put("kind", row.kind)
                    .put("detail", row.detail)
            )
        }
    }
    return JSONObject()
        .put("phase", view.phase)
        .put("headline", view.headline)
        .put("note", view.note)
        .put("confirmedAbsences", view.confirmedAbsences ?: JSONObject.NULL)
        .put("pendingCount", view.pendingCount ?: JSONObject.NULL)
        .put("rangeStart", view.rangeStart)
        .put("rangeEnd", view.rangeEnd)
        .put("rangeConfirmed", view.rangeConfirmed)
        .put("rangeSource", view.rangeSource)
        .put("fetchedAt", view.fetchedAt)
        .put("queryComplete", view.queryComplete)
        .put("error", view.error)
        .put("listEmpty", view.listEmpty)
        .put("rows", rows)
}

data class PageStep(val done: Boolean, val nextPage: Int?, val incomplete: Boolean)

fun nextPageStep(page: Int, envelope: Envelope, collected: Int, seenStable: Set<String>): PageStep {
    if (!envelope.shapeOk) return PageStep(done = true, nextPage = null, incomplete = true)
    val fresh = envelope.records.mapNotNull { it.stableId }.filter { it !in seenStable }
    if (page > 1 && envelope.records.isNotEmpty() && fresh.isEmpty() && envelope.records.all { it.stableId != null }) {
        return PageStep(done = true, nextPage = null, incomplete = true)
    }
    val total = envelope.total
    if (total == null) return PageStep(done = true, nextPage = null, incomplete = false)
    if (collected >= total) return PageStep(done = true, nextPage = null, incomplete = false)
    if (envelope.records.isEmpty()) return PageStep(done = true, nextPage = null, incomplete = true)
    if (page >= HwsysUrls.MAX_PAGES) return PageStep(done = true, nextPage = null, incomplete = true)
    return PageStep(done = false, nextPage = page + 1, incomplete = false)
}

data class TokenBundle(val accessToken: String, val refreshToken: String, val userKey: String, val userName: String)

fun parseTokenBundle(body: String?): TokenBundle? {
    val obj = try {
        JSONObject(body?.trim().orEmpty())
    } catch (_: Exception) {
        return null
    }
    if (obj.optInt("status_code", 0) != 200) return null
    val access = obj.optString("access_token").trim()
    if (jwtExpiryEpochMs(access) == null) return null
    val user = obj.optJSONObject("userx") ?: obj.optJSONObject("user")
    val key = user?.optString("userx_key").orEmpty().trim()
    if (key.isEmpty()) return null
    return TokenBundle(
        accessToken = access,
        refreshToken = obj.optString("refresh_token").trim(),
        userKey = key,
        userName = user?.optString("name").orEmpty().trim(),
    )
}

sealed class FetchOutcome {
    data class Success(val cache: CacheBody) : FetchOutcome()
    data class Failed(val message: String, val auth: Boolean) : FetchOutcome()
    data object Stale : FetchOutcome()
}

fun interface AttendanceApi {
    fun getAttendance(token: String, start: LocalDate, end: LocalDate, page: Int?): Envelope
}

fun fetchTerm(
    api: AttendanceApi,
    token: String,
    accountHash: String,
    term: TermId,
    range: DateRange,
    rangeConfirmed: Boolean,
    rangeSource: String,
    nowMs: Long,
    isCurrent: () -> Boolean,
): FetchOutcome {
    if (range.end.isBefore(range.start)) return FetchOutcome.Failed("学期结束日期早于开始日期", false)
    val segments = segmentRange(range.start, range.end)
    if (segments.isEmpty()) return FetchOutcome.Failed("没有可查询的日期", false)
    val all = ArrayList<RawAttendance>()
    var complete = true
    for (segment in segments) {
        if (!isCurrent()) return FetchOutcome.Stale
        var page = 1
        var pageParam: Int? = null
        val seen = HashSet<String>()
        var segmentCount = 0
        while (true) {
            if (!isCurrent()) return FetchOutcome.Stale
            val envelope = try {
                api.getAttendance(token, segment.start, segment.end, pageParam)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return FetchOutcome.Failed(safeFailure("出勤查询失败", e), false)
            }
            if (envelope.authRequired) {
                return FetchOutcome.Failed(attendanceFailure(envelope), true)
            }
            if (envelope.statusCode != 200 || !envelope.shapeOk) {
                return FetchOutcome.Failed(attendanceFailure(envelope), false)
            }
            if (envelope.total == null && envelope.statsSum != null && envelope.statsSum != envelope.records.size) {
                complete = false
            }
            val step = nextPageStep(page, envelope, segmentCount + envelope.records.size, seen)
            all += envelope.records
            envelope.records.mapNotNull { it.stableId }.forEach { seen += it }
            segmentCount += envelope.records.size
            if (step.incomplete) complete = false
            if (step.done) break
            page = step.nextPage ?: break
            pageParam = page
        }
    }
    if (!isCurrent()) return FetchOutcome.Stale
    return FetchOutcome.Success(
        CacheBody(
            accountHash = accountHash,
            termId = term.text,
            dateStart = range.start.toString(),
            dateEnd = range.end.toString(),
            rangeConfirmed = rangeConfirmed,
            rangeSource = rangeSource,
            fetchedAt = nowMs,
            queryComplete = complete,
            foreignDropped = all.any { it.term.isNotBlank() && it.term != term.text },
            identityIncomplete = all.any { it.stableId == null },
            records = all,
        )
    )
}

object SyncText {
    fun compose(
        cancelled: Boolean,
        scheduleTried: Boolean,
        scheduleOk: Boolean,
        attendanceTried: Boolean,
        attendanceOk: Boolean,
        attendanceNeedsLogin: Boolean,
    ): String {
        if (cancelled && !scheduleOk && !attendanceOk) return "已取消登录"
        if (scheduleOk && attendanceOk) return "已同步课表与出勤"
        if (scheduleOk && attendanceNeedsLogin) return "课表已同步，出勤需重新登录"
        if (scheduleOk && attendanceTried && !attendanceOk) return "课表已同步，出勤获取失败"
        if (!scheduleOk && attendanceOk) return "出勤已同步，课表同步失败"
        if (attendanceNeedsLogin && scheduleTried && !scheduleOk) return "课表同步失败，出勤需重新登录"
        if (attendanceNeedsLogin) return "出勤需重新登录"
        if (scheduleTried && !scheduleOk && attendanceTried && !attendanceOk) return "课表与出勤都没有同步成功"
        if (scheduleTried && !scheduleOk) return "课表同步失败"
        if (attendanceTried && !attendanceOk) return "出勤获取失败"
        return "没有完成同步"
    }

    fun buttonLabel(syncing: Boolean, jwxtReady: Boolean, hwsysReady: Boolean, lastFailed: Boolean): String {
        if (syncing) return "同步中…"
        if (!jwxtReady || !hwsysReady) return "登录并同步"
        if (lastFailed) return "重新同步"
        return "同步数据"
    }
}

fun cacheToJson(cache: CacheBody): JSONObject {
    val records = JSONArray()
    cache.records.forEach { record ->
        records.put(
            JSONObject()
                .put("stableId", record.stableId ?: JSONObject.NULL)
                .put("title", record.title)
                .put("course", record.course)
                .put("taskTitle", record.taskTitle)
                .put("term", record.term)
                .put("timeStart", record.timeStart)
                .put("timeEnd", record.timeEnd)
                .put("status", if (record.statusPresent) record.status else JSONObject.NULL)
                .put("statusPresent", record.statusPresent)
        )
    }
    return JSONObject()
        .put("accountHash", cache.accountHash)
        .put("termId", cache.termId)
        .put("dateStart", cache.dateStart)
        .put("dateEnd", cache.dateEnd)
        .put("rangeConfirmed", cache.rangeConfirmed)
        .put("rangeSource", cache.rangeSource)
        .put("fetchedAt", cache.fetchedAt)
        .put("queryComplete", cache.queryComplete)
        .put("foreignDropped", cache.foreignDropped)
        .put("identityIncomplete", cache.identityIncomplete)
        .put("records", records)
}

fun cacheFromJson(raw: String?): CacheBody? {
    if (raw.isNullOrBlank()) return null
    val obj = try {
        JSONObject(raw)
    } catch (_: Exception) {
        return null
    }
    val records = ArrayList<RawAttendance>()
    val arr = obj.optJSONArray("records") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val present = item.optBoolean("statusPresent") && !item.isNull("status")
        records += RawAttendance(
            stableId = item.optString("stableId").trim().takeIf { it.isNotEmpty() && it != "null" && !item.isNull("stableId") },
            title = item.optString("title"),
            course = item.optString("course"),
            taskTitle = item.optString("taskTitle"),
            term = item.optString("term"),
            timeStart = item.optString("timeStart"),
            timeEnd = item.optString("timeEnd"),
            status = if (present) item.optInt("status") else null,
            statusPresent = present,
        )
    }
    val term = obj.optString("termId")
    if (parseTerm(term) == null) return null
    return CacheBody(
        accountHash = obj.optString("accountHash"),
        termId = term,
        dateStart = obj.optString("dateStart"),
        dateEnd = obj.optString("dateEnd"),
        rangeConfirmed = obj.optBoolean("rangeConfirmed"),
        rangeSource = obj.optString("rangeSource"),
        fetchedAt = obj.optLong("fetchedAt"),
        queryComplete = obj.optBoolean("queryComplete"),
        foreignDropped = obj.optBoolean("foreignDropped"),
        identityIncomplete = obj.optBoolean("identityIncomplete"),
        records = records,
    )
}
