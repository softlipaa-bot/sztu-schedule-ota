package com.sztu.schedule.duifene

/**
 * 四类签到的提交判断。
 * 定位使用调用方提供的真实坐标；取不到有效定位时不提交、也不报成功。
 * 人数门槛、有效期和去重语义对齐 duifene_auto_sign ce1608e。
 */
class ActivityProcessor(
    private val settings: DuifeneSettings,
    private val api: SignBoard,
    private val log: (String) -> Unit,
    private val clock: DfClock = SystemDfClock,
    private val ledger: PendingLedger = PendingLedger(),
) {
    private val handled = mutableSetOf<String>()
    private val pendingConfirm = mutableSetOf<String>()
    private val headcountLog = mutableMapOf<String, Pair<Int, Int>>()
    private val headcountCache = mutableMapOf<Triple<String, String, String>, Pair<Long, Pair<Int, Int>>>()
    private val resultLogged = mutableSetOf<String>()
    private val waitLogged = mutableSetOf<Pair<String, String>>()
    var statusText: String = ""
        private set
    var statusPhase: String = "none"
        private set

    fun reset() {
        handled.clear()
        pendingConfirm.clear()
        pendingConfirm += ledger.snapshot()
        headcountLog.clear()
        headcountCache.clear()
        resultLogged.clear()
        waitLogged.clear()
        statusText = ""
        statusPhase = "none"
    }

    fun process(activity: AppCheckIn, course: Course): Outcome {
        val key = dedupeKey(activity)
        if (key in handled) {
            markStatus("skipped", "这一活动已经处理过")
            return Outcome.SKIPPED
        }
        val who = api.identity
        if (who == null || activity.studentId != who.userId || activity.classId != course.classId) {
            throw AuthError("签到活动与当前学生或教学班不匹配，请重新登录学生账号")
        }
        if (activity.isDeleted) {
            markHandled(course, activity, "活动已作废", "voided")
            return Outcome.SKIPPED
        }
        val now = clock.shanghaiNow()
        if (!activity.hasValidTimes) {
            logWait(activity, "活动有效期未知，暂不提交", course, "waiting_material")
            return Outcome.DEFERRED
        }
        if (activity.isAttended) {
            markHandled(course, activity, "已签到，无需重复签到", "already")
            return Outcome.SKIPPED
        }
        if (activity.hasEnded(now)) {
            markHandled(course, activity, "已超过签到结束时间", "expired")
            return Outcome.SKIPPED
        }
        val age = activity.ageSeconds(now)
        if (age == null || age < 0) {
            logWait(activity, "活动创建时间异常，暂不提交", course, "waiting_material")
            return Outcome.DEFERRED
        }
        if (age > settings.staleSignMinutes * 60) {
            markHandled(course, activity, "创建已超过 ${settings.staleSignMinutes} 分钟，视为旧活动", "expired")
            return Outcome.SKIPPED
        }
        if (key in pendingConfirm) {
            if (!api.sameSession()) return discard()
            val confirmed = try {
                api.confirmOwnStatus(course.classId, activity.activityId)
            } catch (error: AuthError) {
                throw error
            }
            when (confirmed) {
                null -> {
                    logWait(activity, "提交结果未知，正在回查本人记录", course, "pending_confirm")
                    return Outcome.PENDING
                }
                true -> {
                    pendingConfirm.remove(key)
                    ledger.remove(key)
                    markHandled(course, activity, "回查确认已签到", "already")
                    return Outcome.SKIPPED
                }
                false -> {
                    pendingConfirm.remove(key)
                    ledger.remove(key)
                }
            }
        }
        if (api.limiter.isBlocking(clock.wallMs())) {
            val left = api.limiter.remainingSeconds(clock.wallMs()).toInt()
            logCourse(course, "限流中，$left 秒后恢复")
            markStatus("waiting_condition", "限流中，$left 秒后恢复")
            return Outcome.DEFERRED
        }
        if (activity.checkType == "1" && !activity.validCode()) {
            logWait(activity, "已发现活动，但当前学生查询未返回有效签到码", course, "waiting_material")
            return Outcome.DEFERRED
        }
        if (activity.checkType == "4" && activity.code.length != 4) {
            logWait(activity, "已发现活动，但当前学生查询未返回汉字答案", course, "waiting_material")
            return Outcome.DEFERRED
        }
        if (activity.checkType == "2" && activity.qrToken.isEmpty()) {
            logWait(activity, "已发现活动，但当前学生查询未返回二维码令牌", course, "waiting_material")
            return Outcome.DEFERRED
        }
        val gate = headcountGate(activity, age, course)
        if (gate != null) return gate
        return execute(activity, course)
    }

    private fun headcountGate(activity: AppCheckIn, age: Double, course: Course): Outcome? {
        val cacheKey = Triple(activity.studentId, activity.classId, activity.activityId)
        val cached = headcountCache[cacheKey]
        val counts = if (cached != null && clock.monoMs() - cached.first < DuifenePolicy.HEADCOUNT_CACHE_MS) {
            cached.second
        } else {
            val fresh = api.fetchHeadcount(activity.activityId)
            headcountCache[cacheKey] = clock.monoMs() to fresh
            fresh
        }
        val (signed, total) = counts
        if (total <= 0) {
            markStatus("waiting_condition", "人数尚未就绪")
            return Outcome.DEFERRED
        }
        if (total < settings.smallClassMax) {
            if (age < settings.smallClassDelayS) {
                val wait = (settings.smallClassDelayS - age).toInt()
                logCourse(course, "活动 ${activity.activityId} 小班 $total 人，$wait 秒后签到")
                markStatus("waiting_condition", "小班 $total 人，$wait 秒后签到")
                return Outcome.DEFERRED
            }
            return null
        }
        val ratio = signed.toDouble() / total.toDouble()
        if (ratio <= settings.headcountRatio) {
            val marker = signed to total
            if (headcountLog[activity.activityId] != marker) {
                headcountLog[activity.activityId] = marker
                logCourse(
                    course,
                    "活动 ${activity.activityId} 已签 $signed/$total" +
                        "（${(ratio * 100).toInt()}%），未达 ${(settings.headcountRatio * 100).toInt()}% 门槛，继续等待",
                )
            }
            markStatus("waiting_condition", "已签 $signed/$total，未达到人数门槛")
            return Outcome.DEFERRED
        }
        return null
    }

    private fun execute(activity: AppCheckIn, course: Course): Outcome {
        if (!api.allowSubmit() || !api.sameSession()) return discard()
        markStatus("submitting", "正在提交")
        val outcome = try {
            when (activity.checkType) {
                "1" -> signCode(activity, course)
                "2" -> signQr(activity, course)
                "3" -> signLocation(activity, course)
                "4" -> signHanzi(activity, course)
                else -> {
                    logResult(activity, course, "活动 ${activity.activityId} 类型未知（${activity.checkType}），跳过", "failed")
                    return Outcome.SKIPPED
                }
            }
        } catch (error: RateLimited) {
            logCourse(course, "活动 ${activity.activityId} ${error.message}")
            markStatus("waiting_condition", error.message ?: "签到过于频繁")
            return Outcome.DEFERRED
        } catch (error: UnconfirmedSubmit) {
            val key = dedupeKey(activity)
            pendingConfirm += key
            ledger.mark(key)
            logWait(activity, "提交结果未知，改为回查本人记录", course, "pending_confirm")
            return Outcome.PENDING
        }
        if (outcome == Outcome.SIGNED || outcome == Outcome.SKIPPED) {
            if (outcome == Outcome.SIGNED) handled += dedupeKey(activity)
            if (outcome == Outcome.SKIPPED && statusPhase == "failed") handled += dedupeKey(activity)
        }
        if (!api.sameSession()) return discard()
        return outcome
    }

    private fun signCode(activity: AppCheckIn, course: Course): Outcome {
        if (!activity.validCode()) {
            logWait(activity, "未返回有效签到码", course, "waiting_material")
            return Outcome.DEFERRED
        }
        logAttempt(activity, course, "活动 ${activity.activityId} 提交数字签到")
        return report(activity, api.signByCode(activity.code), course)
    }

    private fun signQr(activity: AppCheckIn, course: Course): Outcome {
        if (activity.qrToken.isEmpty()) {
            logWait(activity, "未返回二维码令牌", course, "waiting_material")
            return Outcome.DEFERRED
        }
        logAttempt(activity, course, "活动 ${activity.activityId} 提交二维码签到")
        return report(activity, api.signByQr(activity.qrToken), course)
    }

    private fun signHanzi(activity: AppCheckIn, course: Course): Outcome {
        if (activity.code.length != 4) {
            logWait(activity, "未返回有效汉字答案", course, "waiting_material")
            return Outcome.DEFERRED
        }
        logAttempt(activity, course, "活动 ${activity.activityId} 提交汉字签到")
        return report(activity, api.signByCode(activity.code), course)
    }

    private fun signLocation(activity: AppCheckIn, course: Course): Outcome {
        val fix = api.locationFix()
        if (fix == null) {
            logWait(activity, "未取得有效定位，暂不提交", course, "waiting_material")
            return Outcome.DEFERRED
        }
        if (!api.allowSubmit() || !api.sameSession()) return discard()
        logAttempt(activity, course, "活动 ${activity.activityId} 提交当前定位")
        val message = api.signByLocation(fix.longitude, fix.latitude)
        return if (isSigned(message)) {
            logResult(activity, course, "定位签到成功", "success")
            Outcome.SIGNED
        } else {
            logResult(activity, course, "定位签到失败：${message.ifBlank { "无响应" }}", "failed")
            Outcome.SKIPPED
        }
    }

    private fun report(activity: AppCheckIn, message: String?, course: Course): Outcome {
        return if (isSigned(message)) {
            logResult(activity, course, "签到成功", "success")
            Outcome.SIGNED
        } else {
            logResult(activity, course, "签到失败：${message ?: "无响应"}", "failed")
            Outcome.SKIPPED
        }
    }

    private fun discard(): Outcome {
        markStatus("none", "会话已更换，已丢弃迟到结果")
        return Outcome.DISCARDED
    }

    private fun dedupeKey(activity: AppCheckIn): String =
        "${activity.studentId}|${activity.classId}|${activity.activityId}"

    private fun markHandled(course: Course, activity: AppCheckIn, reason: String, phase: String) {
        handled += dedupeKey(activity)
        logCourse(course, "活动 ${activity.activityId} $reason，跳过")
        markStatus(phase, reason)
    }

    private fun logWait(activity: AppCheckIn, reason: String, course: Course, phase: String) {
        val marker = activity.activityId to reason
        if (marker !in waitLogged) {
            waitLogged += marker
            logCourse(course, "活动 ${activity.activityId} $reason，继续重试")
        }
        markStatus(phase, reason)
    }

    private fun logAttempt(activity: AppCheckIn, course: Course, message: String) {
        if (activity.activityId !in resultLogged) logCourse(course, message)
    }

    private fun logResult(activity: AppCheckIn, course: Course, message: String, phase: String) {
        if (activity.activityId !in resultLogged) {
            resultLogged += activity.activityId
            logCourse(course, message)
        }
        markStatus(phase, message)
    }

    private fun logCourse(course: Course, message: String) {
        log("课程: ${course.name} $message")
    }

    private fun markStatus(phase: String, text: String) {
        statusPhase = phase
        statusText = text
    }

    private fun isSigned(message: String?): Boolean =
        !message.isNullOrEmpty() && "签到成功" in message && "未成功" !in message
}
