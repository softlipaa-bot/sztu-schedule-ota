package com.sztu.schedule.duifene

import org.json.JSONObject
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

interface WebMaterials {
    fun clear()
    fun fetchToken(courseId: String): String
    fun fetchNumericMaterials(classId: String, activityIds: Set<String>): Map<String, JSONObject>
}

interface ActivitySource {
    val appAuthenticated: Boolean
    val identity: AppIdentity?
    val limiter: RateLimiter
    fun fetchActiveActivities(course: Course, includeNumericMaterials: Boolean = true): List<AppCheckIn>
    fun refreshNumericMaterials(course: Course, activities: List<AppCheckIn>): List<AppCheckIn>
}

interface SignBoard {
    val identity: AppIdentity?
    val limiter: RateLimiter
    var allowSubmit: () -> Boolean
    var sameSession: () -> Boolean
    var locationFix: () -> GeoPoint?
    fun fetchHeadcount(activityId: String): Pair<Int, Int>
    fun signByCode(code: String): String
    fun signByQr(token: String): String
    fun signByLocation(longitude: Double, latitude: Double): String
    fun confirmOwnStatus(classId: String, activityId: String): Boolean?
}

/**
 * 从当前学生的个人记录发现活动，再按类型补材料。
 * 不使用教师 Cookie，也不把 GetModelByInProgress 当发现入口。
 */
