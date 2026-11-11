package com.sztu.schedule.data.hwsys

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.IOException

/**
 * 给界面看的失败原因。只保留阶段、异常类型、HTTP 状态和业务 status_code，
 * 不带令牌、Cookie、带参数的回调地址或响应正文。
 */
class HwsysCallException(
    val stage: String,
    val httpStatus: Int,
    val businessCode: Int?,
    val kind: String,
    message: String,
) : IOException(message)

fun attendanceFailure(envelope: Envelope): String {
    val http = envelope.statusCode
    val business = if (http == 401 || http == 403) null else http.takeIf { it != 0 }
    return when {
        envelope.authRequired -> "出勤查询失败：会话已失效（HTTP $http）"
        !envelope.shapeOk -> "出勤查询失败：响应格式变化（HTTP $http）"
        business != null && business !in 200..299 -> "出勤查询失败：请求失败（HTTP $business）"
        else -> "出勤查询失败：请求失败（HTTP $http）"
    }
}

fun safeFailure(stage: String, error: Throwable): String {
    if (error is CancellationException) throw error
    if (error is HwsysCallException) return error.message ?: "$stage：请求失败"
    val type = error.javaClass.simpleName.ifBlank { "Exception" }
    val why = when {
        type.contains("SSL", ignoreCase = true) || type.contains("Cert", ignoreCase = true) -> "证书校验失败"
        type == "NetworkOnMainThreadException" -> "网络跑在主线程"
        else -> "网络连接失败"
    }
    return "$stage：$why（$type）"
}

fun interpretHttp(stage: String, httpStatus: Int, body: String?): HwsysCallException? {
    val business = businessCode(body)
    val auth = httpStatus == 401 || httpStatus == 403 || business == 401 || business == 403
    if (auth) {
        val shown = if (httpStatus > 0) httpStatus else business ?: 401
        return HwsysCallException(stage, shown, business, "auth", "$stage：会话已失效（HTTP $shown）")
    }
    if (httpStatus in 200..299 && parseTokenBundle(body) == null && stage != "出勤查询") {
        return HwsysCallException(stage, httpStatus, business, "parse", "$stage：响应格式变化（HTTP $httpStatus）")
    }
    if (httpStatus !in 200..299) {
        val code = if (business != null) "，status_code $business" else ""
        return HwsysCallException(stage, httpStatus, business, "http", "$stage：请求失败（HTTP $httpStatus$code）")
    }
    return null
}

private fun businessCode(body: String?): Int? {
    if (body.isNullOrBlank() || !body.trim().startsWith("{")) return null
    val obj = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    if (!obj.has("status_code")) return null
    return obj.optInt("status_code")
}
