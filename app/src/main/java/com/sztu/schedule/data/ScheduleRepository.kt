package com.sztu.schedule.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

object JwxtUrls {
    const val SSO =
        "https://auth.sztu.edu.cn/idp/authcenter/ActionAuthChain?entityId=jiaowu"
    const val SCHEDULE = "https://jwxt.sztu.edu.cn/jsxsd/xskb/xskb_list.do"
    const val MAIN = "https://jwxt.sztu.edu.cn/jsxsd/framework/xsMain.jsp"
}

class ScheduleRepository(private val context: Context) {
    private val file = File(context.filesDir, "schedule.json")
    private val backupFile = File(context.filesDir, "schedule.json.bak")
    private val tmpFile = File(context.filesDir, "schedule.json.tmp")
    private val prefs = context.getSharedPreferences("sztu_schedule", Context.MODE_PRIVATE)

    /** 进程内副本（path → header）：Keystore 不可用时本次运行仍可请求，重启后需重新登录。 */
    @Volatile private var memJwxtScopes: Map<String, String> = emptyMap()
    @Volatile private var memAuthScopes: Map<String, String> = emptyMap()

    fun contextFilesDir(): File = context.filesDir

    init {
        // 解析器升级：**不再删除旧课表**（报告 J06）。
        // 只在无网/离线时仍能看旧课表，同时标记需要重新同步。
        if (prefs.getInt("parse_epoch", 0) < CURRENT_PARSE_EPOCH) {
            prefs.edit()
                .putInt("parse_epoch", CURRENT_PARSE_EPOCH)
                .putBoolean("needs_resync", true)
                .apply()
        }
        // 旧版把 auth+jwxt 的 cookie 合并成一个字符串保存，无法可靠分域（报告 J03）。
        // 直接丢弃，改由 WebView CookieManager 按 host 重新采集。
        if (prefs.contains(LEGACY_COOKIE_KEY)) {
            prefs.edit().remove(LEGACY_COOKIE_KEY).apply()
        }
    }

    private val _snapshot = MutableStateFlow(load())
    val snapshot: StateFlow<ScheduleSnapshot> = _snapshot.asStateFlow()

    fun isLoggedInHint(): Boolean =
        prefs.getBoolean("has_session", false) || cookieFor(JwxtUrls.SCHEDULE).isNotBlank()

    /**
     * 会话状态三态：有缓存 ≠ 认证仍然有效。
     * 只有最近一次真实同步成功（HTTP 200 + 非登录页 + 解析成功）才算 SERVER_VERIFIED。
     */
    fun sessionState(): SessionState = SessionStateResolver.resolve(
        nowMs = System.currentTimeMillis(),
        verifiedAtMs = prefs.getLong("session_verified_at", 0L),
        verifiedTtlMs = VERIFIED_TTL_MS,
        hasCookie = cookieFor(JwxtUrls.SCHEDULE).isNotBlank(),
        hasCachedCourses = _snapshot.value.courses.isNotEmpty(),
    )

    fun needsResync(): Boolean = prefs.getBoolean("needs_resync", false)

    fun isEmptyTerm(): Boolean = prefs.getBoolean("empty_term", false)

    fun markLoggedIn() {
        prefs.edit().putBoolean("has_session", true).apply()
    }

