package com.sztu.schedule.duifene

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface AppSessionStore {
    fun load(): Map<String, String>?
    fun save(values: Map<String, String>)
    fun clear()
}

/**
 * 对分易 App REST。发现链路不走 GetModelByInProgress。
 * 移植自 bytehola/duifene_auto_sign ce1608e。
 */
class DfAppApi(
    private val transport: DfTransport,
    private val settings: DuifeneSettings,
    private val sessionStore: AppSessionStore? = null,
    private val clock: DfClock = SystemDfClock,
) {
    private val authLock = ReentrantLock()
    internal var token: String? = null
        private set
    private var refreshToken: String? = null
    private var tokenExpiresAt: Long = 0L
    private var refreshExpiresAt: Long = 0L
    var identity: AppIdentity? = null
        private set

    internal var checkinOverride: ((String) -> AppCheckIn?)? = null

    val isAuthenticated: Boolean
        get() = authLock.withLock {
            !token.isNullOrEmpty() &&
                !refreshToken.isNullOrEmpty() &&
                identity != null &&
                refreshExpiresAt > clock.wallMs()
        }

    fun restore() {
        restorePersisted()
    }

    fun clear() {
        authLock.withLock {
            clearMemory()
            sessionStore?.clear()
        }
    }

    private fun clearMemory() {
        token = null
        refreshToken = null
        tokenExpiresAt = 0L
        refreshExpiresAt = 0L
        identity = null
    }

    fun login(username: String, password: String): Boolean {
        authLock.withLock { clearMemory() }
        val reply = try {
            transport.exchange(
                DfCall(
                    method = "POST",
                    url = url("/Auth/login"),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "User-Agent" to settings.userAgent,
                        "Accept" to "application/json",
                    ),
                    body = JSONObject().put("username", username).put("password", password).toString(),
                )
            )
        } catch (error: TransportException) {
            restorePersisted()
            return false
        }
        val payload = try {
            JSONObject(reply.body)
        } catch (_: Exception) {
            restorePersisted()
            return false
        }
        if (reply.status != 200 || !storeLoginPayload(payload, initialLogin = true)) {
            restorePersisted()
            return false
        }
        if (identity == null) {
            clear()
            return false
        }
        return true
    }

    fun fetchCourses(): List<Course> {
        val who = requireIdentity()
        val coursePath = when (who.userTypeId) {
            "1" -> "/Course/GetTeacherCourseList"
            "2" -> "/Course/GetStudentCourseList"
            "3" -> "/Course/GetAssistantCourseList"
            else -> "/Course/GetStudentCourseList"
        }
        val payload = request("GET", coursePath)
        requireSuccess(payload, "未返回课程")
        val data = payload.optJSONObject("data") ?: throw AppApiError("课程数据格式异常")
        val rawCourses = data.optJSONArray("courses") ?: throw AppApiError("课程数据格式异常")
        val courses = mutableListOf<Course>()
        for (index in 0 until rawCourses.length()) {
            val raw = rawCourses.optJSONObject(index) ?: continue
            val courseId = jsonText(raw, "courseID")
            val courseName = jsonText(raw, "courseName").trim()
            if (courseId.isEmpty() || courseId == "0") continue
            val classes = fetchClasses(courseId, who.userTypeId)
            val teacher = firstText(raw, "teacherName", "teacher")
            for ((classId, className, classTeacher) in classes) {
                var display = courseName
                val whoTeaches = classTeacher.ifBlank { teacher }
                if (classes.size > 1 && className.isNotBlank()) {
                    display = "$courseName · $className"
                }
                if (whoTeaches.isNotBlank() && classes.size > 1) {
                    display = "$display · $whoTeaches"
                }
                courses += Course(courseId, classId, display)
            }
        }
        return disambiguate(courses)
    }

    /** 旧入口只保留给权限语义测试，监听不会调用它。 */
    fun fetchActiveCheckin(courseId: String): AppCheckIn? {
        val payload = request("GET", "/CK/GetModelByInProgress/${urlPath(courseId)}")
        requireSuccess(payload, "读取当前签到失败")
        val data = payload.optJSONObject("data") ?: throw AppApiError("当前签到数据格式异常")
        return try {
            AppCheckIn.fromPayload(data)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun fetchStudentRecords(classId: String): List<JSONObject> {
        val who = requireIdentity()
        val payload = request(
            "GET",
            "/CK/GetStudentInLogList/${urlPath(classId)}/${urlPath(who.userId)}",
        )
        requireSuccess(payload, "读取学生签到记录失败")
        val rows = payload.optJSONArray("data") ?: throw AppApiError("学生签到记录列表格式异常")
        val normalized = mutableListOf<JSONObject>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: throw AppApiError("学生签到记录格式异常")
            val statusId = jsonText(row, "StatusID")
            val checkInStatus = if (row.has("CheckInStatus") && !row.isNull("CheckInStatus")) jsonText(row, "CheckInStatus") else ""
            val checkInDatePresent = row.has("CheckInDate") && !row.isNull("CheckInDate") && jsonText(row, "CheckInDate").isNotEmpty()
            val knownSelf = (statusId == "2" && checkInStatus.isEmpty() && !checkInDatePresent) ||
                (statusId == "1" && checkInStatus == "1" && checkInDatePresent)
            val nullSelf = row.has("StudentID") && row.isNull("StudentID") && knownSelf
            val studentId = if (row.has("StudentID") && !row.isNull("StudentID")) jsonText(row, "StudentID") else ""
            if (studentId != who.userId && !nullSelf) {
                throw PermissionDenied("学生签到记录的身份归属不匹配，已停止处理")
            }
            val activityId = jsonText(row, "ID")
            if (activityId.isEmpty() || !activityId.all { it.isDigit() } || activityId.toLong() <= 0L) {
                throw AppApiError("学生签到记录的活动标识无效")
            }
            val copy = JSONObject(row.toString())
            copy.put("StudentID", who.userId)
            normalized += copy
        }
        return normalized
    }

    fun fetchCheckin(activityId: String): AppCheckIn? {
        checkinOverride?.let { return it(activityId) }
        val payload = request("GET", "/CK/GetCheckIn/${urlPath(activityId)}")
        requireSuccess(payload, "读取签到详情失败")
        val data = payload.optJSONObject("data") ?: return null
        return try {
            AppCheckIn.fromPayload(data)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun fetchHeadcount(activityId: String): Pair<Int, Int> {
        if (activityId.isEmpty() || !activityId.all { it.isDigit() }) {
            throw AppApiError("签到人数数据格式异常")
        }
        val payload = request(
            "POST",
            "/CK/GetCheckInTotalByCheckInIds",
            JSONObject().put("checkInIds", JSONArray().put(activityId.toLong())).toString(),
        )
        requireSuccess(payload, "读取签到人数失败")
        val data = payload.optJSONObject("data") ?: throw AppApiError("签到人数数据格式异常")
        val total = data.optInt("totalNumber", -1)
        val absence = data.optInt("absenceNumber", -1)
        if (total < 0 || absence < 0) throw AppApiError("签到人数数据格式异常")
        return (total - absence).coerceAtLeast(0) to total
    }

    fun signByCode(code: String): String = sign("/CK/StudentCheckIn", JSONObject().put("checkInCode", code))

    fun signByQr(token: String): String = sign("/CK/QrCodeCheckIn", JSONObject().put("checkInCode", token))

    fun signByLocation(longitude: Double, latitude: Double): String =
        sign(
            "/CK/CheckInSignInLocation",
            JSONObject().put("latitude", latitude).put("longitude", longitude),
        )

    internal fun storeLoginPayload(payload: JSONObject, initialLogin: Boolean): Boolean {
        if (!jsonSuccess(payload) || jsonCode(payload) != "200") return false
        val data = payload.optJSONObject("data") ?: return false
        val access = jsonText(data, "token")
        if (access.isEmpty()) return false
        val refreshed = jsonText(data, "refreshToken")
        return authLock.withLock {
            val nextRefresh = when {
                refreshed.isNotEmpty() -> refreshed
                !initialLogin -> refreshToken
                else -> null
            }
            if (nextRefresh.isNullOrEmpty()) return@withLock false
            var nextIdentity = identity
            val userInfo = data.optJSONObject("userInfo")
            if (userInfo != null) {
                val userId = jsonText(userInfo, "userId")
                val account = jsonText(userInfo, "userLogin")
                if (userId.isNotEmpty() && account.isNotEmpty()) {
                    nextIdentity = AppIdentity(
                        account = account,
                        userId = userId,
                        userName = jsonText(userInfo, "userName"),
                        userTypeId = jsonText(userInfo, "userTypeID"),
                    )
                }
            }
            if (nextIdentity == null) return@withLock false
            val now = clock.wallMs()
            token = access
            tokenExpiresAt = now + DuifenePolicy.ACCESS_TOKEN_LIFETIME_MS
            if (refreshed.isNotEmpty()) {
                refreshToken = refreshed
                refreshExpiresAt = now + DuifenePolicy.REFRESH_TOKEN_LIFETIME_MS
            }
            identity = nextIdentity
            saveSession()
            true
        }
    }

    internal fun testingSetIdentity(value: AppIdentity?) {
        identity = value
    }

    fun ensureSessionFresh(force: Boolean = false): Boolean {
        authLock.withLock {
            if (token.isNullOrEmpty() || refreshToken.isNullOrEmpty() || identity == null || refreshExpiresAt <= clock.wallMs()) {
                clear()
                throw AuthError("登录状态已失效，请重新登录账号")
            }
            val refreshDeadline = tokenExpiresAt - DuifenePolicy.TOKEN_REFRESH_AHEAD_MS
            if (!force && clock.wallMs() < refreshDeadline) return false
            if (!refreshAccessToken()) throw AuthError("登录状态已失效，请重新登录账号")
            return true
        }
    }

    private fun refreshAccessToken(): Boolean {
        if (token.isNullOrEmpty() || refreshToken.isNullOrEmpty() || identity == null || refreshExpiresAt <= clock.wallMs()) {
            clear()
            return false
        }
        val oldToken = token ?: return false
        val refresh = refreshToken ?: return false
        val reply = try {
            transport.exchange(
                DfCall(
                    method = "GET",
                    url = url("/Auth/RefreshAccessToken") +
                        "?OldToken=${urlPath(oldToken)}&RefreshToken=${urlPath(refresh)}",
                    headers = mapOf(
                        "Authorization" to "Bearer $oldToken",
                        "User-Agent" to settings.userAgent,
                        "Accept" to "application/json",
                    ),
                )
            )
        } catch (error: TransportException) {
            throw AppApiError("登录状态续期失败，请检查网络或代理设置")
        }
        val payload = try {
            JSONObject(reply.body)
        } catch (_: Exception) {
            throw AppApiError("登录状态续期返回了无法解析的数据")
        }
        if (reply.status == 400 || reply.status == 401 || reply.status == 403) {
            clear()
            return false
        }
        if (reply.status != 200) throw AppApiError("登录状态续期失败（HTTP ${reply.status}）")
        if (!storeLoginPayload(payload, initialLogin = false)) {
            clear()
            return false
        }
        return true
    }

    private fun request(
        method: String,
        path: String,
        jsonBody: String? = null,
        allowRefresh: Boolean = true,
        uncertainAsUnconfirmed: Boolean = false,
    ): JSONObject {
        ensureSessionFresh()
        val requestToken = authLock.withLock { token }
        if (requestToken.isNullOrEmpty()) throw AuthError("登录状态已失效，请重新登录账号")
        val reply = try {
            transport.exchange(
                DfCall(
                    method = method,
                    url = url(path),
                    headers = mapOf(
                        "Authorization" to "Bearer $requestToken",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to settings.userAgent,
                    ),
                    body = jsonBody,
                )
            )
        } catch (error: TransportException) {
            if (error.uncertain && uncertainAsUnconfirmed) throw UnconfirmedSubmit("网络结果未知，需要回查本人记录")
            throw AppApiError("网络请求失败，请检查网络或代理设置")
        }
        if (reply.status == 401 && allowRefresh) {
            ensureSessionFresh(force = true)
            return request(method, path, jsonBody, allowRefresh = false, uncertainAsUnconfirmed = uncertainAsUnconfirmed)
        }
        if (reply.status == 401) {
            clear()
            throw AuthError("登录状态已失效，请重新登录账号")
        }
        if (reply.status == 403) throw permissionError(path)
        if (reply.status != 200) throw AppApiError("请求失败（HTTP ${reply.status}）")
        val payload = try {
            JSONObject(reply.body.ifBlank { "{}" })
        } catch (_: Exception) {
            throw AppApiError("返回了无法解析的数据")
        }
        if (jsonCode(payload) == "403") throw permissionError(path)
        return payload
    }

    private fun sign(path: String, data: JSONObject): String {
        val payload = request("POST", path, data.toString(), uncertainAsUnconfirmed = true)
        val message = when {
            payload.has("message") && !payload.isNull("message") -> jsonText(payload, "message")
            payload.has("msgbox") && !payload.isNull("msgbox") -> jsonText(payload, "msgbox")
            else -> null
        }
        if (message == null) {
            if (payload.optBoolean("success")) throw AppApiError("未返回签到结果")
            throw AppApiError("签到请求失败")
        }
        return message
    }

    private fun fetchClasses(courseId: String, userTypeId: String): List<Triple<String, String, String>> {
        val payload = request(
            "GET",
            "/Class/GetClassListByCourseID/${urlPath(courseId)}/${urlPath(userTypeId)}",
        )
        requireSuccess(payload, "未返回教学班")
        val data = payload.optJSONArray("data") ?: return emptyList()
        val classes = mutableListOf<Triple<String, String, String>>()
        for (index in 0 until data.length()) {
            val raw = data.optJSONObject(index) ?: continue
            val classId = jsonText(raw, "classId")
            if (classId.isEmpty() || classId == "0") continue
            classes += Triple(
                classId,
                jsonText(raw, "className"),
                firstText(raw, "teacherName", "teacher"),
            )
        }
        return classes
    }

    private fun requireIdentity(): AppIdentity =
        identity ?: throw AuthError("登录状态已失效，请重新登录账号")

    private fun url(path: String): String = settings.appHost.trimEnd('/') + path

    private fun permissionError(path: String): PermissionDenied =
        PermissionDenied("接口 ${permissionEndpoint(path)} 没有访问权限（403），请核对接口与账号角色")

    private fun saveSession() {
        val store = sessionStore ?: return
        val who = identity ?: return
        val access = token ?: return
        val refresh = refreshToken ?: return
        store.save(
            mapOf(
                "account" to who.account,
                "user_id" to who.userId,
                "user_name" to who.userName,
                "user_type_id" to who.userTypeId,
                "access_token" to access,
                "access_expires_at" to tokenExpiresAt.toString(),
                "refresh_token" to refresh,
                "refresh_expires_at" to refreshExpiresAt.toString(),
            )
        )
    }

    private fun restorePersisted() {
        val store = sessionStore ?: return
        val values = store.load()
        if (values.isNullOrEmpty()) return
        val tokenExpires = values["access_expires_at"]?.toLongOrNull()
        val refreshExpires = values["refresh_expires_at"]?.toLongOrNull()
        val access = values["access_token"].orEmpty()
        val refresh = values["refresh_token"].orEmpty()
        val account = values["account"].orEmpty()
        val userId = values["user_id"].orEmpty()
        val userType = values["user_type_id"].orEmpty()
        if (tokenExpires == null || refreshExpires == null || access.isEmpty() || refresh.isEmpty() ||
            account.isEmpty() || userId.isEmpty() || userType.isEmpty() || refreshExpires <= clock.wallMs()
        ) {
            store.clear()
            return
        }
        authLock.withLock {
            token = access
            refreshToken = refresh
            tokenExpiresAt = tokenExpires
            refreshExpiresAt = refreshExpires
            identity = AppIdentity(account, userId, values["user_name"].orEmpty(), userType)
        }
    }

    private fun requireSuccess(payload: JSONObject, fallback: String) {
        if (jsonSuccess(payload)) return
        val message = jsonText(payload, "message").trim()
        throw AppApiError(message.ifBlank { fallback })
    }

    private fun firstText(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val text = jsonText(obj, key).trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun disambiguate(courses: List<Course>): List<Course> {
        val counts = courses.groupingBy { it.name }.eachCount()
        if (counts.values.all { it == 1 }) return courses
        return courses.map { course ->
            if (counts[course.name] == 1) course
            else course.copy(name = "${course.name} · ${course.classId}")
        }
    }
}
