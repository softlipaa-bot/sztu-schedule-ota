package com.sztu.schedule.duifene

/**
 * 服务端以最后一次签到请求为起点计时。静默期内不能再发签到请求。
 */
class RateLimiter(
    private val settings: DuifeneSettings,
    private val log: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var untilMs: Long = 0L

    fun remainingMs(now: Long = nowMs()): Long = (untilMs - now).coerceAtLeast(0L)

    fun remainingSeconds(now: Long = nowMs()): Double = remainingMs(now) / 1000.0

    fun isBlocking(now: Long = nowMs()): Boolean = remainingMs(now) > 0L

    fun raiseIfBlocking() {
        val left = remainingSeconds()
        if (left > 0) throw RateLimited("限流静默中，还需 ${left.toInt()} 秒才恢复")
    }

    fun trip() {
        if (remainingMs() > 0L) return
        untilMs = nowMs() + (settings.cooldownSeconds * 1000.0).toLong()
        log(
            "服务端提示签到过于频繁，已进入静默，" +
                "${settings.cooldownSeconds.toInt()} 秒后恢复"
        )
    }

    fun isLimitText(text: String): Boolean = settings.rateLimitMarkers.any { it in text }

    fun clear() {
        untilMs = 0L
    }
}
