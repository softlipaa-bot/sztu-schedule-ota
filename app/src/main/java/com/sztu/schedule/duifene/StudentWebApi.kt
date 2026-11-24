package com.sztu.schedule.duifene

import org.json.JSONObject

/**
 * 学生网页会话只留在内存。网页登录失败不会清除 App Token。
 * 移植自 bytehola/duifene_auto_sign ce1608e。
 */
class StudentWebApi(
    private val settings: DuifeneSettings,
    private val transport: DfTransport,
    private val credentials: () -> WebCredential?,
    private val decoder: QrImageDecoder,
) : WebMaterials {
    private val cookies = linkedMapOf<String, String>()
    private val lock = Any()
    private var loggedIn = false

    override fun clear() {
        synchronized(lock) {
            loggedIn = false
            cookies.clear()
        }
    }

    override fun fetchToken(courseId: String): String = withLogin { image(courseId) }

    override fun fetchNumericMaterials(classId: String, activityIds: Set<String>): Map<String, JSONObject> {
        if (activityIds.isEmpty()) return emptyMap()
        return withLogin { numericMaterials(classId, activityIds) }
    }

    private fun <T> withLogin(operation: () -> T): T {
        synchronized(lock) {
            var lastExpired = false
            repeat(2) { attempt ->
                if (!loggedIn) login()
                try {
                    return operation()
                } catch (_: ExpiredWebSession) {
                    clear()
                    lastExpired = true
                    if (attempt == 1) {
                        throw WebSessionError("学生网页会话无法恢复，请重新登录学生账号")
                    }
                }
            }
            if (lastExpired) throw WebSessionError("学生网页会话无法恢复，请重新登录学生账号")
            throw WebSessionError("学生网页会话无法恢复，请重新登录学生账号")
        }
    }

    private fun login() {
        val credential = credentials()
            ?: throw WebSessionError("缺少当前学生账号的网页登录凭据，请重新登录学生账号")
        clear()
        val home = exchange("GET", "/Home.aspx", null, null)
        if (home.status != 200) throw AppApiError("学生网页登录入口请求失败（HTTP ${home.status}）")
        val result = exchange(
            "POST",
            "/AppCode/LoginInfo.ashx",
            mapOf(
                "action" to "loginmb",
                "loginname" to credential.account,
                "password" to credential.password,
            ),
            WEB_HOST + "/AppGate.aspx",
        )
        val payload = try {
            JSONObject(result.body)
        } catch (_: Exception) {
            throw WebSessionError("学生网页登录返回异常，请重新登录学生账号")
        }
        if (result.status != 200 || jsonText(payload, "msgbox") != "登录成功") {
            throw WebSessionError("学生网页登录失败，请检查保存的凭据或重新登录学生账号")
        }
        loggedIn = true
    }

    private fun image(courseId: String): String {
        val result = exchange(
            "POST",
            "/_CheckIn/CheckIn.ashx",
            mapOf("action" to "getcodeimage", "cid" to courseId),
            WEB_HOST + "/_CheckIn/PC/TeachCheckIn.aspx",
        )
        if (result.status in REDIRECTS || result.status == 401) throw ExpiredWebSession()
        if (result.status == 403) throw PermissionDenied("当前学生账号无权读取该课程的二维码（403）")
        if (result.status != 200) throw AppApiError("网页二维码请求失败（HTTP ${result.status}）")
        val payload = try {
            JSONObject(result.body)
        } catch (_: Exception) {
            if ("topLogin_tbLoginName" in result.body) throw ExpiredWebSession()
            throw AppApiError("网页二维码返回了无法解析的数据")
        }
        val message = jsonText(payload, "msgbox")
        if (jsonCode(payload) == "403" || "权限" in message) {
            throw PermissionDenied("当前学生账号无权读取该课程的二维码（403）")
        }
        if ("登录" in message) throw ExpiredWebSession()
        if (jsonText(payload, "msg") != "1") return ""
        val data = payload.opt("data")
        if (data !is String) throw AppApiError("网页二维码图片数据异常")
        return decodeQrToken(data, decoder)
    }

    private fun numericMaterials(classId: String, activityIds: Set<String>): Map<String, JSONObject> {
        val result = exchange(
            "POST",
            "/_CheckIn/MBCount.ashx",
            mapOf(
                "action" to "gettclasscheckinlistbypage",
                "classid" to classId,
                "page" to "1",
                "nums" to "10",
            ),
            null,
        )
        if (result.status in REDIRECTS || result.status == 401) throw ExpiredWebSession()
        if (result.status == 403) throw PermissionDenied("当前学生账号无权读取该教学班的签到材料（403）")
        if (result.status != 200) throw AppApiError("学生网页签到材料请求失败（HTTP ${result.status}）")
        val payload = try {
            JSONObject(result.body)
        } catch (_: Exception) {
            if ("topLogin_tbLoginName" in result.body) throw ExpiredWebSession()
            throw AppApiError("网页签到材料返回了无法解析的数据")
        }
        val message = jsonText(payload, "msgbox").ifBlank { jsonText(payload, "message") }
        if (jsonCode(payload) == "403" || "权限" in message) {
            throw PermissionDenied("当前学生账号无权读取该教学班的签到材料（403）")
        }
        if ("登录" in message) throw ExpiredWebSession()
        if (!payload.has("rows") || payload.isNull("rows")) throw AppApiError("网页签到材料未返回记录列表")
        val rows = payload.optJSONArray("rows") ?: throw AppApiError("网页签到材料未返回记录列表")
        val matches = linkedMapOf<String, JSONObject>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: throw AppApiError("网页签到材料条目格式异常")
            if (jsonText(row, "TClassID") != classId) {
                throw PermissionDenied("网页签到材料的教学班归属不匹配，未提交")
            }
            val activityId = jsonText(row, "ID")
            if (activityId in activityIds) {
                if (matches.containsKey(activityId)) throw AppApiError("网页签到材料包含重复活动，未提交")
                matches[activityId] = row
            }
        }
        return matches
    }

    private fun exchange(method: String, path: String, form: Map<String, String>?, referer: String?): DfReply {
        val headers = linkedMapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "User-Agent" to WEB_USER_AGENT,
        )
        if (cookies.isNotEmpty()) headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (referer != null) headers["Referer"] = referer
        val body = form?.let { formBody(it) }
        if (body != null) headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        val reply = try {
            transport.exchange(
                DfCall(
                    method = method,
                    url = WEB_HOST + path,
                    headers = headers,
                    body = body,
                    followRedirects = false,
                )
            )
        } catch (_: TransportException) {
            throw AppApiError("学生网页签到材料查询网络失败")
        }
        absorb(reply)
        return reply
    }

    private fun absorb(reply: DfReply) {
        for ((name, value) in reply.headerList) {
            if (!name.equals("Set-Cookie", ignoreCase = true)) continue
            val pair = value.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq).trim()
            val cookie = pair.substring(eq + 1).trim()
            if (key.isEmpty()) continue
            if (cookie.isEmpty()) cookies.remove(key) else cookies[key] = cookie
        }
    }

    private class ExpiredWebSession : Exception()

    companion object {
        const val WEB_HOST = "https://www.duifene.com"
        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

data class WebCredential(val account: String, val password: String)
