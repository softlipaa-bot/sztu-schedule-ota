package com.sztu.schedule.duifene

data class DfCall(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val followRedirects: Boolean = false,
)

data class DfReply(
    val status: Int,
    val body: String,
    val headerList: List<Pair<String, String>> = emptyList(),
)

fun interface DfTransport {
    fun exchange(call: DfCall): DfReply
}

class ScriptedTransport : DfTransport {
    val calls = mutableListOf<DfCall>()
    private val script = ArrayDeque<DfReply>()

    fun enqueue(status: Int, body: String, headers: List<Pair<String, String>> = emptyList()) {
        script.addLast(DfReply(status, body, headers))
    }

    override fun exchange(call: DfCall): DfReply {
        calls += call
        if (script.isEmpty()) error("没有预设响应：${call.method} ${call.url}")
        return script.removeFirst()
    }
}

fun urlPath(raw: String): String =
    java.net.URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")

fun formBody(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) ->
        urlPath(key) + "=" + urlPath(value)
    }

fun permissionEndpoint(path: String): String {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }.take(2)
    return "/" + parts.joinToString("/")
}

fun checkImageBounds(width: Int, height: Int) {
    if (width <= 0 || height <= 0) throw AppApiError("网页二维码图片无法解析")
    if (width.toLong() * height.toLong() > DuifenePolicy.MAX_QR_PIXELS) {
        throw AppApiError("网页二维码图片尺寸异常")
    }
}