    fun clearSession() {
        memJwxtScopes = emptyMap()
        memAuthScopes = emptyMap()
        prefs.edit()
            .putBoolean("has_session", false)
            .remove(COOKIE_JWXT)
            .remove(COOKIE_AUTH)
            .remove(SEALED_JWXT_SCOPES)
            .remove(SEALED_AUTH_SCOPES)
            .remove(FLAG_NO_SECURE_STORAGE)
            .remove("session_verified_at")
            .apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    /**
     * 从 WebView CookieManager 采集并**按 host 分开**持久化（报告 J03）。
     * 持久化内容经 Android Keystore 封装（报告 J08）：Keystore 不可用时只留内存副本，
     * 不静默退回明文。
     */
    fun persistWebViewCookies() {
        val cm = CookieManager.getInstance()
        cm.flush()
        // R07：按 path 作用域分别保存，而不是把所有路径压成一个全域 header
        val jwxtScopes = LinkedHashMap<String, String>()
        for (path in JWXT_PATHS) {
            val header = CookieJar.headerForSameHost(
                listOf(cm.getCookie("https://${CookieJar.HOST_JWXT}$path"))
            )
            if (header.isNotBlank()) jwxtScopes[path] = header
        }
        val authScopes = LinkedHashMap<String, String>()
        val authHeader = CookieJar.headerForSameHost(
            listOf(cm.getCookie("https://${CookieJar.HOST_AUTH}/"))
        )
        if (authHeader.isNotBlank()) authScopes["/"] = authHeader

        memJwxtScopes = jwxtScopes
        memAuthScopes = authScopes

        val editor = prefs.edit()
        // 立即清除历史明文（旧版本遗留）
        editor.remove(COOKIE_JWXT).remove(COOKIE_AUTH).remove(LEGACY_COOKIE_KEY)
        editor.remove(SEALED_JWXT_SCOPES).remove(SEALED_AUTH_SCOPES)
        if (jwxtScopes.isNotEmpty()) {
            val sealed = SecretStore.seal(CookieJar.encodeScopes(jwxtScopes))
            if (sealed != null) editor.putString(SEALED_JWXT_SCOPES, sealed)
            else editor.putBoolean(FLAG_NO_SECURE_STORAGE, true)
        }
        if (authScopes.isNotEmpty()) {
            val sealed = SecretStore.seal(CookieJar.encodeScopes(authScopes))
            if (sealed != null) editor.putString(SEALED_AUTH_SCOPES, sealed)
            else editor.putBoolean(FLAG_NO_SECURE_STORAGE, true)
        }
        editor.putLong("cookie_saved_at", System.currentTimeMillis())
        editor.apply()
        if (jwxtScopes.isNotEmpty()) markLoggedIn()
    }

    /** 加密存储是否真的可用（UI 可据此提示“重启后需重新登录”）。 */
    fun secureStorageAvailable(): Boolean = SecretStore.isAvailable()

    private fun loadScopes(sealedKey: String, mem: Map<String, String>): List<Pair<String, String>> {
        if (mem.isNotEmpty()) return mem.toList()
        val raw = SecretStore.open(prefs.getString(sealedKey, "")).orEmpty()
        return CookieJar.decodeScopes(raw)
    }

    /**
     * 只返回目标 host + 路径匹配的 Cookie（R07）：
     * host 必须是精确已知域名，path 取最具体的作用域。
     */
    fun cookieFor(url: String): String {
        val host = CookieJar.hostOf(url)
        if (!CookieJar.isKnownHost(host)) return ""
        val isJwxt = host == CookieJar.HOST_JWXT
        val scopes = if (isJwxt) {
            loadScopes(SEALED_JWXT_SCOPES, memJwxtScopes)
        } else {
            loadScopes(SEALED_AUTH_SCOPES, memAuthScopes)
        }
        if (scopes.isEmpty()) return ""
        return CookieJar.pickForPath(scopes, CookieJar.pathOf(url))
    }

    /** 兼容旧调用：返回教务（jwxt）域 Cookie。 */
    fun savedCookieHeader(): String {
        val saved = cookieFor(JwxtUrls.SCHEDULE)
        if (saved.isNotBlank()) return saved
        persistWebViewCookies()
        return cookieFor(JwxtUrls.SCHEDULE)
    }

    private fun readSnapshot(from: File): ScheduleSnapshot? {
        if (!from.exists()) return null
        return try {
            ScheduleSnapshot.fromJson(from.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun load(): ScheduleSnapshot {
        // 主缓存损坏/被截断时回退到最后可用副本（报告 J06）
        return readSnapshot(file) ?: readSnapshot(backupFile) ?: ScheduleSnapshot.empty()
    }

    /** 原子写入：临时文件 → 备份旧文件 → 重命名替换（报告 J06）。 */
    fun save(snapshot: ScheduleSnapshot) {
        val text = snapshot.toJson().toString()
        try {
            tmpFile.writeText(text)
            if (file.exists()) {
                file.copyTo(backupFile, overwrite = true)
            }
            if (!tmpFile.renameTo(file)) {
                // 某些文件系统不支持 rename 覆盖，退化为直接写但仍保留备份
                file.writeText(text)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        _snapshot.value = snapshot
        prefs.edit()
            .putBoolean("needs_resync", false)
            .putLong("last_saved_at", System.currentTimeMillis())
            .apply()
        markLoggedIn()
    }

    fun reloadFromDisk(): ScheduleSnapshot {
        val s = load()
        _snapshot.value = s
        return s
    }

    suspend fun refreshFromJwxt(): Result<ScheduleSnapshot> = withContext(Dispatchers.IO) {
        try {
            persistWebViewCookies()
            val cookie = cookieFor(JwxtUrls.SCHEDULE)
            if (!CookieJar.hasSessionId(cookie)) {
                return@withContext Result.failure(IllegalStateException("教务未登录，请先官方登录"))
            }
            val html = fetch(JwxtUrls.SCHEDULE, cookie)
            if (looksLikeLoginPage(html)) {
                return@withContext Result.failure(IllegalStateException("会话已过期，请重新登录"))
            }
            if (!looksLikeTimetablePage(html)) {
                // 结构变化 ≠ 空学期；保留旧课表，明确提示而不是把缓存清空（报告 J06）
                prefs.edit().putBoolean("needs_resync", true).apply()
                return@withContext Result.failure(
                    IllegalStateException("教务页面结构有变化，暂时无法解析；已保留上次课表")
                )
            }
            val parsed = ScheduleHtmlParser.parse(html)
            val emptyTerm = parsed.courses.isEmpty()
            // 有课表容器但 0 门课 = 正常空学期，必须能保存并显示为空（报告 J06）
            save(parsed)
            prefs.edit()
                .putBoolean("empty_term", emptyTerm)
                .putLong("session_verified_at", System.currentTimeMillis())
                .apply()
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetch(url: String, cookie: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000
            readTimeout = 25000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Cookie", cookie)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Referer", JwxtUrls.MAIN)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    companion object {
        @Volatile private var instance: ScheduleRepository? = null

        /** 解析器版本：升级时不删旧课表，只标记 needs_resync（报告 J06）。 */
        const val CURRENT_PARSE_EPOCH = 6

        /** 「服务端已验证」的有效期；过期后降级为 CACHED_ONLY。 */
        const val VERIFIED_TTL_MS = 12 * 60 * 60 * 1000L

        private const val COOKIE_JWXT = "cookie_jwxt"
        private const val COOKIE_AUTH = "cookie_auth"
        /** R07：按 path 作用域保存（加密后的 scope 列表）。 */
        private const val SEALED_JWXT_SCOPES = "cookie_jwxt_scopes"
        private const val SEALED_AUTH_SCOPES = "cookie_auth_scopes"
        private const val FLAG_NO_SECURE_STORAGE = "secure_storage_unavailable"
        private const val LEGACY_COOKIE_KEY = "jwxt_cookie"

        /** 采集用的 path 作用域（选择时按最长前缀匹配）。 */
        private val JWXT_PATHS = listOf("/jsxsd/xskb/xskb_list.do", "/jsxsd/xskb/", "/jsxsd/", "/")

        fun get(context: Context): ScheduleRepository {
            return instance ?: synchronized(this) {
                instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
            }
        }

        /** 页面里是否有课表容器：用于区分「正常空学期」和「页面结构变了」。 */
        fun looksLikeTimetablePage(html: String): Boolean {
            if (html.isBlank()) return false
            return html.contains("id=\"timetable\"", ignoreCase = true) ||
                html.contains("id='timetable'", ignoreCase = true) ||
                html.contains("class=\"kbcontent", ignoreCase = true) ||
                html.contains("学期理论课表") ||
                html.contains("xskb_list")
        }

        /**
         * 更严格的登录页判断，避免课表页脚本里出现 userAccount 等字样被误判为过期。
         */
        fun looksLikeLoginPage(html: String): Boolean {
            if (html.isBlank()) return true
            // 正特征：课表页
            if (html.contains("id=\"timetable\"", ignoreCase = true) ||
                html.contains("id='timetable'", ignoreCase = true) ||
                html.contains("kbcontent", ignoreCase = true) ||
                html.contains("班级课表") ||
                html.contains("学期理论课表") ||
                html.contains("xskb_list")
            ) {
                return false
            }
            val hasAccountField =
                html.contains("id=\"userAccount\"", ignoreCase = true) ||
                    html.contains("name=\"userAccount\"", ignoreCase = true) ||
                    html.contains("id='userAccount'", ignoreCase = true)
            val hasPassword =
                html.contains("type=\"password\"", ignoreCase = true) ||
                    html.contains("type='password'", ignoreCase = true)
            if (hasAccountField && hasPassword) return true
            if (html.contains("ActionAuthChain") && hasPassword) return true
            if (html.contains("统一身份认证") && hasPassword) return true
            if (html.contains("LoginToXk", ignoreCase = true) && hasPassword) return true
            return false
        }
    }
}

object ScheduleClock {
    /** 周次匹配结果：必须区分「本周不授课」和「周次解析不出来」。 */
    enum class WeekMatch { MATCH, NO_MATCH, UNKNOWN }

    /** 今日页状态：本周无课要显示为空，不能用其它周的课顶替。 */
    enum class TodayStatus { OK, NO_CLASS, WEEK_UNKNOWN }

    fun todayWeekday(): Int {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun parseHm(hm: String): Int {
        val p = hm.split(":")
        if (p.size < 2) return 0
        return (p[0].toIntOrNull() ?: 0) * 60 + (p[1].toIntOrNull() ?: 0)
    }

    data class TodayView(
        val today: List<Course>,
        val previous: Course?,
        val current: Course?,
        val next: Course?,
        val status: TodayStatus = TodayStatus.OK,
    )

    /**
     * @param teachingWeek 当前教学周；<=0 表示周次未知，此时不按周过滤（保持旧行为）
     * @param weekday      可注入的星期（1..7），便于离线测试
     * @param nowMinute    可注入的当前分钟，便于离线测试“下课整分钟”等边界
     */
    fun buildTodayView(
        all: List<Course>,
        teachingWeek: Int = 0,
        weekday: Int = todayWeekday(),
        nowMinute: Int = nowMinutes(),
    ): TodayView {
        val today = all.filter { it.weekday == weekday }
            .sortedBy { parseHm(it.startTime) }

        val visible: List<Course>
        val status: TodayStatus
        if (teachingWeek <= 0) {
            // 周次未知：不过滤，避免把课藏起来；空则明确为“今日无课”
            visible = today
            status = if (today.isEmpty()) TodayStatus.NO_CLASS else TodayStatus.OK
        } else {
            val classified = today.map { it to matchWeek(it.weeks, teachingWeek) }
            val matched = classified.filter { it.second == WeekMatch.MATCH }.map { it.first }
            // 周次解析不出来的课保留但标记待核对，绝不静默丢弃
            val unknown = classified.filter { it.second == WeekMatch.UNKNOWN }.map { it.first }
            visible = (matched + unknown).sortedBy { parseHm(it.startTime) }
            status = when {
                matched.isNotEmpty() -> TodayStatus.OK
                unknown.isNotEmpty() -> TodayStatus.WEEK_UNKNOWN
                else -> TodayStatus.NO_CLASS
            }
        }

        var previous: Course? = null
        var current: Course? = null
        var next: Course? = null
        for (c in visible) {
            val s = parseHm(c.startTime)
            val e = parseHm(c.endTime)
            when {
                // 统一为 start <= now < end：下课的整分钟不再算“这节”
                nowMinute >= s && nowMinute < e -> current = c
                e <= nowMinute -> previous = c
                next == null && s > nowMinute -> next = c
            }
        }
        if (current == null && next == null && visible.isNotEmpty() && nowMinute < parseHm(visible.first().startTime)) {
            next = visible.first()
        }
        if (current == null && next == null && visible.isNotEmpty() && nowMinute >= parseHm(visible.last().endTime)) {
            previous = visible.last()
        }
        return TodayView(visible, previous, current, next, status)
    }

    /** 三态周次匹配。空周次/无法解析 → UNKNOWN（而不是“本周不授课”）。 */
    fun matchWeek(weeks: String, week: Int): WeekMatch {
        val raw = weeks.trim()
        if (raw.isEmpty()) return WeekMatch.UNKNOWN
        // 仅识别「单周 / (单)」——禁止裸匹配「单」（会误伤其它字）
        val oddOnly = Regex("""单周|（单）|\(单\)""").containsMatchIn(raw) &&
            !Regex("""双周|（双）|\(双\)""").containsMatchIn(raw)
        val evenOnly = Regex("""双周|（双）|\(双\)""").containsMatchIn(raw)
        if (oddOnly && week % 2 == 0) return WeekMatch.NO_MATCH
        if (evenOnly && week % 2 == 1) return WeekMatch.NO_MATCH
        // 先去掉「星期X」，再去掉「第/周」，避免「第7周 星期三」被拆烂
        var body = raw.substringBefore("[").substringBefore("节")
        body = body
            .replace(Regex("""星期[一二三四五六日天]"""), "")
            .replace(Regex("""周[一二三四五六日天]"""), "")
            .replace(Regex("""[（(]周[）)]"""), "")
            .replace(Regex("""[第周单双]"""), "")
            .replace('～', '-')
            .replace('—', '-')
            .replace('~', '-')
            .replace(Regex("""[()（）]"""), " ")
            .trim()
        if (body.isBlank() || !body.any { it.isDigit() }) return WeekMatch.UNKNOWN
        for (part in body.split(',', '，', '、', ' ', ';', '；')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.contains('-')) {
                val ab = p.split('-')
                val a = ab.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull()
                val b = ab.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()
                if (a != null && b != null && week in minOf(a, b)..maxOf(a, b)) return WeekMatch.MATCH
            } else {
                val n = p.filter { it.isDigit() }.toIntOrNull()
                if (n == week) return WeekMatch.MATCH
            }
        }
        return WeekMatch.NO_MATCH
    }

    /** 兼容旧调用：未知周次按“可能上课”处理（不隐藏课程）。 */
    fun matchesWeek(weeks: String, week: Int): Boolean = matchWeek(weeks, week) != WeekMatch.NO_MATCH
}
