package com.sztu.schedule.data.hwsys

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class AttendanceRepository(
    context: Context,
    private val session: HwsysSessionStore,
    private val api: AttendanceApi,
    private val http: HwsysHttp,
) {
    private val root = File(context.filesDir, "hwsys_attendance")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val generations = ConcurrentHashMap<String, Long>()

    fun hwsysReady(): Boolean = session.sessionPresent()

    fun hwsysStatus(): String = when {
        session.serverVerified() -> {
            val name = session.accountName()
            if (name.isBlank()) "已登录" else "已登录 · $name"
        }
        session.accessUsable() -> "本地会话待服务器确认"
        session.hasRefresh() -> "会话可续期"
        session.accountHash() != null -> "登录已失效，仍可看上次结果"
        else -> "未登录"
    }

    suspend fun prepareAccess(nowMs: Long = System.currentTimeMillis()): HwsysAccess = withContext(Dispatchers.IO) {
        if (session.accessUsable(nowMs)) {
            val token = session.accessToken()
            if (token != null) return@withContext HwsysAccess.Ready(token)
        }
        val refresh = session.refreshToken()
        if (refresh.isNullOrBlank()) return@withContext HwsysAccess.NeedsLogin
        try {
            val bundle = http.refresh(refresh)
            session.save(bundle)
            HwsysAccess.Ready(bundle.accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HwsysCallException) {
            if (e.kind == "auth") {
                session.clearTokens()
                HwsysAccess.NeedsLogin
            } else {
                HwsysAccess.Failed(e.message ?: "登录续期失败：请求失败")
            }
        } catch (e: Exception) {
            HwsysAccess.Failed(safeFailure("登录续期失败", e))
        }
    }

    fun resolveTerm(scheduleTerm: String): TermId? {
        return parseTerm(session.viewTerm()) ?: parseTerm(scheduleTerm)
    }

    fun confirmRange(termRaw: String, startRaw: String, endRaw: String): Boolean {
        val term = parseTerm(termRaw) ?: return false
        val start = parseDay(startRaw) ?: return false
        val end = parseDay(endRaw) ?: return false
        if (end.isBefore(start)) return false
        writeRange(term.text, start.toString(), end.toString(), confirmed = true, source = "user")
        session.setViewTerm(term.text)
        return true
    }

    fun selectTerm(termRaw: String) {
        val term = parseTerm(termRaw) ?: return
        session.setViewTerm(term.text)
    }

    fun snapshot(scheduleTerm: String, syncDetail: String): String {
        val now = Instant.now()
        val account = session.accountHash().orEmpty()
        val view = parseTerm(session.viewTerm()) ?: parseTerm(scheduleTerm)
        val schedule = parseTerm(scheduleTerm)
        val extras = cachedTerms(account)
        val choices = termChoices(scheduleTerm, extras)
        val terms = JSONArray()
        choices.forEach { term ->
            val range = storedRange(account, term.text) ?: suggested(term)
            terms.put(
                JSONObject()
                    .put("id", term.text)
                    .put("schedule", schedule?.text == term.text)
                    .put("start", range.start)
                    .put("end", range.end)
                    .put("confirmed", range.confirmed)
                    .put("source", range.source)
            )
        }
        val viewJson = describe(view?.text, schedule?.text, now)
        val todayJson = describe(schedule?.text, schedule?.text, now, includeRows = false)
        return JSONObject()
            .put("scheduleTerm", schedule?.text ?: "")
            .put("viewTerm", view?.text ?: "")
            .put("loggedIn", session.accessUsable() || session.hasRefresh())
            .put("accountName", session.accountName())
            .put("storageWarning", session.secureStorageUnavailable())
            .put("terms", terms)
            .put("view", viewJson)
            .put("today", todayJson)
            .put("syncDetail", syncDetail)
            .toString()
    }

    suspend fun sync(scheduleTerm: String, nowMs: Long = System.currentTimeMillis()): FetchOutcome = withContext(Dispatchers.IO) {
        val term = resolveTerm(scheduleTerm) ?: return@withContext FetchOutcome.Failed("没有可查询的学期编号", false)
        val account = session.accountHash() ?: return@withContext FetchOutcome.Failed("请先登录教学辅助系统", true)
        val access = when (val prepared = prepareAccess(nowMs)) {
            is HwsysAccess.Ready -> prepared.token
            is HwsysAccess.NeedsLogin -> return@withContext FetchOutcome.Failed("出勤查询失败：会话已失效", true)
            is HwsysAccess.Failed -> return@withContext FetchOutcome.Failed(prepared.message, false)
        }
        val range = storedRange(account, term.text) ?: suggested(term)
        val start = parseDay(range.start) ?: return@withContext FetchOutcome.Failed("学期开始日期还不能用", false)
        val end = parseDay(range.end) ?: return@withContext FetchOutcome.Failed("学期结束日期还不能用", false)
        val generation = begin(term.text)
        val outcome = try {
            fetchTerm(
                api = api,
                token = access,
                accountHash = account,
                term = term,
                range = DateRange(start, end),
                rangeConfirmed = range.confirmed,
                rangeSource = range.source,
                nowMs = nowMs,
                isCurrent = { generations[term.text] == generation && session.accountHash() == account },
            )
        } catch (e: CancellationException) {
            throw e
        }
        ensureActive()
        when (outcome) {
            is FetchOutcome.Success -> {
                if (generations[term.text] != generation || session.accountHash() != account) {
                    return@withContext FetchOutcome.Stale
                }
                writeCache(outcome.cache)
                clearError(account, term.text)
                session.markServerVerified()
            }
            is FetchOutcome.Failed -> {
                if (outcome.auth) session.clearTokens()
                if (generations[term.text] == generation && session.accountHash() == account) {
                    writeError(account, term.text, outcome.message, nowMs)
                }
            }
            FetchOutcome.Stale -> Unit
        }
        outcome
    }

    private fun describe(termId: String?, scheduleTermId: String?, now: Instant, includeRows: Boolean = true): JSONObject {
        val account = session.accountHash().orEmpty()
        val range = if (termId != null) storedRange(account, termId) ?: parseTerm(termId)?.let { suggested(it) } else null
        val cache = if (termId != null && account.isNotBlank()) readCache(account, termId) else null
        val error = if (termId != null) errorOf(account, termId) else ""
        val errorAt = if (termId != null) errorAtOf(account, termId) else 0L
        val view = buildView(
            loggedIn = session.sessionPresent(),
            hasAccount = account.isNotBlank(),
            termId = termId,
            cache = cache,
            error = error,
            errorAt = errorAt,
            now = now,
            isScheduleTerm = termId != null && termId == scheduleTermId,
            displayStart = range?.start.orEmpty(),
            displayEnd = range?.end.orEmpty(),
            displayConfirmed = range?.confirmed == true,
            displaySource = range?.source.orEmpty(),
        )
        return viewToJson(view, includeRows)
    }

    private fun suggested(term: TermId): StoredRange {
        val range = heuristicRange(term)
        return StoredRange(range.start.toString(), range.end.toString(), confirmed = false, source = "hwsys_term_util_heuristic")
    }

    private fun begin(term: String): Long {
        val next = (generations[term] ?: 0L) + 1L
        generations[term] = next
        return next
    }

    private fun cachedTerms(account: String): List<String> {
        if (account.isBlank()) return emptyList()
        val dir = File(root, account)
        return dir.listFiles()?.mapNotNull { file ->
            val name = file.nameWithoutExtension
            parseTerm(name)?.text
        }.orEmpty()
    }

    private fun cacheFile(account: String, term: String): File {
        val safeAccount = account.filter { it.isDigit() || it in 'a'..'f' }
        val safeTerm = parseTerm(term)?.text ?: "invalid"
        return File(File(root, safeAccount), "$safeTerm.json")
    }

    private fun readCache(account: String, term: String): CacheBody? {
        val file = cacheFile(account, term)
        if (!file.isFile) return null
        val cache = cacheFromJson(file.readText()) ?: return null
        if (cache.accountHash != account || cache.termId != term) return null
        return cache
    }

    private fun writeCache(cache: CacheBody) {
        val file = cacheFile(cache.accountHash, cache.termId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(cacheToJson(cache).toString())
        if (file.exists() && !file.delete()) {
            file.writeText(tmp.readText())
            tmp.delete()
        } else if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private data class StoredRange(val start: String, val end: String, val confirmed: Boolean, val source: String)

    private fun rangeKey(account: String, term: String) = "$account|$term"

    private fun storedRange(account: String, term: String): StoredRange? {
        if (account.isBlank()) return null
        val obj = ranges().optJSONObject(rangeKey(account, term)) ?: return null
        val start = obj.optString("start")
        val end = obj.optString("end")
        if (parseDay(start) == null || parseDay(end) == null) return null
        return StoredRange(start, end, obj.optBoolean("confirmed"), obj.optString("source"))
    }

    private fun writeRange(term: String, start: String, end: String, confirmed: Boolean, source: String) {
        val account = session.accountHash() ?: return
        val all = ranges()
        all.put(
            rangeKey(account, term),
            JSONObject().put("start", start).put("end", end).put("confirmed", confirmed).put("source", source)
        )
        prefs.edit().putString(KEY_RANGES, all.toString()).apply()
    }

    private fun ranges(): JSONObject {
        val raw = prefs.getString(KEY_RANGES, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun errorKey(account: String, term: String) = "err|$account|$term"

    private fun writeError(account: String, term: String, message: String, at: Long) {
        prefs.edit()
            .putString(errorKey(account, term), redact(message))
            .putLong(errorKey(account, term) + "|at", at)
            .apply()
    }

    private fun clearError(account: String, term: String) {
        prefs.edit().remove(errorKey(account, term)).remove(errorKey(account, term) + "|at").apply()
    }

    private fun errorOf(account: String, term: String): String =
        if (account.isBlank()) "" else prefs.getString(errorKey(account, term), "").orEmpty()

    private fun errorAtOf(account: String, term: String): Long =
        if (account.isBlank()) 0L else prefs.getLong(errorKey(account, term) + "|at", 0L)

    companion object {
        private const val PREFS = "sztu_hwsys_attendance"
        private const val KEY_RANGES = "ranges"

        @Volatile private var instance: AttendanceRepository? = null

        fun get(context: Context): AttendanceRepository {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: AttendanceRepository(
                    context.applicationContext,
                    HwsysSessionStore(context.applicationContext),
                    HwsysHttp(),
                    HwsysHttp(),
                ).also { instance = it }
            }
        }
    }
}