class DuifeneApi(
    private val appApi: DfAppApi,
    private val webApi: WebMaterials,
    private val settings: DuifeneSettings,
    override val limiter: RateLimiter,
    private val clock: DfClock = SystemDfClock,
    private val credentials: () -> WebCredential? = { null },
) : ActivitySource, SignBoard {
    private val operationLock = Any()
    private val knownCourses = mutableSetOf<Pair<String, String>>()
    private var loginCredential: WebCredential? = null

    override var allowSubmit: () -> Boolean = { true }
    override var sameSession: () -> Boolean = { true }
    override var locationFix: () -> GeoPoint? = { null }

    override val appAuthenticated: Boolean get() = appApi.isAuthenticated
    override val identity: AppIdentity? get() = appApi.identity

    fun clearSession() {
        synchronized(operationLock) {
            appApi.clear()
            webApi.clear()
            loginCredential = null
            knownCourses.clear()
        }
    }

    fun ensureSessionFresh(force: Boolean = false): Boolean = appApi.ensureSessionFresh(force)

    fun loginByPassword(username: String, password: String, retainPassword: Boolean = false): String {
        synchronized(operationLock) {
            knownCourses.clear()
            webApi.clear()
            loginCredential = null
            return if (appApi.login(username, password)) {
                if (retainPassword) loginCredential = WebCredential(username.trim(), password)
                "登录成功"
            } else {
                "登录失败，请检查账号、密码、网络或代理设置"
            }
        }
    }

    fun rememberCredential(credential: WebCredential?) {
        synchronized(operationLock) {
            loginCredential = credential
        }
    }

    fun fetchCourses(): List<Course> {
        synchronized(operationLock) {
            val courses = appApi.fetchCourses()
            knownCourses.clear()
            knownCourses += courses.map { it.courseId to it.classId }
            return courses
        }
    }

    fun rememberCourses(courses: List<Course>) {
        synchronized(operationLock) {
            knownCourses.clear()
            knownCourses += courses.map { it.courseId to it.classId }
        }
    }

    fun currentWebCredential(): WebCredential? {
        val who = identity ?: return null
        if (who.userTypeId != "2") return null
        val credential = loginCredential ?: credentials()
        return credential?.takeIf { it.account == who.account }
    }

    fun fetchActiveActivities(course: Course): List<AppCheckIn> = fetchActiveActivities(course, true)

    override fun fetchActiveActivities(course: Course, includeNumericMaterials: Boolean): List<AppCheckIn> {
        synchronized(operationLock) {
            val who = identity
            if (who == null || who.userTypeId != "2") {
                throw PermissionDenied("监听需要当前学生身份，请登录学生账号")
            }
            if (course.courseId to course.classId !in knownCourses) {
                throw PermissionDenied("所选课程不属于当前学生账号已加载的教学班")
            }
            val records = appApi.fetchStudentRecords(course.classId)
            val candidates = linkedMapOf<String, JSONObject>()
            val now = clock.shanghaiNow()
            for (row in records) {
                if (jsonText(row, "CheckInStatus") == "1" || jsonText(row, "StatusID") == "1") continue
                val age = try {
                    java.time.Duration.between(parseCheckinTime(jsonText(row, "CreaterDate")), now).toMillis() / 1000.0
                } catch (error: DateTimeParseException) {
                    throw AppApiError("学生签到记录创建时间异常，未处理该轮活动")
                }
                if (age < 0 || age > settings.staleSignMinutes * 60) continue
                candidates[jsonText(row, "ID")] = row
            }
            if (candidates.isEmpty()) return emptyList()
            val activities = mutableListOf<AppCheckIn>()
            for ((activityId, student) in candidates) {
                val loaded = appApi.fetchCheckin(activityId)
                    ?: throw AppApiError("已发现学生签到记录，但未取得活动详情")
                val activity = loaded.copy(
                    studentId = who.userId,
                    checkInStatus = jsonText(student, "CheckInStatus"),
                    statusId = jsonText(student, "StatusID"),
                    statusName = jsonText(student, "StatusName"),
                )
                if (activity.activityId != activityId || activity.classId != course.classId) {
                    throw PermissionDenied("签到详情的活动或教学班归属不匹配，已停止处理")
                }
                if (!activity.hasValidTimes) throw AppApiError("签到有效期数据异常，未处理该轮活动")
                if (activity.isDeleted || activity.hasEnded(now)) continue
                activities += activity
            }
            var current = if (includeNumericMaterials) {
                refreshNumericMaterialsLocked(course, activities)
            } else {
                activities
            }
            val classes = knownCourses.filter { it.first == course.courseId }.map { it.second }.toSet()
            if (current.size == 1 && classes.size == 1) {
                val activity = current[0]
                if (activity.checkType == "2" && activity.qrToken.isEmpty()) {
                    val token = webApi.fetchToken(course.courseId)
                    if (token.isNotEmpty()) {
                        val fresh = appApi.fetchCheckin(activity.activityId)
                        if (fresh == null || fresh.activityId != activity.activityId ||
                            fresh.classId != course.classId || fresh.checkType != "2"
                        ) {
                            throw PermissionDenied("二维码查询后活动归属或类型已变化，未提交")
                        }
                        val freshNow = clock.shanghaiNow()
                        if (!fresh.hasValidTimes || fresh.isDeleted || fresh.hasEnded(freshNow)) return emptyList()
                        current = listOf(
                            activity.copy(
                                qrToken = token,
                                createDate = fresh.createDate,
                                validDate = fresh.validDate,
                            )
                        )
                    }
                }
            }
            return current
        }
    }

    override fun refreshNumericMaterials(course: Course, activities: List<AppCheckIn>): List<AppCheckIn> {
        synchronized(operationLock) {
            return refreshNumericMaterialsLocked(course, activities)
        }
    }

    private fun refreshNumericMaterialsLocked(course: Course, source: List<AppCheckIn>): List<AppCheckIn> {
        val who = identity
        if (who == null || who.userTypeId != "2") throw PermissionDenied("取码需要当前学生身份")
        if (course.courseId to course.classId !in knownCourses) {
            throw PermissionDenied("取码课程不属于当前学生已加载的教学班")
        }
        for (activity in source) {
            if (activity.studentId != who.userId || activity.classId != course.classId) {
                throw PermissionDenied("取码活动与当前学生或教学班不匹配，未提交")
            }
            if (!activity.hasValidTimes) throw AppApiError("取码活动有效期异常，未提交")
        }
        val now = clock.shanghaiNow()
        var activities = source.filter { activity ->
            !activity.isDeleted && !activity.hasEnded(now) && !activity.isAttended &&
                activity.ageSeconds(now)?.let { it in 0.0..(settings.staleSignMinutes * 60.0) } == true
        }
        val missing = activities.filter { it.checkType == "1" && !it.validCode() }.map { it.activityId }.toSet()
        if (missing.isNotEmpty()) {
            val materials = webApi.fetchNumericMaterials(course.classId, missing)
            val updated = mutableListOf<AppCheckIn>()
            for (activity in activities) {
                val row = materials[activity.activityId]
                var next = activity
                if (row != null) {
                    if (jsonText(row, "CheckInType") != "1") {
                        throw PermissionDenied("数字签到材料类型与活动不匹配，未提交")
                    }
                    if (deletedFlag(row.opt("IsDel"))) continue
                    val deadline = try {
                        minOf(
                            parseCheckinTime(activity.validDate),
                            parseCheckinTime(jsonText(row, "ValidDate")),
                        )
                    } catch (error: DateTimeParseException) {
                        throw AppApiError("数字签到材料有效期异常，未提交")
                    }
                    if (!now.isBefore(deadline)) continue
                    val code = row.opt("CheckInCode")
                    if (code is String && code.length == 4 && code.all { it in '0'..'9' }) {
                        next = activity.copy(
                            code = code,
                            validDate = deadline.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        )
                    }
                }
                updated += next
            }
            activities = updated
        }
        return activities
    }

    override fun fetchHeadcount(activityId: String): Pair<Int, Int> = appApi.fetchHeadcount(activityId)

    override fun signByCode(code: String): String = guardRateLimit(appApi.signByCode(code))

    override fun signByQr(token: String): String = guardRateLimit(appApi.signByQr(token))

    override fun signByLocation(longitude: Double, latitude: Double): String =
        guardRateLimit(appApi.signByLocation(longitude, latitude))

    override fun confirmOwnStatus(classId: String, activityId: String): Boolean? {
        return try {
            val rows = appApi.fetchStudentRecords(classId)
            val row = rows.firstOrNull { jsonText(it, "ID") == activityId } ?: return null
            jsonText(row, "CheckInStatus") == "1" || jsonText(row, "StatusID") == "1"
        } catch (_: AppApiError) {
            null
        } catch (_: AuthError) {
            throw AuthError("登录状态已失效，请重新登录账号")
        }
    }

    private fun guardRateLimit(message: String): String {
        if (limiter.isLimitText(message)) {
            limiter.trip()
            throw RateLimited("服务端提示签到过于频繁，已静默 ${settings.cooldownSeconds.toInt()} 秒")
        }
        return message
    }
}
