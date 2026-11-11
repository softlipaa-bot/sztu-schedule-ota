package com.sztu.schedule.data.hwsys

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * 教学辅助系统的 HTTPS JSON。
 *
 * SSO 交换对齐当前官网页面：POST /api/sso_callback，正文只有 access_token_sso。
 * 官网在还没有系统令牌时不会加 Authorization。兄弟项目会把 SSO 令牌同时放进 Bearer，
 * 那是旧客户端的做法，这里不照搬，避免服务端把 SSO 令牌当成已经失效的系统令牌。
 */
class HwsysHttp : AttendanceApi {
    override fun getAttendance(token: String, start: LocalDate, end: LocalDate, page: Int?): Envelope {
        val (status, body) = request("出勤查询", "GET", attendanceUrl(start, end, page), token, null)
        return parseEnvelope(status, body)
    }

    fun verifyAccess(token: String): Envelope {
        val today = LocalDate.now(SHANGHAI)
        return getAttendance(token, today, today, null)
    }

    fun refresh(refreshToken: String): TokenBundle {
        val (status, body) = request("登录续期", "PUT", HwsysUrls.TOKEN, refreshToken, "{}")
        return requireBundle("登录续期", status, body)
    }

    fun exchangeSso(ssoToken: String): TokenBundle {
        val payload = JSONObject().put("access_token_sso", ssoToken).toString()
        val (status, body) = request("SSO交换", "POST", HwsysUrls.SSO_CALLBACK, null, payload)
        return requireBundle("SSO交换", status, body)
    }

    private fun requireBundle(stage: String, status: Int, body: String): TokenBundle {
        interpretHttp(stage, status, body)?.let { throw it }
        return parseTokenBundle(body)
            ?: throw HwsysCallException(stage, status, null, "parse", "$stage：响应格式变化（HTTP $status）")
    }

    private fun request(stage: String, method: String, url: String, bearer: String?, jsonBody: String?): Pair<Int, String> {
        NetworkThreadGuard.mark(stage)
        if (!url.startsWith("https://")) throw IOException("$stage：只接受 HTTPS")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            if (status in 300..399) {
                throw HwsysCallException(stage, status, null, "redirect", "$stage：服务返回跳转，已中止（HTTP $status）")
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            conn.disconnect()
        }
    }
}
