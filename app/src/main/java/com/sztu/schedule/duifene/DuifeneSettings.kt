package com.sztu.schedule.duifene

/**
 * 运行参数。默认值对齐 duifene_auto_sign commit ce1608e（bytehola，MIT）。
 * 本应用不把这些策略改成更激进的轮询。
 */
class DuifeneSettings(
    val appHost: String = "https://dfapp.duifene.com",
    val userAgent: String = "okhttp/4.12.0",
    var proxyUrl: String = "",
    var verifyTls: Boolean = true,
    val requestTimeoutMs: Int = 10_000,
    var pollMinS: Double = 3.0,
    var pollMaxS: Double = 5.0,
    var errorBackoffMaxS: Double = 60.0,
    var staleSignMinutes: Int = 30,
    var smallClassMax: Int = 5,
    var smallClassDelayS: Double = 5.0,
    var headcountRatio: Double = 0.20,
    var monitorStartHour: Int = 6,
    var monitorEndHour: Int = 23,
    var cooldownSeconds: Double = 300.0,
    val rateLimitMarkers: List<String> = listOf("过于频繁", "频繁"),
) {
    fun pollDelaySeconds(randomUnit: Double = Math.random()): Double {
        if (pollMinS >= pollMaxS) return pollMinS
        val unit = randomUnit.coerceIn(0.0, 1.0)
        return pollMinS + (pollMaxS - pollMinS) * unit
    }
}

object DuifenePolicy {
    const val SOURCE_REPO = "https://github.com/bytehola/duifene_auto_sign"
    const val SOURCE_COMMIT = "ce1608e492949e34c2239a7d442dc4fde207dd9b"
    const val NUMERIC_POLL_S = 0.2
    const val NUMERIC_BACKOFF_MAX_S = 2.0
    const val NUMERIC_PRIORITY_WINDOW_S = 5.0
    const val HEADCOUNT_CACHE_MS = 1_000L
    const val MAX_LISTEN_MINUTES = 120
    const val ACCESS_TOKEN_LIFETIME_MS = 30L * 60L * 1000L
    const val REFRESH_TOKEN_LIFETIME_MS = 7L * 24L * 60L * 60L * 1000L
    const val TOKEN_REFRESH_AHEAD_MS = 5L * 60L * 1000L
    const val SESSION_CHECK_MS = 60_000L
    const val MAX_QR_TEXT_CHARS = 2_000_000
    const val MAX_QR_PIXELS = 4_000_000L
    const val RECORD_LIMIT = 100

    val numericBackoffSteps = doubleArrayOf(0.4, 0.8, 1.6, 2.0)
}
